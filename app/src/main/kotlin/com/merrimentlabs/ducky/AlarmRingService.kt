package com.merrimentlabs.ducky

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.CombinedVibration
import android.os.IBinder
import android.os.PowerManager
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.VibratorManager
import android.provider.Settings
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Rings until dismissed: alarm-stream audio (cuts through DND-as-alarm) +
 * vibration + the full-screen intent notification that launches AlarmActivity.
 */
class AlarmRingService : Service() {

    @VisibleForTesting internal var player: MediaPlayer? = null

    /** Test seam: a player that fails to construct or to start. */
    @VisibleForTesting internal var playerFactory: () -> MediaPlayer = { MediaPlayer() }

    // A throw inside a launch here would take the whole process down while
    // the foreground alarm notification is up (the placeholder card keeps its
    // Stop action, but the ring is gone). Log and stay alive instead.
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main +
            CoroutineExceptionHandler { _, e -> Log.e(TAG, "ring coroutine failed", e) }
    )

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_REPOST) {
            scope.launch {
                val state = TimerStore.state(this@AlarmRingService)
                if (state.isRinging) postRingCard(state)
            }
            return START_STICKY
        }
        // The ring session (TimerController.EXTRA_RING_SESSION) arrives with
        // the start intent. A START_STICKY restart after the OS killed us
        // mid-ring delivers a null intent: the placeholder then has no
        // actions until the persisted state is read a few ms later.
        val session = intent?.getLongExtra(TimerController.EXTRA_RING_SESSION, 0L) ?: 0L
        // Plain notification first (startForeground must not wait on I/O),
        // then immediately re-notify with the accent-colored card.
        startForeground(
            TimerController.ALARM_NOTIFICATION_ID,
            buildAlarmNotification(theme = null, session = session.takeIf { it > 0 }),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED
        )
        scope.launch {
            val state = TimerStore.state(this@AlarmRingService)
            if (!state.isRinging) {
                // Sticky restart for a ring that was stopped while we were dead.
                releaseStartWakeLock()
                stopSelf()
                return@launch
            }
            val settings = TimerStore.settings(this@AlarmRingService)
            // Vibration first and on its own: an audio failure (bad saved URI,
            // codec, focus) must never be able to skip the haptic ring.
            if (settings.vibrate) runCatching { startVibration() }
                .onFailure { Log.e(TAG, "vibration failed", it) }
            requestAudioFocus()
            startSound(settings.soundUri)
            postRingCard(state)
        }
        return START_STICKY // if the OS kills us mid-ring, come back ringing
    }

    /** The accent-colored ring card with this ring's +1 min and Stop. */
    private suspend fun postRingCard(state: TimerState) {
        (getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager).notify(
            TimerController.ALARM_NOTIFICATION_ID,
            buildAlarmNotification(
                TimerNotifications.notifTheme(this@AlarmRingService),
                state.totalMillis,
                state.ringingSinceMillis,
            )
        )
    }

    /**
     * Native template with two actions: "+1 min" and "Stop". Both route
     * through TimerReceiver with the ring session, so they work from the
     * heads-up card, the shade, and the lock screen without unlocking or
     * opening the app, and outlive this service's own scope.
     */
    private fun buildAlarmNotification(
        theme: TimerNotifications.NotifTheme?,
        totalMillis: Long = 0L,
        session: Long?,
    ): Notification {
        val fullScreenPi = PendingIntent.getActivity(
            this, RequestCodes.RING_FULL_SCREEN,
            Intent(this, AlarmActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(this, TimerController.ALARM_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_timer)
            .setContentTitle(getString(R.string.alarm_done))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setFullScreenIntent(fullScreenPi, true)
            .setContentIntent(fullScreenPi)
        if (totalMillis > 0) {
            builder.setContentText(
                getString(R.string.notification_total, TimerMath.formatClock(totalMillis))
            )
        }
        if (session != null) {
            builder.addAction(
                0, getString(R.string.action_extend),
                TimerController.ringActionPendingIntent(
                    this, TimerController.ACTION_EXTEND_RING, session
                )!!
            )
            builder.addAction(
                0, getString(R.string.action_stop),
                TimerController.ringActionPendingIntent(
                    this, TimerController.ACTION_STOP_RING, session
                )!!
            )
        }
        if (theme != null) builder.setColor(theme.accent)
        return builder.build()
    }

    /**
     * Bounded fallback chain: the saved ringtone (may be unreadable: the file
     * moved, the provider revoked, the URI from another user profile), then
     * the system default alarm, then the default ringtone. Each step logs;
     * running out of candidates leaves vibration + the notification ringing.
     * Both the synchronous throw from setDataSource/prepareAsync AND the async
     * onError callback step down the same chain.
     */
    private fun startSound(soundUri: String?) {
        val candidates = listOfNotNull(
            soundUri?.let(Uri::parse),
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
            Settings.System.DEFAULT_RINGTONE_URI,
        ).distinct()
        tryStartSound(candidates, 0)
    }

    private fun tryStartSound(candidates: List<Uri>, index: Int) {
        releasePlayer()
        if (index >= candidates.size) {
            Log.e(TAG, "no playable alarm sound; ringing with vibration + notification only")
            releaseStartWakeLock()
            return
        }
        val uri = candidates[index]
        val next = { reason: String ->
            Log.w(TAG, "alarm sound $uri failed ($reason); falling back")
            tryStartSound(candidates, index + 1)
        }
        // Construction and start() can throw too; every failure steps down the same chain.
        val created = runCatching { playerFactory() }.getOrElse {
            next(it.toString())
            return
        }
        player = created
        var prepared = false
        runCatching {
            created.apply {
                // Keeps the CPU awake while the alarm plays, independent of the audio route.
                setWakeMode(this@AlarmRingService, PowerManager.PARTIAL_WAKE_LOCK)
                setAudioAttributes(ALARM_ATTRIBUTES)
                setOnErrorListener { _, what, extra ->
                    // Only this player's error advances the chain; a stale
                    // player already replaced by a later step is ignored.
                    if (player === created) next("onError what=$what extra=$extra")
                    true
                }
                setDataSource(this@AlarmRingService, uri)
                isLooping = true
                setOnPreparedListener {
                    if (player === created) {
                        prepared = true
                        runCatching { it.start() }
                            // Playing audio holds the device awake from here on.
                            .onSuccess { releaseStartWakeLock() }
                            .onFailure { e -> next("start: $e") }
                    }
                }
                prepareAsync()
            }
            // A source that never answers (neither prepared nor error) would leave the alarm
            // silent with the rest of the chain untried. Canceled with the service's scope.
            scope.launch {
                delay(PREPARE_TIMEOUT_MS)
                if (player === created && !prepared) next("no answer in $PREPARE_TIMEOUT_MS ms")
            }
        }.onFailure { next(it.toString()) }
    }

    private var focusRequest: AudioFocusRequest? = null

    /**
     * Transient focus with alarm attributes: music or a podcast pauses (or ducks) while the alarm
     * rings and resumes after Stop, as with the Clock app. The ring plays whether or not focus is
     * granted, and a later focus loss (a call) does not silence it.
     */
    private fun requestAudioFocus() {
        if (focusRequest != null) return
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(ALARM_ATTRIBUTES)
            .build()
        focusRequest = request
        runCatching { getSystemService(AudioManager::class.java)?.requestAudioFocus(request) }
            .onFailure { Log.w(TAG, "audio focus request failed; ringing anyway", it) }
    }

    private fun abandonAudioFocus() {
        val request = focusRequest ?: return
        focusRequest = null
        runCatching { getSystemService(AudioManager::class.java)?.abandonAudioFocusRequest(request) }
    }

    /** Idempotent, safe in any MediaPlayer state (Idle/Preparing/Error included). */
    private fun releasePlayer() {
        val p = player ?: return
        player = null
        runCatching { p.stop() }
        runCatching { p.release() }
    }

    private fun startVibration() {
        val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
        val effect = VibrationEffect.createWaveform(
            longArrayOf(0, 700, 500, 700, 1000), 0
        )
        vm.vibrate(
            CombinedVibration.createParallel(effect),
            VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM)
        )
    }

    override fun onDestroy() {
        running = false
        releaseStartWakeLock()
        releasePlayer()
        abandonAudioFocus()
        runCatching {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).cancel()
        }
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "AlarmRingService"

        /**
         * True from onCreate to onDestroy, in this process. A saved ring while this is false has
         * nothing playing it: its start threw, or the process died before the service came up.
         */
        @Volatile internal var running = false

        /** Re-post the ring card from saved state; sound and vibration are left as they are. */
        internal const val ACTION_REPOST = "com.merrimentlabs.ducky.action.REPOST_RING"

        /**
         * Notifications were just allowed: a card posted while they were off was dropped, so a
         * ring playing now has none in the shade (its Stop and +1 min included).
         */
        fun repostIfRinging(context: Context) {
            if (running) context.startService(Intent(context, AlarmRingService::class.java).setAction(ACTION_REPOST))
        }

        private val ALARM_ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        /** How long one sound may take to prepare before the chain moves to the next. */
        private const val PREPARE_TIMEOUT_MS = 3_000L

        /**
         * Bridges the gap between the FIRE broadcast and the first sound. The receiver
         * finishes (and AlarmManager drops its delivery wake lock) as soon as this call
         * returns, but the service starts asynchronously and then reads DataStore before
         * it plays anything. With the screen off and no full-screen launch to wake it,
         * the CPU could sleep in between and the alarm would start late. Timed, so a
         * service that never starts can't hold it.
         */
        private var startWakeLock: PowerManager.WakeLock? = null
        private const val START_WAKE_LOCK_MS = 30_000L

        @Synchronized
        private fun acquireStartWakeLock(context: Context) {
            val pm = context.getSystemService(PowerManager::class.java) ?: return
            startWakeLock?.let { if (it.isHeld) it.release() }
            startWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Ducky:alarm-start")
                .apply { setReferenceCounted(false); acquire(START_WAKE_LOCK_MS) }
        }

        @Synchronized
        internal fun releaseStartWakeLock() {
            startWakeLock?.let { if (it.isHeld) it.release() }
        }

        fun start(context: Context, session: Long) {
            acquireStartWakeLock(context.applicationContext)
            context.startForegroundService(
                Intent(context, AlarmRingService::class.java)
                    .putExtra(TimerController.EXTRA_RING_SESSION, session)
            )
        }
    }
}
