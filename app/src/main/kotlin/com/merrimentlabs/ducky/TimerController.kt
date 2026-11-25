package com.merrimentlabs.ducky

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.net.toUri
import com.merrimentlabs.ducky.widget.TimerWidget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The engine. Every mutation goes: DataStore -> AlarmManager -> notification ->
 * widget refresh request, in that order, so a crash mid-way leaves the alarm
 * consistent with what's persisted. The widget render itself happens off the
 * critical section (see [refreshWidget]).
 *
 * Every public mutation is a read-compute-write over a DataStore snapshot, and
 * callers (Compose tap scope, Glance action worker, TimerReceiver on Default)
 * overlap freely: two taps could compute from the same snapshot and the last
 * write would win (a lost increment). [mutex] serializes them. Mutex is NOT
 * reentrant: the public entry points take the lock once and delegate to the
 * private `*Locked` bodies, which must never re-acquire it.
 */
object TimerController {

    private val mutex = Mutex()

    private const val TAG = "TimerController"

    /**
     * Widget re-render seam. Production = Glance snapshot delivery; tests swap in a
     * throwing or never-returning lambda to prove the state machine neither
     * fails nor waits on the widget.
     */
    internal var widgetRefresher: suspend (Context) -> Unit = { TimerWidget.refreshAll(it) }

    /**
     * Widget delivery runs OUTSIDE the mutex. Glance's updateAll is IPC to the
     * launcher (RemoteViews inflation, provider lookups): it can throw, and it
     * can simply be slow or suspended, and a mutation that awaited it under
     * the lock would hold the next Stop, +1 min, or due FIRE behind display
     * work. So [refreshWidget] only records that a render is wanted and
     * returns; one job on [widgetScope] drains the requests one at a time.
     *
     * Ordering: requests coalesce into a `pending` flag, and each render reads
     * the persisted state at render time, so the last render always reflects
     * the newest state: an older queued render can never overwrite a newer
     * running/ringing/idle presentation. Each render is bounded by
     * [WIDGET_REFRESH_TIMEOUT_MS]; a failure or timeout is logged and the
     * next request renders again. Cancellation propagates (it is how the
     * job is torn down), everything else is a presentation failure.
     */
    private val widgetScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val widgetLock = Any()
    private var widgetJob: Job? = null
    private var widgetPending = false
    // Each provider gets its own bound (TimerWidget.PROVIDER_TIMEOUT_MS), so this one only has
    // to outlast four of them.
    private const val WIDGET_REFRESH_TIMEOUT_MS = 22_000L

    private fun refreshWidget(context: Context) {
        val app = context.applicationContext
        synchronized(widgetLock) {
            widgetPending = true
            if (widgetJob != null) return
            widgetJob = widgetScope.launch { drainWidgetRequests(app) }
        }
    }

    private suspend fun drainWidgetRequests(app: Context) {
        try {
            drainLoop(app)
        } catch (e: CancellationException) {
            // Whatever canceled this drain (teardown, or a cancellation thrown out of Glance),
            // it must not keep the slot: a job that still looks busy would swallow every later
            // request. A request still pending is rendered by a fresh job.
            val job = coroutineContext[Job]
            synchronized(widgetLock) {
                if (widgetJob === job) {
                    widgetJob = null
                    if (widgetPending) widgetJob = widgetScope.launch { drainWidgetRequests(app) }
                }
            }
            throw e
        }
    }

