package com.merrimentlabs.ducky

import android.app.AlarmManager
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import com.merrimentlabs.ducky.ui.PRESET_SEEDS
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowBroadcastPendingResult
import java.util.concurrent.TimeUnit

/**
 * Every broadcast TimerReceiver handles, delivered the way the system delivers it: with a
 * pending result, so goAsync() works and the test waits for the receiver's own finish().
 * (Robolectric's sendBroadcast does not route an explicit intent whose action is not in the
 * manifest filter, which is every action but the three system ones.)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TimerReceiverTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val application = context as Application
    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val realWidgetRefresher = TimerController.widgetRefresher

    @Before
    fun reset() {
        TimerController.widgetRefresher = {}
        runBlocking {
            TimerStore.clearTimer(context)
            TimerStore.setSeedColor(context, TimerStore.DEFAULT_SEED)
        }
    }

    @After
    fun restore() {
        TimerController.resetWidgetDelivery()
        TimerController.widgetRefresher = realWidgetRefresher
        runBlocking {
            TimerStore.clearTimer(context)
            TimerStore.touch(context, 0L) // the heartbeat survives clearTimer
            TimerStore.setSeedColor(context, TimerStore.DEFAULT_SEED)
        }
    }

    /** Delivers [intent] to a fresh receiver with a pending result and waits for finish(). */
    private fun deliver(intent: Intent) {
        val create = ShadowBroadcastPendingResult::class.java.getDeclaredMethod(
            "create", Int::class.javaPrimitiveType, String::class.java, Bundle::class.java,
            Boolean::class.javaPrimitiveType,
        ).apply { isAccessible = true }
        val pending = create.invoke(null, 0, null, null, false) as BroadcastReceiver.PendingResult
        val receiver = TimerReceiver()
        BroadcastReceiver::class.java
            .getMethod("setPendingResult", BroadcastReceiver.PendingResult::class.java)
            .invoke(receiver, pending)
        receiver.onReceive(context, intent)
        // finish() runs in the receiver's finally: completing proves it on every path.
        Shadow.extract<ShadowBroadcastPendingResult>(pending).future.get(5, TimeUnit.SECONDS)
    }

    private fun action(name: String) = Intent(context, TimerReceiver::class.java).setAction(name)

    private fun ring(name: String, session: Long?) = action(name).apply {
        if (session != null) putExtra(TimerController.EXTRA_RING_SESSION, session)
    }

    private fun state() = runBlocking { TimerStore.state(context) }

    private fun fireAlarms() =
        shadowOf(alarmManager).scheduledAlarms.filter { it.getType() == AlarmManager.RTC_WAKEUP }

    private fun tickAlarms() =
        shadowOf(alarmManager).scheduledAlarms.filter { it.getType() == AlarmManager.RTC }

    private fun ringServiceStarted(): Boolean =
        shadowOf(application).nextStartedService?.component?.className == AlarmRingService::class.java.name

    private fun ringServiceStopped(): Boolean =
        shadowOf(application).nextStoppedService?.component?.className == AlarmRingService::class.java.name

    private fun countdownCard(): Notification? = shadowOf(notificationManager).getNotification(1)

    /** A ringing timer, as a due FIRE leaves it. Returns its ring session. */
    private fun ringNow(total: Long = 60_000L): Long {
        runBlocking { TimerStore.setTimer(context, System.currentTimeMillis() - 1L, total) }
        deliver(action(TimerController.ACTION_FIRE))
        assertTrue(ringServiceStarted())
        return state().ringingSinceMillis.also { assertTrue(it > 0L) }
    }

    @Test
    fun `FIRE at the deadline rings and keeps the total`() {
        ringNow(total = 90_000L)
        val s = state()
        assertTrue(s.isRinging)
        assertEquals(0L, s.endTimeMillis)
        assertEquals(90_000L, s.totalMillis)
    }

    @Test
    fun `FIRE for a deadline still in the future does not ring`() {
        val end = System.currentTimeMillis() + 120_000L
        runBlocking { TimerStore.setTimer(context, end, 120_000L) }
        deliver(action(TimerController.ACTION_FIRE))
        assertFalse(state().isRinging)
        assertNull(shadowOf(application).nextStartedService)
        assertEquals(end, fireAlarms().single().triggerAtMs)
    }

    @Test
    fun `CANCEL clears a running timer and its alarms`() {
        runBlocking { TimerController.addTime(context, 60_000L) }
        assertEquals(1, fireAlarms().size)
        deliver(action(TimerController.ACTION_CANCEL))
        assertEquals(0L, state().totalMillis)
        assertEquals(0L, state().endTimeMillis)
        assertTrue(fireAlarms().isEmpty())
        assertNull(countdownCard())
    }

    @Test
    fun `PAUSE freezes the timer and RESUME re-arms it`() {
        runBlocking { TimerController.addTime(context, 60_000L) }
        deliver(action(TimerController.ACTION_PAUSE))
        assertTrue(state().isPaused)
        assertTrue(fireAlarms().isEmpty())
        assertEquals(
            context.getString(R.string.action_resume),
            countdownCard()!!.actions.first().title.toString(),
        )

        deliver(action(TimerController.ACTION_RESUME))
        val s = state()
        assertFalse(s.isPaused)
        assertTrue(s.isRunning(System.currentTimeMillis()))
        assertEquals(s.endTimeMillis, fireAlarms().single().triggerAtMs)
        assertEquals(
            context.getString(R.string.action_pause),
            countdownCard()!!.actions.first().title.toString(),
        )
    }

    @Test
    fun `TICK on a running timer re-arms the non-wakeup tick and bumps the display heartbeat`() {
        val end = System.currentTimeMillis() + 900_000L
        runBlocking { TimerStore.setTimer(context, end, 900_000L) }
        // Force a notification repost on this tick (the countdown card is reposted at most every 12 s).
        TimerController::class.java.getDeclaredField("lastCountdownPostMs")
            .apply { isAccessible = true }.setLong(null, 0L)
        val before = System.currentTimeMillis()

        deliver(action(TimerController.ACTION_TICK))

        val tick = tickAlarms().single()
        // total/90 = 10 s, inside the 3..15 s clamp.
        assertTrue(tick.triggerAtMs in before + 10_000L..System.currentTimeMillis() + 10_000L)
        assertTrue(fireAlarms().isEmpty()) // the tick never arms (or wakes) the firing alarm
        assertTrue(state().lastTickMillis >= before)
        assertEquals(end, state().endTimeMillis)
        val card = countdownCard()
        assertNotNull(card)
        assertEquals(end, card!!.`when`)
    }

    @Test
    fun `a second TICK right after the first does not repost the notification`() {
        runBlocking { TimerStore.setTimer(context, System.currentTimeMillis() + 900_000L, 900_000L) }
        TimerController::class.java.getDeclaredField("lastCountdownPostMs")
            .apply { isAccessible = true }.setLong(null, 0L)
        deliver(action(TimerController.ACTION_TICK))
        assertNotNull(countdownCard())
        notificationManager.cancel(1)

        deliver(action(TimerController.ACTION_TICK))
        assertNull(countdownCard())
        assertEquals(1, tickAlarms().size) // the chain itself still continues
    }

    @Test
    fun `TICK with no running timer ends the chain`() {
        val heartbeat = state().lastTickMillis
        deliver(action(TimerController.ACTION_TICK))
        assertTrue(tickAlarms().isEmpty())
        assertEquals(heartbeat, state().lastTickMillis)
        assertNull(countdownCard())
    }

    // ---- Ring session (the ringing contract in docs/ARCHITECTURE.md) ----

    @Test
    fun `a broadcast finishes only after the widget render it asked for`() {
        val session = ringNow()
        // Android may reclaim the process once the receiver finishes: the render that shows the
        // stopped ring has to be with the launcher by then.
        val rendered = java.util.concurrent.atomic.AtomicBoolean(false)
        TimerController.widgetRefresher = { kotlinx.coroutines.delay(400); rendered.set(true) }
        deliver(ring(TimerController.ACTION_STOP_RING, session))
        assertTrue("finish() came after the render", rendered.get())
    }

    @Test
    fun `STOP_RING with the ring's session silences it`() {
        val session = ringNow()
        deliver(ring(TimerController.ACTION_STOP_RING, session))
        assertFalse(state().isRinging)
        assertEquals(0L, state().totalMillis)
        assertTrue(ringServiceStopped())
    }

    @Test
    fun `STOP_RING for another session or with no session leaves the ring alone`() {
        val session = ringNow()
        deliver(ring(TimerController.ACTION_STOP_RING, session - 1L))
        deliver(ring(TimerController.ACTION_STOP_RING, null))
        assertTrue(state().isRinging)
        assertEquals(session, state().ringingSinceMillis)
        assertFalse(ringServiceStopped())
    }

    @Test
    fun `EXTEND_RING with the ring's session starts a fresh minute`() {
        val session = ringNow()
        val before = System.currentTimeMillis()
        deliver(ring(TimerController.ACTION_EXTEND_RING, session))
        val s = state()
        assertFalse(s.isRinging)
        assertEquals(TimerController.EXTEND_MILLIS, s.totalMillis)
        assertTrue(s.endTimeMillis >= before + TimerController.EXTEND_MILLIS)
        assertEquals(s.endTimeMillis, fireAlarms().single().triggerAtMs)
        assertTrue(ringServiceStopped())
    }

    @Test
    fun `EXTEND_RING with no session is dropped`() {
        val session = ringNow()
        deliver(ring(TimerController.ACTION_EXTEND_RING, null))
        assertTrue(state().isRinging)
        assertEquals(session, state().ringingSinceMillis)
        assertTrue(fireAlarms().isEmpty())
    }

    @Test
    fun `the notification's Stop and +1 min intents carry the session the receiver checks`() {
        val session = ringNow()
        val extend = TimerController.ringActionPendingIntent(
            context, TimerController.ACTION_EXTEND_RING, session
        )!!
        deliver(shadowOf(extend).savedIntent)
        assertTrue(state().isRunning(System.currentTimeMillis()))

        // The old card's Stop arrives after +1 min: stale, the new minute keeps running.
        val stop = TimerController.ringActionPendingIntent(
            context, TimerController.ACTION_STOP_RING, session
        )!!
        deliver(shadowOf(stop).savedIntent)
        assertTrue(state().isRunning(System.currentTimeMillis()))
        assertEquals(TimerController.EXTEND_MILLIS, state().totalMillis)
    }

    // ---- BOOT_COMPLETED (stale-state rule: ring a passed deadline only under STALE_RING_MS) ----

    @Test
    fun `BOOT_COMPLETED rings a deadline that passed within the stale window`() {
        val passed = System.currentTimeMillis() - TimerController.STALE_RING_MS + 60_000L
        runBlocking { TimerStore.setTimer(context, passed, 60_000L) }
        deliver(action(Intent.ACTION_BOOT_COMPLETED))
        assertTrue(state().isRinging)
        assertTrue(ringServiceStarted())
    }

    @Test
    fun `BOOT_COMPLETED clears a deadline older than the stale window instead of ringing`() {
        val passed = System.currentTimeMillis() - TimerController.STALE_RING_MS - 60_000L
        runBlocking { TimerStore.setTimer(context, passed, 60_000L) }
        deliver(action(Intent.ACTION_BOOT_COMPLETED))
        assertFalse(state().isRinging)
        assertEquals(0L, state().endTimeMillis)
        assertEquals(0L, state().totalMillis)
        assertNull(shadowOf(application).nextStartedService)
    }

    @Test
    fun `BOOT_COMPLETED silences a ring persisted before the stale window`() {
        runBlocking {
            TimerStore.setTimer(context, System.currentTimeMillis() - 1L, 60_000L)
            TimerStore.setRinging(context, System.currentTimeMillis() - TimerController.STALE_RING_MS - 1_000L)
        }
        deliver(action(Intent.ACTION_BOOT_COMPLETED))
        assertFalse(state().isRinging)
        assertNull(shadowOf(application).nextStartedService)
        assertTrue(ringServiceStopped()) // the ring invariant: cleared means silenced
    }

    @Test
    fun `BOOT_COMPLETED re-arms a running timer and keeps a paused one paused`() {
        val end = System.currentTimeMillis() + 300_000L
        runBlocking { TimerStore.setTimer(context, end, 300_000L) }
        deliver(action(Intent.ACTION_BOOT_COMPLETED))
        assertEquals(end, fireAlarms().single().triggerAtMs)
        assertEquals(1, tickAlarms().size)
        assertEquals(end, countdownCard()!!.`when`)

        runBlocking { TimerController.pause(context) }
        notificationManager.cancel(1)
        deliver(action(Intent.ACTION_BOOT_COMPLETED))
        assertTrue(state().isPaused)
        assertTrue(fireAlarms().isEmpty())
        assertEquals(
            context.getString(R.string.action_resume),
            countdownCard()!!.actions.first().title.toString(),
        )
    }

    // ---- TIME_SET (rings a passed deadline only while the FIRE PendingIntent exists) ----

    @Test
    fun `TIME_SET past an armed deadline rings now`() {
        runBlocking {
            TimerController.addTime(context, 60_000L) // arms the FIRE PendingIntent
            TimerStore.setTimer(context, System.currentTimeMillis() - 5_000L, 60_000L)
        }
        deliver(action(Intent.ACTION_TIME_CHANGED))
        assertTrue(state().isRinging)
        assertTrue(ringServiceStarted())
    }

    @Test
    fun `TIME_SET past a deadline with no FIRE PendingIntent clears it`() {
        runBlocking { TimerStore.setTimer(context, System.currentTimeMillis() - 5_000L, 60_000L) }
        deliver(action(Intent.ACTION_TIME_CHANGED))
        assertFalse(state().isRinging)
        assertEquals(0L, state().endTimeMillis)
        assertNull(shadowOf(application).nextStartedService)
    }

    @Test
    fun `TIME_SET with nothing set does nothing`() {
        deliver(action(Intent.ACTION_TIME_CHANGED))
        assertTrue(fireAlarms().isEmpty())
        assertTrue(tickAlarms().isEmpty())
        assertNull(shadowOf(application).nextStartedService)
    }

    // ---- MY_PACKAGE_REPLACED ----

    @Test
    fun `MY_PACKAGE_REPLACED re-arms the timer and puts the icon back on the saved color`() {
        val red = PRESET_SEEDS[0]
        val end = System.currentTimeMillis() + 120_000L
        runBlocking {
            TimerStore.setSeedColor(context, red)
            TimerStore.setTimer(context, end, 120_000L)
        }
        deliver(action(Intent.ACTION_MY_PACKAGE_REPLACED))

        assertEquals(end, fireAlarms().single().triggerAtMs)
        val pm = context.packageManager
        assertEquals(
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            pm.getComponentEnabledSetting(LauncherIcon.aliasComponent(context.packageName, 0)),
        )
        assertEquals(
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            pm.getComponentEnabledSetting(LauncherIcon.aliasComponent(context.packageName, 7)),
        )
    }

    @Test
    fun `an unknown action is ignored and the broadcast still finishes`() {
        runBlocking { TimerStore.setTimer(context, System.currentTimeMillis() - 1L, 60_000L) }
        deliver(action("org.example.NOT_OURS"))
        assertFalse(state().isRinging)
        assertNull(shadowOf(application).nextStartedService)
    }
}
