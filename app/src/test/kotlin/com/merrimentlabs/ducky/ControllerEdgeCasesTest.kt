package com.merrimentlabs.ducky

import android.app.AlarmManager
import android.app.Application
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/**
 * TimerController paths the main suite does not reach: the request* entry points (which
 * outlive their caller), settings writes, and what happens when storage refuses a write or
 * the ring service cannot start.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ControllerEdgeCasesTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val application = context as Application
    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val realWidgetRefresher = TimerController.widgetRefresher
    @Volatile private var renders = 0
    private var readOnlyDir: File? = null
    private val controllerMutex =
        TimerController::class.java.getDeclaredField("mutex").apply { isAccessible = true }
            .get(null) as kotlinx.coroutines.sync.Mutex

    @Before
    fun reset() {
        TimerController.widgetRefresher = { renders++ }
        runBlocking {
            TimerStore.clearTimer(context)
            TimerStore.setVibrate(context, true)
        }
    }

    @After
    fun restore() {
        readOnlyDir?.setWritable(true)
        // A request* command may still be running under the controller's lock: let it finish.
        runBlocking { controllerMutex.lock(); controllerMutex.unlock() }
        TimerController.resetWidgetDelivery()
        TimerController.widgetRefresher = realWidgetRefresher
        runBlocking {
            TimerStore.clearTimer(context)
            TimerStore.setVibrate(context, true)
        }
    }

    private fun state() = runBlocking { TimerStore.state(context) }

    private fun fireAlarms() =
        shadowOf(alarmManager).scheduledAlarms.filter { it.getType() == AlarmManager.RTC_WAKEUP }

    private fun ringServiceStarted(): Boolean =
        shadowOf(application).nextStartedService?.component?.className == AlarmRingService::class.java.name

    /** Like [ringServiceStarted], but leaves the start in the queue, so it is safe to poll. */
    private fun ringServiceStartRequested(): Boolean =
        shadowOf(application).peekNextStartedService()?.component?.className == AlarmRingService::class.java.name

    private fun ringServiceStopped(): Boolean =
        shadowOf(application).nextStoppedService?.component?.className == AlarmRingService::class.java.name

    /** Waits (real time, main looper idled) for [done]; the request* work runs on Default. */
    private fun awaitTrue(done: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!done()) {
            if (System.currentTimeMillis() > deadline) fail("condition not reached in 5 s")
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
    }

    private fun ringNow(): Long = runBlocking {
        TimerStore.setTimer(context, System.currentTimeMillis() - 1L, 60_000L)
        TimerController.onAlarmFired(context)
        notificationManager.notify(
            TimerController.ALARM_NOTIFICATION_ID,
            NotificationCompat.Builder(context, TimerController.ALARM_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification_timer).build()
        )
        assertTrue(ringServiceStarted())
        TimerStore.state(context).ringingSinceMillis
    }

    /**
     * The file behind the process-wide timer DataStore. It was created from the first test's
     * files dir in this JVM, not this test's, so it is read out of the store itself.
     */
    private fun dataStoreFile(): File {
        fun find(o: Any?, depth: Int): File? {
            if (o == null || depth > 5) return null
            if (o is File) return o
            if (o is Lazy<*>) return if (o.isInitialized()) find(o.value, depth + 1) else null
            if (!o.javaClass.name.startsWith("androidx.datastore")) return null
            var c: Class<*>? = o.javaClass
            while (c != null && c != Any::class.java) {
                for (f in c.declaredFields) {
                    if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                    val v = runCatching { f.isAccessible = true; f.get(o) }.getOrNull()
                    find(v, depth + 1)?.let { return it }
                }
                c = c.superclass
            }
            return null
        }
        return requireNotNull(find(context.timerDataStore, 0)) { "DataStore file not found" }
    }

    /** Storage full, as far as DataStore can tell: its directory refuses the scratch file. */
    private fun failWrites() {
        state() // the store is open and its state cached before writes start failing
        val dir = dataStoreFile().parentFile!!
        assertTrue(dir.setWritable(false))
        readOnlyDir = dir
    }

    private fun allowWrites() {
        readOnlyDir?.setWritable(true)
        readOnlyDir = null
    }

    // ---- request* entry points ----

    @Test
    fun `requestTestAlarm rings from idle with no duration`() {
        TimerController.requestTestAlarm(context)
        awaitTrue { state().isRinging }
        assertEquals(0L, state().totalMillis)
        // The state is saved before the service is started, on the request's own thread, so the
        // ringing state can be visible here a moment before the start call is made.
        awaitTrue { ringServiceStartRequested() }
    }

    @Test
    fun `requestStopRing acts only on its own session`() {
        val session = ringNow()
        TimerController.requestStopRing(context, session + 1L)
        TimerController.requestStopRing(context, session)
        awaitTrue { !state().isRinging }
        // The state is saved before the service is stopped, on the command's own thread.
        awaitTrue { ringServiceStopped() }
        assertNull(shadowOf(notificationManager).getNotification(TimerController.ALARM_NOTIFICATION_ID))
    }

    @Test
    fun `requestExtendRing turns the ring into a fresh minute`() {
        val session = ringNow()
        TimerController.requestExtendRing(context, session)
        awaitTrue { fireAlarms().isNotEmpty() }
        val s = state()
        assertFalse(s.isRinging)
        assertEquals(TimerController.EXTEND_MILLIS, s.totalMillis)
        assertEquals(s.endTimeMillis, fireAlarms().single().triggerAtMs)
    }

    @Test
    fun `requestSettingsWrite lands the write and re-renders widgets only when asked`() {
        TimerController.requestSettingsWrite(context) { TimerStore.setVibrate(it, false) }
        awaitTrue { runBlocking { !TimerStore.settings(context).vibrate } }
        Thread.sleep(100)
        assertEquals(0, renders)

        TimerController.requestSettingsWrite(context, refreshWidgets = true) {
            TimerStore.setVibrate(it, true)
        }
        awaitTrue { renders > 0 }
        assertTrue(runBlocking { TimerStore.settings(context).vibrate })
    }

    // ---- Storage refuses writes ----

    @Test
    fun `stop silences the ring even when storage refuses the write`() {
        val session = ringNow()
        failWrites()
        runBlocking { TimerController.stopRinging(context, session) }

        assertTrue(ringServiceStopped())
        assertNull(shadowOf(notificationManager).getNotification(TimerController.ALARM_NOTIFICATION_ID))
        // The persisted ring stays, so Stop on the timer screen can try again.
        assertTrue(state().isRinging)

        allowWrites()
        runBlocking { TimerController.stopRinging(context, session) }
        assertFalse(state().isRinging)
    }

    @Test
    fun `cancel retires the alarm and the ring even when storage refuses the write`() {
        val session = ringNow()
        failWrites()
        runBlocking { TimerController.cancel(context) }

        assertTrue(ringServiceStopped())
        assertTrue(fireAlarms().isEmpty())
        assertEquals(session, state().ringingSinceMillis)
    }

    @Test
    fun `+1 min that cannot be written leaves the ring playing, and its Stop working`() {
        val session = ringNow()
        failWrites()
        runBlocking { TimerController.extendRing(context, session) }
        assertFalse("the ring was not silenced", ringServiceStopped())
        assertEquals(session, state().ringingSinceMillis)
        assertTrue(fireAlarms().isEmpty())

        // Storage back: the same +1 min goes through, once.
        allowWrites()
        runBlocking { TimerController.extendRing(context, session) }
        assertTrue(ringServiceStopped())
        val s = state()
        assertFalse(s.isRinging)
        assertEquals(TimerController.EXTEND_MILLIS, s.totalMillis)
    }

    @Test
    fun `a FIRE whose ring cannot be written arms a retry instead of going silent`() {
        runBlocking { TimerStore.setTimer(context, System.currentTimeMillis() - 1L, 60_000L) }
        failWrites()
        val before = System.currentTimeMillis()
        runBlocking { TimerController.onAlarmFired(context) }
        val after = System.currentTimeMillis()

        assertFalse(state().isRinging)
        assertNull(shadowOf(application).nextStartedService)
        val retry = fireAlarms().single().triggerAtMs
        assertTrue(retry in before + 5_000L..after + 5_000L)

        // The retry, once storage recovers, rings.
        allowWrites()
        runBlocking { TimerController.onAlarmFired(context) }
        assertTrue(state().isRinging)
    }

    @Test
    fun `a boot ring that cannot be written arms a retry instead of going silent`() =
        recoveryRingRetries { TimerController.rescheduleAfterBoot(context) }

    @Test
    fun `an update's ring that cannot be written arms a retry instead of going silent`() =
        recoveryRingRetries { TimerController.onPackageReplaced(context) }

    /** A deadline that passed while the phone was off (or updating), found with storage full. */
    private fun recoveryRingRetries(recover: suspend () -> Unit) {
        runBlocking { TimerStore.setTimer(context, System.currentTimeMillis() - 60_000L, 120_000L) }
        failWrites()
        val before = System.currentTimeMillis()
        runBlocking { recover() }
        val after = System.currentTimeMillis()

        assertFalse(state().isRinging)
        val retry = fireAlarms().single().triggerAtMs
        assertTrue(retry in before + 5_000L..after + 5_000L)

        allowWrites()
        runBlocking { TimerController.onAlarmFired(context) }
        assertTrue(state().isRinging)
        assertTrue(ringServiceStarted())
    }

    @Test
    fun `a cancellation from the ring start propagates and is not retried`() {
        runBlocking { TimerStore.setTimer(context, System.currentTimeMillis() - 1L, 60_000L) }
        val canceling = object : ContextWrapper(context) {
            override fun startForegroundService(service: Intent?): ComponentName? =
                throw CancellationException("caller went away")
        }
        val thrown = runCatching { runBlocking { TimerController.onAlarmFired(canceling) } }
            .exceptionOrNull()
        assertTrue(thrown is CancellationException)
        assertTrue(fireAlarms().isEmpty()) // no retry armed for a cancellation
    }

    @Test
    fun `a ring service that refuses to start arms a retry`() {
        runBlocking { TimerStore.setTimer(context, System.currentTimeMillis() - 1L, 60_000L) }
        val refusing = object : ContextWrapper(context) {
            override fun startForegroundService(service: Intent?): ComponentName? =
                throw IllegalStateException("not allowed to start a foreground service")
        }
        val before = System.currentTimeMillis()
        runBlocking { TimerController.onAlarmFired(refusing) }
        assertNotNull(fireAlarms().singleOrNull { it.triggerAtMs >= before + 5_000L })
    }

    @Test
    fun `the retry after a refused ring service start rings the same session`() {
        runBlocking { TimerStore.setTimer(context, System.currentTimeMillis() - 1L, 60_000L) }
        val refusing = object : ContextWrapper(context) {
            override fun startForegroundService(service: Intent?): ComponentName? =
                throw IllegalStateException("not allowed to start a foreground service")
        }
        runBlocking { TimerController.onAlarmFired(refusing) }
        // The ring was saved before the start threw: every surface already offers its Stop.
        val session = state().ringingSinceMillis
        assertTrue(session > 0L)
        assertTrue("a retry is armed", fireAlarms().isNotEmpty())
        // The retry alarm fires (a plain context now, the service can start).
        val rendersBefore = renders
        runBlocking { TimerController.onAlarmFired(context) }
        awaitTrue { renders > rendersBefore } // widgets show the ring, with its Stop and +1 min
        val started = shadowOf(application).nextStartedService
        assertEquals(AlarmRingService::class.java.name, started?.component?.className)
        assertEquals("the Stop already shown still reaches it",
            session, started!!.getLongExtra(TimerController.EXTRA_RING_SESSION, 0L))
        assertEquals(session, state().ringingSinceMillis)
    }

    @Test
    fun `a FIRE during a ring that is playing changes nothing`() {
        val session = ringNow()
        AlarmRingService.running = true
        try {
            runBlocking { TimerController.onAlarmFired(context) }
        } finally {
            AlarmRingService.running = false
        }
        assertEquals(null, shadowOf(application).nextStartedService)
        assertEquals(session, state().ringingSinceMillis)
    }

    @Test
    fun `a saved ring older than the stale window is not restarted by a FIRE`() {
        runBlocking {
            TimerStore.setRinging(context, System.currentTimeMillis() - TimerController.STALE_RING_MS - 1_000L)
            TimerController.onAlarmFired(context)
        }
        assertEquals(null, shadowOf(application).nextStartedService)
    }

    // Android 14 after a force-stop (measured on an API 34 emulator): the alarm is gone,
    // the FIRE PendingIntent is not, and the relaunch gets no BOOT_COMPLETED. A passed deadline
    // then sat behind an idle-looking screen until a clock change rang it by surprise.

    @Test
    fun `opening the app rings a deadline its lost alarm never rang, as a relaunch on 15+ does`() {
        runBlocking {
            TimerStore.setTimer(context, System.currentTimeMillis() - 9 * 60_000L, 10 * 60_000L)
            TimerController.rearm(context)
        }
        assertTrue(state().isRinging)
        assertTrue(ringServiceStarted())
        assertEquals("the total survives for the alarm screen", 10 * 60_000L, state().totalMillis)
    }

    @Test
    fun `opening the app clears a lost deadline older than the stale window`() {
        runBlocking {
            TimerStore.setTimer(context, System.currentTimeMillis() - TimerController.STALE_RING_MS - 60_000L, 60_000L)
            TimerController.rearm(context)
        }
        val s = state()
        assertFalse(s.isRinging)
        assertEquals(0L, s.endTimeMillis)
        assertEquals(0L, s.totalMillis)
        assertNull(shadowOf(application).nextStartedService)
    }

    @Test
    fun `opening the app leaves a deadline that just passed to its FIRE`() {
        val end = System.currentTimeMillis() - 1_000L
        runBlocking { TimerStore.setTimer(context, end, 60_000L); TimerController.rearm(context) }
        assertFalse(state().isRinging)
        assertEquals(end, state().endTimeMillis)
    }

    @Test
    fun `re-arming leaves idle, paused and passed timers without an alarm`() {
        runBlocking { TimerController.rearm(context) }
        runBlocking { TimerStore.setPaused(context, 30_000L); TimerController.rearm(context) }
        runBlocking {
            TimerStore.setTimer(context, System.currentTimeMillis() - 1_000L, 60_000L)
            TimerController.rearm(context)
        }
        assertTrue(fireAlarms().isEmpty())
    }

    @Test
    fun `onTimeChanged with nothing set requests no render`() {
        runBlocking { TimerController.onTimeChanged(context) }
        Thread.sleep(100)
        assertEquals(0, renders)
        assertTrue(fireAlarms().isEmpty())
    }
}