    private suspend fun drainLoop(app: Context) {
        while (true) {
            synchronized(widgetLock) {
                // Decide "done" under the same lock a new request checks, so a
                // request can't slip in between "no more pending" and exit.
                if (!widgetPending) {
                    widgetJob = null
                    return
                }
                widgetPending = false
            }
            try {
                withTimeoutOrNull(WIDGET_REFRESH_TIMEOUT_MS) { widgetRefresher(app) }
                    ?: Log.w(TAG, "widget refresh timed out (state already persisted)")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "widget refresh failed (state already persisted)", e)
            }
        }
    }

    /**
     * Waits, at most [timeoutMs], for the widget renders requested so far. A broadcast (a FIRE,
     * Stop from the card, a widget button) finishes right after its command, and Android may then
     * reclaim the process before the render reached the launcher: the widget would keep the old
     * picture, with nothing left to fix it until the next update. Receivers call this after the
     * command, outside the mutex; the bound stays under a foreground broadcast's 10 s.
     */
    suspend fun awaitWidgetDelivery(timeoutMs: Long = WIDGET_AWAIT_MS) {
        val job = synchronized(widgetLock) { widgetJob } ?: return
        withTimeoutOrNull(timeoutMs) { job.join() }
    }

    private const val WIDGET_AWAIT_MS = 7_000L

    /** Tests: drop any in-flight render so a hung seam can't bleed into the next test. */
    internal fun resetWidgetDelivery() {
        val job = synchronized(widgetLock) {
            widgetPending = false
            widgetJob.also { widgetJob = null }
        }
        job?.cancel()
    }

    const val COUNTDOWN_CHANNEL_ID = "countdown_v2"
    const val ALARM_CHANNEL_ID = "alarm"
    const val ALARM_NOTIFICATION_ID = 2

    const val ACTION_FIRE = "com.merrimentlabs.ducky.FIRE"
    const val ACTION_CANCEL = "com.merrimentlabs.ducky.CANCEL"
    const val ACTION_PAUSE = "com.merrimentlabs.ducky.PAUSE"
    const val ACTION_RESUME = "com.merrimentlabs.ducky.RESUME"
    const val ACTION_TICK = "com.merrimentlabs.ducky.TICK"
    const val ACTION_STOP_RING = "com.merrimentlabs.ducky.STOP_RING"
    const val ACTION_EXTEND_RING = "com.merrimentlabs.ducky.EXTEND_RING"

    /**
     * Ring-session identity. Every surface that offers Stop / +1 min on a
     * ringing alarm carries the `ringingSinceMillis` of the ring it was drawn
     * for, and the handlers act only if that is still the ring in progress.
     * A delayed Stop from the old card can therefore never erase the timer a
     * +1 min just started, and a duplicated +1 min can't turn one minute into
     * two (after the first, the state is running, not ringing).
     */
    const val EXTRA_RING_SESSION = "com.merrimentlabs.ducky.RING_SESSION"

    /** "+1 min" on a ringing alarm is fixed, independent of the quick-add presets. */
    const val EXTEND_MILLIS = 60_000L

    /**
     * Lifetime for actions started from UI surfaces that may die mid-action
     * (AlarmActivity finishes as soon as the ring stops; AlarmRingService's
     * scope is canceled by stopService; the timer screen's
     * rememberCoroutineScope dies with its composition, which a Cancel tap
     * causes in the same frame). The durable transition + exact-alarm
     * scheduling must run to completion regardless, so they run here.
     */
    private val actionScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun requestStopRing(context: Context, session: Long) {
        val app = context.applicationContext
        actionScope.launch { stopRinging(app, session) }
    }

    fun requestExtendRing(context: Context, session: Long) {
        val app = context.applicationContext
        actionScope.launch { extendRing(app, session) }
    }

    /** Settings "Test alarm": the screen that asks for it is gone a frame later. */
    fun requestTestAlarm(context: Context) {
        val app = context.applicationContext
        actionScope.launch { testAlarm(app) }
    }

    /**
     * The countdown commands, for tap handlers on the timer screen. Same
     * contract as the ring requests above: the DataStore write and the
     * AlarmManager call outlive whatever composition asked for them, so a
     * screen that goes away mid-tap cannot strand a half-applied transition.
     */
    fun requestAddTime(context: Context, addMillis: Long) {
        val app = context.applicationContext
        actionScope.launch { addTime(app, addMillis) }
    }

    fun requestTogglePause(context: Context) {
        val app = context.applicationContext
        actionScope.launch { togglePause(app) }
    }

    fun requestCancel(context: Context) {
        val app = context.applicationContext
        actionScope.launch { cancel(app) }
    }

    /**
     * The timer screen opened: re-derive what a lost alarm left behind. On Android 14 a force-stop
     * removes the alarm but keeps the FIRE PendingIntent, and the next launch gets no
     * BOOT_COMPLETED (Android 15+ sends one, so the boot path runs there; measured on an API 34
     * emulator). Without this, a running timer counted down to a deadline that never
     * rang, and a passed one sat behind an idle-looking screen until a clock change (TIME_SET
     * trusts the surviving PendingIntent) rang it by surprise. A running timer is re-armed (the
     * same PendingIntent replaces itself); a deadline its FIRE missed gets the boot rule.
     */
    fun requestRearm(context: Context) {
        val app = context.applicationContext
        actionScope.launch { rearm(app) }
    }

    suspend fun rearm(context: Context) = mutex.withLock {
        val state = TimerStore.state(context)
        val now = System.currentTimeMillis()
        when {
            state.isRunning(now) -> scheduleAlarm(context, state.endTimeMillis)
            // Passed by more than a FIRE (or its retry) takes: that alarm is gone.
            !state.isPaused && !state.isRinging && state.endTimeMillis > 0 &&
                now - state.endTimeMillis > LOST_FIRE_GRACE_MS -> {
                if (now - state.endTimeMillis <= STALE_RING_MS) withFireRetry(context, now) { ringLocked(context, now) }
                else clearStaleLocked(context, state)
                refreshWidget(context)
            }
        }
    }

    /** Longer than a FIRE's delivery plus one retry ([FIRE_RETRY_MS]). */
    private const val LOST_FIRE_GRACE_MS = 15_000L

    private val settingsScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * Settings writes, for the same reason as the countdown commands: a choice made just before
     * leaving Settings (or rotating) must still land. Main.immediate keeps the writes in tap order
     * (each reaches DataStore before the next tap's handler runs), and widgets re-render through
     * the protected delivery path, never awaited here.
     */
    fun requestSettingsWrite(
        context: Context,
        refreshWidgets: Boolean = false,
        write: suspend (Context) -> Unit,
    ) {
        val app = context.applicationContext
        settingsScope.launch {
            write(app)
            if (refreshWidgets) refreshWidget(app)
        }
    }

    /**
     * Broadcast PendingIntent for a Stop / +1 min notification action. The
     * session rides in the data URI as well as the extra so each ring gets
     * its own PendingIntent identity: FLAG_UPDATE_CURRENT can't retarget a
     * stale card's button at a newer ring.
     */
    fun ringActionPendingIntent(
        context: Context,
        action: String,
        session: Long,
        create: Boolean = true,  // false: look up an existing one (to cancel it)
    ): PendingIntent? =
        PendingIntent.getBroadcast(
            context,
            if (action == ACTION_STOP_RING) RequestCodes.STOP_RING else RequestCodes.EXTEND_RING,
            Intent(context, TimerReceiver::class.java)
                .setAction(action)
                .setData("ducky://ring/$session/$action".toUri())
                .putExtra(EXTRA_RING_SESSION, session),
            (if (create) PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_NO_CREATE) or
                PendingIntent.FLAG_IMMUTABLE
        )

    suspend fun addTime(context: Context, addMillis: Long) = mutex.withLock {
        addTimeLocked(context, addMillis)
    }

    private suspend fun addTimeLocked(context: Context, addMillis: Long) {
        val now = System.currentTimeMillis()
        val state = TimerStore.state(context)
        // A tap while paused resumes: any + tap must leave a scheduled alarm
        // behind (the correctness anchor), never a silently-frozen timer.
        val baseEnd =
            if (state.isPaused) now + state.pausedRemainingMillis else state.endTimeMillis
        val end = TimerMath.newEndTime(now, baseEnd, addMillis)
        val total =
            (if (state.isRunning(now) || state.isPaused) state.totalMillis else 0L) + addMillis
        if (state.isRinging) {
            // A tap while the alarm is ringing means "next timer, stop yelling": save the
            // fresh timer, then silence the ring. A failed write (a full disk) leaves the ring
            // playing, with its Stop and +1 min, rather than silenced with no timer behind it.
            try {
                TimerStore.setTimer(context, end, total)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "timer state write failed; the ring keeps playing", e)
                return
            }
            silenceRing(context, state.ringingSinceMillis)
        } else {
            TimerStore.setTimer(context, end, total)
        }
        scheduleAlarm(context, end)
        postCountdownNotification(context, end, total)
        scheduleTick(context, total)
        refreshWidget(context)
    }

    suspend fun pause(context: Context) = mutex.withLock { pauseLocked(context) }

    private suspend fun pauseLocked(context: Context) {
        val now = System.currentTimeMillis()
        val state = TimerStore.state(context)
        if (!state.isRunning(now)) return
        val remaining = state.endTimeMillis - now
        TimerStore.setPaused(context, remaining)
        alarmManager(context).cancel(firePendingIntent(context))
        alarmManager(context).cancel(tickPendingIntent(context))
        TimerNotifications.postPaused(context, remaining)
        refreshWidget(context)
    }

    suspend fun resume(context: Context) = mutex.withLock { resumeLocked(context) }

    private suspend fun resumeLocked(context: Context) {
        val state = TimerStore.state(context)
        if (!state.isPaused) return
        val end = System.currentTimeMillis() + state.pausedRemainingMillis
        TimerStore.setTimer(context, end, state.totalMillis)
        scheduleAlarm(context, end)
        postCountdownNotification(context, end, state.totalMillis)
        scheduleTick(context, state.totalMillis)
        refreshWidget(context)
    }

    /**
     * Widget water-level heartbeat: a NON-WAKEUP alarm chain, display-only,
     * that re-renders the draining water while a timer runs. It never wakes
     * the device (screen off = nobody watching the widget), self-terminates
     * when the timer stops, and is fully separate from the firing anchor.
     */
    // The countdown card is a promoted ongoing notification (a Live Update on Android 16 and
    // newer): re-posting it every widget tick would be abusive. The widget redraws on every tick
    // (finer), the notification only every COUNTDOWN_REPOST_MS (its chronometer text ticks
    // per-second for free).
    @Volatile private var lastCountdownPostMs = 0L
    private const val COUNTDOWN_REPOST_MS = 12_000L

    suspend fun onTick(context: Context) {
        mutex.withLock {
            val now = System.currentTimeMillis()
            val state = TimerStore.state(context)
            if (!state.isRunning(now)) return@withLock // dead chain: cancel/pause/fire won
            scheduleTick(context, state.totalMillis)
            // On unless the APK was built with -PwidgetHeartbeat=false (app/build.gradle.kts).
            if (BuildConfig.WIDGET_HEARTBEAT) {
                // A fresh display timestamp forces a new Glance snapshot even
                // though the timer deadline itself has not changed.
                TimerStore.touch(context, now)
                refreshWidget(context)
            }
            if (now - lastCountdownPostMs >= COUNTDOWN_REPOST_MS) {
                postCountdownNotification(context, state.endTimeMillis, state.totalMillis)
            }
        }
    }

    // Lint expects SCHEDULE_EXACT_ALARM here. Ducky holds USE_EXACT_ALARM instead (the manifest),
    // the exact-alarm permission for timer and alarm apps, which the user can't revoke.
    @SuppressLint("MissingPermission")
    private fun scheduleTick(context: Context, totalMillis: Long) {
        // About 90 steps per timer, 3 to 15 s apart. A RemoteViews widget can't
        // animate smoothly (updateAll is IPC and rate-limited), so the widget
        // water drains in small steps instead of the per-frame motion of the pond.
        val interval = (totalMillis / 90).coerceIn(3_000L, 15_000L)
        alarmManager(context).setExact(
            AlarmManager.RTC, // no _WAKEUP: display-only
            System.currentTimeMillis() + interval,
            tickPendingIntent(context)
        )
    }

    private fun tickPendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context, RequestCodes.TICK,
            Intent(context, TimerReceiver::class.java).setAction(ACTION_TICK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    suspend fun togglePause(context: Context) = mutex.withLock {
        // One lock acquisition for the read AND the branch: the state checked
        // here is the state pause/resume act on.
        if (TimerStore.state(context).isPaused) resumeLocked(context) else pauseLocked(context)
    }

    /**
     * Cancel the current timer, whatever its state. A Cancel can be queued
     * behind a FIRE (tap on the countdown card or the in-app button in the
     * last quarter-second): by the time it runs the timer is RINGING, and
     * clearing that state without silencing the ring would strand an alarm
     * no surface can stop: Stop / +1 min fail the session guard, a + tap
     * finds nothing ringing, AlarmActivity closes. Invariant: no path clears
     * a ring without silencing it.
     */
    suspend fun cancel(context: Context) = mutex.withLock {
        val before = TimerStore.state(context)
        persistBestEffort { TimerStore.clearTimer(context) }
        // Retire the FIRE token too, not only its alarm: TIME_SET treats an existing token as
        // "this deadline is still armed" (fireArmed), and if the clear above failed, a later
        // clock step must not ring the timer the person just canceled.
        firePendingIntent(context).let { alarmManager(context).cancel(it); it.cancel() }
        alarmManager(context).cancel(tickPendingIntent(context))
        TimerNotifications.cancelCountdown(context)
        if (before.isRinging) silenceRing(context, before.ringingSinceMillis)
        refreshWidget(context)
    }

    /**
     * Called from TimerReceiver when the AlarmManager FIRE broadcast lands.
     * Guarded, not trusted: TimerReceiver launches every broadcast in its own
     * coroutine, so a FIRE enqueued a beat before a cancel / pause / + tap can
     * be ordered BEHIND that mutation by the mutex. The persisted deadline,
     * not the fact of delivery, decides whether there is anything to ring.
     */
    suspend fun onAlarmFired(context: Context) = mutex.withLock { onAlarmFiredLocked(context) }

    /**
     * How early a FIRE may arrive and still count as the real deadline.
     * setAlarmClock never fires early by contract, but `now` is sampled after
     * wake-up + receiver dispatch against a wall clock that may have been
     * stepped (NTP) since the alarm was scheduled; any such slop is sub-second.
     * 1.5 s absorbs it while staying far below the smallest increment a user
     * can add (5 s, TimerStore.INCREMENT_CHOICES), so a genuine extension
     * can never be mistaken for an early delivery.
     */
    private const val FIRE_TOLERANCE_MS = 1_500L

    private suspend fun onAlarmFiredLocked(context: Context) {
        val now = System.currentTimeMillis()
        val state = TimerStore.state(context)
        if (state.isRinging) {
            // A ring that was saved but never started (its service start threw): this retry
            // FIRE is all that is left to start it, on the same session, so the Stop and +1 min
            // already on screen still reach it. A ring that is playing is left alone.
            if (!AlarmRingService.running && now - state.ringingSinceMillis <= STALE_RING_MS) {
                withFireRetry(context, now) {
                    AlarmRingService.start(context, session = state.ringingSinceMillis)
                    // The failed start skipped ringLocked's refresh: the widgets show the ring now.
                    refreshWidget(context)
                }
            }
            return
        }
        val due = state.endTimeMillis > 0 &&
            !state.isPaused &&
            state.endTimeMillis <= now + FIRE_TOLERANCE_MS
        // Stale FIRE: canceled (end=0), paused (end=0), or extended (end in
        // the future): nothing to ring. The negative tests pin this behavior.
        if (!due) {
            // Extended: the newer deadline must have its own alarm. Normally the
            // extension already armed it (same PendingIntent, so this replaces it
            // with itself); if the process died between persisting the extension
            // and scheduling it, this is what arms it.
            if (state.endTimeMillis > now) scheduleAlarm(context, state.endTimeMillis)
            return
        }
        withFireRetry(context, now) { ringLocked(context, now) }
    }

    /**
     * Runs a ring start; if it throws, this FIRE is spent, so arm another. A failed state write
     * (a full disk) leaves the deadline due and the retry rings it; a failed service start after
     * the ring was saved leaves a ring nothing plays, and the retry starts it (above).
     */
    private suspend fun withFireRetry(context: Context, now: Long, start: suspend () -> Unit) {
        try {
            start()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "ring start failed; retrying", e)
            scheduleAlarm(context, now + FIRE_RETRY_MS)
        }
    }

    /** How soon a FIRE whose ring failed to start is tried again. */
    private const val FIRE_RETRY_MS = 5_000L

    /**
     * The unconditional ring body. Two callers, both already holding the mutex
     * and both having decided the timer IS due: [onAlarmFiredLocked] after its
     * deadline guard, and [rescheduleAfterBoot] after its own state check
     * (which also covers the END_TIME=0 / RINGING_SINCE>0 mid-ring reboot the
     * deadline guard would reject).
     */
    private suspend fun ringLocked(context: Context, now: Long) {
        TimerStore.setRinging(context, now)
        alarmManager(context).cancel(tickPendingIntent(context))
        TimerNotifications.cancelCountdown(context)
        AlarmRingService.start(context, session = now)
        refreshWidget(context)
    }

    /**
     * Stop the ring identified by [session]. A Stop for any other ring (one
     * already dismissed, or one a +1 min has since replaced with a running
     * timer) is stale and does nothing. Never clears state unconditionally.
     */
    suspend fun stopRinging(context: Context, session: Long) = mutex.withLock {
        val state = TimerStore.state(context)
        if (!state.isRinging || state.ringingSinceMillis != session) return@withLock
        // Persist first, then silence, then (best-effort, last) the widget.
        // The widget observes persisted state, so it catches up either way.
        // A failed write (storage full) must still silence the ring: the
        // persisted ring stays, so Stop on the timer screen can try again.
        persistBestEffort { TimerStore.clearTimer(context) }
        silenceRing(context, session)
        refreshWidget(context)
    }

    /**
     * "+1 min" on the ring identified by [session]: silence it and start a
     * fresh [EXTEND_MILLIS] timer from now. Same session rule as
     * [stopRinging]; a second delivery finds the state running, not ringing,
     * and is dropped, while intentional +1m taps on the RUNNING timer still
     * add normally through [addTime].
     */
    suspend fun extendRing(context: Context, session: Long) = mutex.withLock {
        val state = TimerStore.state(context)
        if (!state.isRinging || state.ringingSinceMillis != session) return@withLock
        addTimeLocked(context, EXTEND_MILLIS)
    }

    /**
     * Dismiss the alarm card, stop the ring service, and retire the ring's
     * two action PendingIntents. Shared by every stop path. The
     * PendingIntents are per-session (data URI), so without the cancel each
     * ring left a pair registered in the system until reboot.
     */
    private fun silenceRing(context: Context, session: Long) {
        TimerNotifications.dismissAlarm(context)
        context.stopService(Intent(context, AlarmRingService::class.java))
        for (action in listOf(ACTION_STOP_RING, ACTION_EXTEND_RING)) {
            ringActionPendingIntent(context, action, session, create = false)?.cancel()
        }
    }

    /**
     * How long after its deadline (or after its ring started) a timer found on
     * boot still rings. A reboot leaves no alarm behind, so the time is the only
     * way to tell "ended while the phone restarted" from a state nothing has
     * served for days: a force-stop (which cancels the alarm) followed by a
     * later reboot, or a timer restored from a backup onto a new phone. Older
     * than this, the timer is cleared quietly instead of ringing out of nowhere.
     */
    internal const val STALE_RING_MS = 60 * 60_000L

    /**
     * Runs a DataStore write whose failure must not skip what follows (silencing a
     * ring, canceling an alarm). An exception escaping the action scope would crash
     * the process, and the sticky ring service would come back ringing.
     */
    private suspend fun persistBestEffort(write: suspend () -> Unit) {
        try {
            write()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "timer state write failed", e)
        }
    }

    /** Re-arm after reboot (AlarmManager state dies with the OS). */
    suspend fun rescheduleAfterBoot(context: Context) = mutex.withLock { rescheduleLocked(context) }

    /**
     * This app was updated. An alarm that outlived the update would still point at the component
     * that set it, and an update that renames components leaves it firing into nothing where no Cancel
     * could reach it. Defensive: on the emulator an `adb install -r` left no old alarm anyway
     * (the install force-stops the app). Clear every alarm this app holds, then re-derive
     * everything from the persisted state, like a boot.
     */
    suspend fun onPackageReplaced(context: Context) = mutex.withLock {
        alarmManager(context).cancelAll()
        rescheduleLocked(context)
    }

    private suspend fun rescheduleLocked(context: Context) {
        val state = TimerStore.state(context)
        val now = System.currentTimeMillis()
        when {
            state.isRunning(now) -> {
                scheduleAlarm(context, state.endTimeMillis)
                postCountdownNotification(context, state.endTimeMillis, state.totalMillis)
                scheduleTick(context, state.totalMillis)
            }
            // Paused survives reboot as-is: no alarm existed to re-arm.
            state.isPaused -> TimerNotifications.postPaused(context, state.pausedRemainingMillis)
            // End time passed while powered off (or mid-ring): ring now, if
            // recent. Calls the body directly: this branch IS the due-check for
            // boot, and the mid-ring case (end=0) would not pass the FIRE guard.
            // A failed start arms the same retry a FIRE would (no alarm survived the boot).
            state.isRinging ->
                if (now - state.ringingSinceMillis <= STALE_RING_MS) withFireRetry(context, now) { ringLocked(context, now) }
                else clearStaleLocked(context, state)
            state.endTimeMillis > 0 ->
                if (now - state.endTimeMillis <= STALE_RING_MS) withFireRetry(context, now) { ringLocked(context, now) }
                else clearStaleLocked(context, state)
        }
        refreshWidget(context)
    }

    /**
     * A timer nothing will ever ring for: clear it the way Cancel does (a
     * persisted ring is silenced too, so the ring invariant holds).
     */
    private suspend fun clearStaleLocked(context: Context, before: TimerState) {
        TimerStore.clearTimer(context)
        alarmManager(context).cancel(tickPendingIntent(context))
        TimerNotifications.cancelCountdown(context)
        if (before.isRinging) silenceRing(context, before.ringingSinceMillis)
    }

    /**
     * The wall clock was stepped (ACTION_TIME_CHANGED: NTP correction, manual
     * change, travel). The persisted deadline is wall-clock and stays the
     * truth, a 12:10 timer still fires at 12:10 on the new clock, so this
     * only re-derives everything that hangs off `now`: the RTC alarm and the
     * tick chain are re-armed from the deadline, the widget's Chronometer
     * base is recomputed, and a deadline the clock jumped past rings now
     * (when its alarm is still armed; see the branch below).
     * Paused (no alarm exists) and ringing (already playing; a new session
     * would orphan the Stop / +1 min surfaces drawn for the old one) are
     * left alone. Idempotent against AlarmManager's own re-evaluation: if the
     * FIRE lands first the state is ringing and this does nothing; if this
     * lands first the FIRE finds end=0 and is dropped.
     */
    suspend fun onTimeChanged(context: Context) = mutex.withLock {
        val state = TimerStore.state(context)
        val now = System.currentTimeMillis()
        when {
            state.isRinging || state.isPaused -> return@withLock
            state.isRunning(now) -> {
                scheduleAlarm(context, state.endTimeMillis)
                postCountdownNotification(context, state.endTimeMillis, state.totalMillis)
                scheduleTick(context, state.totalMillis)
            }
            // The deadline is behind the new clock. A live timer still has its
            // FIRE alarm armed (AlarmManager also delivers it after the step;
            // whichever lands first rings, the other finds nothing to do). No
            // armed FIRE means nothing has served this deadline for a while (a
            // force-stop cancels the app's alarms, a backup restore brings none),
            // so clear it rather than ring a long-gone timer.
            state.endTimeMillis > 0 ->
                if (fireArmed(context)) ringLocked(context, now) else clearStaleLocked(context, state)
            else -> return@withLock
        }
        refreshWidget(context)
    }

    /** Whether the FIRE PendingIntent still exists, i.e. a timer alarm was armed and not torn down. */
    private fun fireArmed(context: Context): Boolean =
        PendingIntent.getBroadcast(
            context, RequestCodes.FIRE, fireIntent(context),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        ) != null

    /**
     * Settings "Test alarm": a genuine ring session through the normal path
     * (notification + full-screen intent + AlarmRingService + widget RINGING),
     * so what the user hears and sees is exactly what a real timer does.
     * Only from idle: it must never displace a running, paused, or ringing
     * timer. total stays 0, which the surfaces already treat as "no duration
     * to show".
     */
    suspend fun testAlarm(context: Context) = mutex.withLock {
        val state = TimerStore.state(context)
        val now = System.currentTimeMillis()
        // A deadline already behind the clock with no ring is not a live timer
        // (the screens show it as idle), so it doesn't block the test.
        if (state.isRunning(now) || state.isPaused || state.isRinging) return@withLock
        if (state.endTimeMillis > 0) TimerStore.clearTimer(context) // drop the stale total
        ringLocked(context, now)
    }

    // Lint expects SCHEDULE_EXACT_ALARM here. Ducky holds USE_EXACT_ALARM instead (the manifest),
    // the exact-alarm permission for timer and alarm apps, which the user can't revoke.
    @SuppressLint("MissingPermission")
    private fun scheduleAlarm(context: Context, endTimeMillis: Long) {
        // setAlarmClock: exact, Doze-proof, and surfaces the lock-screen alarm
        // affordance. USE_EXACT_ALARM makes this non-revocable for a timer app.
        val showApp = PendingIntent.getActivity(
            context, RequestCodes.ALARM_SHOW_APP,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager(context).setAlarmClock(
            AlarmManager.AlarmClockInfo(endTimeMillis, showApp),
            firePendingIntent(context)
        )
    }

    private fun firePendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context, RequestCodes.FIRE, fireIntent(context),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    /** The FIRE intent. Arming the alarm and checking whether it is armed must build the same one. */
    private fun fireIntent(context: Context): Intent =
        Intent(context, TimerReceiver::class.java).setAction(ACTION_FIRE)


    /** Posts the countdown card and notes when, so the display tick doesn't re-post it too often. */
    private suspend fun postCountdownNotification(context: Context, endTimeMillis: Long, totalMillis: Long) {
        TimerNotifications.postCountdown(context, endTimeMillis, totalMillis)
        lastCountdownPostMs = System.currentTimeMillis()
    }

    private fun alarmManager(context: Context) =
        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
}
