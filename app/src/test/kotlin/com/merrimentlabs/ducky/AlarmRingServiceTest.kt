package com.merrimentlabs.ducky

import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Looper
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowMediaPlayer
import org.robolectric.shadows.ShadowPowerManager
import org.robolectric.shadows.ShadowMediaPlayer.MediaInfo
import org.robolectric.shadows.util.DataSource

/**
 * The ring service's sound fallback chain. ShadowMediaPlayer only knows the
 * data sources registered via addMediaInfo; any other URI throws from
 * setDataSource, which is exactly the "saved ringtone is unreadable" case.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AlarmRingServiceTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val bogus: Uri = Uri.parse("content://media/external/audio/media/999999")
    private val defaultAlarm: Uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
    private val defaultRingtone: Uri = Settings.System.DEFAULT_RINGTONE_URI
    private var controller: ServiceController<AlarmRingService>? = null

    @Before
    fun setUp() {
        ShadowMediaPlayer.resetStaticState()
        DataSource.reset()
        ShadowMediaPlayer.addMediaInfo(DataSource.toDataSource(context, defaultAlarm), MediaInfo(1_000, 0))
        ShadowMediaPlayer.addMediaInfo(DataSource.toDataSource(context, defaultRingtone), MediaInfo(1_000, 0))
        runBlocking {
            // A ring in progress: the service reads persisted state on start
            // (the null-intent START_STICKY path) and stops itself otherwise.
            TimerStore.setTimer(context, System.currentTimeMillis() - 1L, 60_000L)
            TimerStore.setRinging(context, System.currentTimeMillis())
            TimerStore.setSoundUri(context, bogus.toString())
        }
    }

    @After
    fun tearDown() {
        controller?.destroy()
        runBlocking {
            TimerStore.setSoundUri(context, null)
            TimerStore.clearTimer(context)
        }
    }

    /** Start the service and pump the main looper until the ring coroutine has run. */
    private fun startRinging(): AlarmRingService {
        val c = controller ?: Robolectric.buildService(AlarmRingService::class.java).create()
            .also { controller = it }
        c.startCommand(0, 1)
        val service = c.get()
        repeat(200) {
            shadowOf(Looper.getMainLooper()).idle()
            if (service.player != null) return service
            Thread.sleep(10)
        }
        return service
    }

    @Test
    fun `unreadable saved ringtone falls back to the system alarm sound`() {
        val service = startRinging()
        val player = service.player
        assertNotNull("fallback player must exist", player)
        assertEquals(defaultAlarm, shadowOf(player!!).sourceUri)
    }

    @Test
    fun `a wake lock covers the ring from its start until the sound plays`() {
        // Preparation never completes on its own here, so "still preparing" is observable.
        ShadowMediaPlayer.addMediaInfo(DataSource.toDataSource(context, defaultAlarm), MediaInfo(1_000, -1))
        AlarmRingService.start(context, session = 1L)
        val lock = ShadowPowerManager.getLatestWakeLock()
        assertNotNull("ring start must hold a wake lock", lock)
        assertTrue(lock!!.isHeld)

        val service = startRinging()
        assertTrue("still preparing: the lock stays", lock.isHeld)
        shadowOf(service.player!!).invokePreparedListener()
        assertTrue(service.player!!.isPlaying)
        assertTrue("playing: the lock is released", !lock.isHeld)
    }

    @Test
    fun `the ring pauses other audio while it plays and gives it back on Stop`() {
        val am = shadowOf(context.getSystemService(android.media.AudioManager::class.java))
        startRinging()
        val request = am.lastAudioFocusRequest
        assertNotNull("the ring asks for audio focus, so music pauses (as the Clock app does)", request)
        assertEquals(android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT, request!!.durationHint)
        assertEquals(android.media.AudioAttributes.USAGE_ALARM, request.audioFocusRequest.audioAttributes.usage)

        controller!!.destroy()
        controller = null
        assertNotNull("Stop gives the focus back, so the music resumes", am.lastAbandonedAudioFocusRequest)
        assertSame(request.audioFocusRequest, am.lastAbandonedAudioFocusRequest)
    }

    @Test
    fun `a repost puts the ring card back without restarting the sound`() {
        val service = startRinging()
        val player = service.player!!
        val nm = context.getSystemService(android.app.NotificationManager::class.java)
        // The card was dropped (notifications were off when it was posted).
        nm.cancel(TimerController.ALARM_NOTIFICATION_ID)
        assertNull(shadowOf(nm).getNotification(TimerController.ALARM_NOTIFICATION_ID))

        service.onStartCommand(
            Intent(context, AlarmRingService::class.java).setAction(AlarmRingService.ACTION_REPOST), 0, 2
        )
        repeat(200) {
            shadowOf(Looper.getMainLooper()).idle()
            if (shadowOf(nm).getNotification(TimerController.ALARM_NOTIFICATION_ID) != null) return@repeat
            Thread.sleep(10)
        }
        val card = shadowOf(nm).getNotification(TimerController.ALARM_NOTIFICATION_ID)
        assertNotNull("the ring card is back", card)
        assertEquals(listOf("+1 min", "Stop"), card!!.actions.map { it.title.toString() })
        assertSame("the sound was not restarted", player, service.player)
    }

    @Test
    fun `a sound that never finishes preparing hands over to the next one`() {
        // The system alarm sound hangs in preparation: no prepared, no error callback.
        ShadowMediaPlayer.addMediaInfo(DataSource.toDataSource(context, defaultAlarm), MediaInfo(1_000, -1))
        val service = startRinging()
        val hung = service.player!!
        assertEquals(defaultAlarm, shadowOf(hung).sourceUri)

        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(2))
        assertSame("still within the timeout", hung, service.player)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(2))
        val next = service.player!!
        assertEquals(defaultRingtone, shadowOf(next).sourceUri)
        assertEquals(ShadowMediaPlayer.State.END, shadowOf(hung).state)

        // The ringtone prepares; nothing times it out afterwards.
        shadowOf(next).invokePreparedListener()
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(10))
        assertSame(next, service.player)
        assertTrue(next.isPlaying)
    }

    @Test
    fun `the start wake lock is released when no sound can play`() {
        AlarmRingService.start(context, session = 1L)
        val lock = ShadowPowerManager.getLatestWakeLock()!!
        val service = startRinging()
        shadowOf(service.player!!).invokeErrorListener(1, 0)   // alarm -> ringtone
        shadowOf(service.player!!).invokeErrorListener(1, 0)   // ringtone -> none
        assertTrue(!lock.isHeld)
    }

    @Test
    fun `an async player error steps down the chain and releases the failed player`() {
        val service = startRinging()
        val first = service.player!!
        shadowOf(first).invokeErrorListener(1, 0)

        val second = service.player
        assertNotNull(second)
        assertNotSame(first, second)
        assertEquals(defaultRingtone, shadowOf(second!!).sourceUri)
        assertEquals(ShadowMediaPlayer.State.END, shadowOf(first).state)
    }

    /** Like [startRinging], with a player factory installed before the ring starts. */
    private fun startRingingWith(factory: () -> android.media.MediaPlayer): AlarmRingService {
        val c = Robolectric.buildService(AlarmRingService::class.java).create().also { controller = it }
        c.get().playerFactory = factory
        return startRinging()
    }

    // Candidates in order: the saved (unreadable) URI, the default alarm, the default ringtone.
    // Player 1 is the saved URI's, which fails in setDataSource anyway; player 2 is the alarm's.

    @Test
    fun `a player that fails to start steps down the chain instead of crashing`() {
        var made = 0
        val service = startRingingWith {
            made++
            if (made == 2) object : android.media.MediaPlayer() {
                override fun start(): Unit = throw IllegalStateException("start failed")
            } else android.media.MediaPlayer()
        }
        repeat(200) {
            shadowOf(Looper.getMainLooper()).idle()
            if (made >= 3) return@repeat
            Thread.sleep(10)
        }
        assertEquals(3, made)
        assertEquals(defaultRingtone, shadowOf(service.player!!).sourceUri)
    }

    @Test
    fun `a player that fails to construct steps down the chain instead of crashing`() {
        var made = 0
        val service = startRingingWith {
            made++
            if (made == 2) throw RuntimeException("no player") else android.media.MediaPlayer()
        }
        assertEquals(3, made)
        assertEquals(defaultRingtone, shadowOf(service.player!!).sourceUri)
    }

    @Test
    fun `exhausting the chain leaves no player but does not crash`() {
        val service = startRinging()
        shadowOf(service.player!!).invokeErrorListener(1, 0)   // alarm -> ringtone
        shadowOf(service.player!!).invokeErrorListener(1, 0)   // ringtone -> none
        assertNull(service.player)
    }

    @Test
    fun `a repeated start releases the previous player`() {
        val service = startRinging()
        val first = service.player!!
        controller!!.startCommand(0, 2)
        repeat(200) {
            shadowOf(Looper.getMainLooper()).idle()
            if (service.player !== first) return@repeat
            Thread.sleep(10)
        }
        val second = service.player
        assertNotNull(second)
        assertNotSame(first, second)
        assertEquals(ShadowMediaPlayer.State.END, shadowOf(first).state)
    }

    @Test
    fun `destroy is safe while the player is still preparing`() {
        val service = startRinging()
        val player = service.player!!
        // Never let it reach PREPARED: destroy must cope with a mid-prepare player.
        controller!!.destroy()
        controller = null
        assertNull(service.player)
        assertEquals(ShadowMediaPlayer.State.END, shadowOf(player).state)
    }

    @Test
    fun `a sticky restart with no ring in progress stops itself without ringing`() {
        // The OS restarted us after the ring was already stopped (or the state
        // was cleared while we were dead): nothing to ring, leave quietly.
        runBlocking { TimerStore.clearTimer(context) }
        val service = startRinging()
        repeat(200) {
            shadowOf(Looper.getMainLooper()).idle()
            if (shadowOf(service).isStoppedBySelf) return@repeat
            Thread.sleep(10)
        }
        assertNull(service.player)
        assertTrue(shadowOf(service).isStoppedBySelf)
    }

    @Test
    fun `the ring service is started, never bound`() {
        val service = Robolectric.buildService(AlarmRingService::class.java).create()
            .also { controller = it }.get()
        assertNull(service.onBind(Intent(context, AlarmRingService::class.java)))
    }
}
