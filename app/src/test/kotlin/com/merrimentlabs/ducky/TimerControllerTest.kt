package com.merrimentlabs.ducky

import android.app.AlarmManager
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TimerControllerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val alarmManager =
        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private val application = context as Application
    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val realWidgetRefresher = TimerController.widgetRefresher

    @Before
    fun resetState() {
        // The preferencesDataStore delegate is a process-wide singleton; state
        // survives across test methods even though the app context is fresh.
        runBlocking { TimerStore.clearTimer(context) }
    }

    @After
    fun restoreWidgetSeam() {
        TimerController.resetWidgetDelivery()
        TimerController.widgetRefresher = realWidgetRefresher
    }

    /**
     * A ringing timer with its alarm card posted, as AlarmRingService leaves
     * it. Returns the ring session (ringingSinceMillis) the surfaces carry.
     */
    private suspend fun ringNow(): Long {
        TimerStore.setTimer(context, System.currentTimeMillis() - 1L, 60_000L)
        TimerController.onAlarmFired(context)
        notificationManager.notify(
            TimerController.ALARM_NOTIFICATION_ID,
            NotificationCompat.Builder(context, TimerController.ALARM_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification_timer).build()
        )
        assertTrue(ringServiceStarted())
        val session = TimerStore.state(context).ringingSinceMillis
        assertTrue(session > 0L)
        return session
    }

    private fun ringServiceStopped(): Boolean =
        shadowOf(application).nextStoppedService
            ?.component?.className == AlarmRingService::class.java.name

    private fun alarmCardGone(): Boolean =
        shadowOf(notificationManager).getNotification(TimerController.ALARM_NOTIFICATION_ID) == null

    /** The FIRING alarm (RTC_WAKEUP): filters out the display tick (RTC). */
    private fun fireAlarms() =
        shadowOf(alarmManager).scheduledAlarms.filter { it.getType() == AlarmManager.RTC_WAKEUP }

    @Test
    fun `addTime persists end time and schedules an alarm clock`() = runTest {
        val before = System.currentTimeMillis()
        TimerController.addTime(context, 30_000L)

        val state = TimerStore.state(context)
        assertTrue(state.endTimeMillis >= before + 30_000L)
        assertEquals(30_000L, state.totalMillis)

        val scheduled = fireAlarms().singleOrNull()
        assertNotNull(scheduled)
        assertEquals(state.endTimeMillis, scheduled!!.triggerAtMs)
    }

    @Test
    fun `additive taps extend the same alarm`() = runTest {
        TimerController.addTime(context, 60_000L)
        TimerController.addTime(context, 60_000L)
        TimerController.addTime(context, 30_000L)

        val state = TimerStore.state(context)
        assertEquals(150_000L, state.totalMillis)

        // Still exactly one FIRING alarm, at the accumulated end time.
        val alarms = fireAlarms()
        assertEquals(1, alarms.size)
        assertEquals(state.endTimeMillis, alarms.first().triggerAtMs)
    }

    @Test
    fun `cancel clears state and the alarm`() = runTest {
        TimerController.addTime(context, 60_000L)
        TimerController.cancel(context)

        val state = TimerStore.state(context)
        assertEquals(0L, state.endTimeMillis)
        assertEquals(0L, state.totalMillis)
        assertNull(shadowOf(alarmManager).peekNextScheduledAlarm())
    }

    @Test
    fun `pause freezes remaining and cancels the alarm`() = runTest {
        TimerController.addTime(context, 60_000L)
        TimerController.pause(context)

        val state = TimerStore.state(context)
        assertEquals(0L, state.endTimeMillis)
        assertTrue(state.isPaused)
        assertTrue(state.pausedRemainingMillis in 1..60_000L)
        assertEquals(60_000L, state.totalMillis)
        assertNull(shadowOf(alarmManager).peekNextScheduledAlarm())
    }

    @Test
    fun `resume reschedules the alarm from the frozen remaining`() = runTest {
        TimerController.addTime(context, 60_000L)
        TimerController.pause(context)
        val frozen = TimerStore.state(context).pausedRemainingMillis

        val before = System.currentTimeMillis()
        TimerController.resume(context)

        val state = TimerStore.state(context)
        assertTrue(!state.isPaused)
        assertTrue(state.endTimeMillis >= before + frozen)
        val scheduled = fireAlarms().singleOrNull()
        assertNotNull(scheduled)
        assertEquals(state.endTimeMillis, scheduled!!.triggerAtMs)
    }

    @Test
    fun `addTime while paused resumes with remaining plus increment`() = runTest {
        TimerController.addTime(context, 60_000L)
        TimerController.pause(context)
        val frozen = TimerStore.state(context).pausedRemainingMillis

        val before = System.currentTimeMillis()
        TimerController.addTime(context, 30_000L)

        val state = TimerStore.state(context)
        assertTrue(!state.isPaused)
        assertEquals(90_000L, state.totalMillis)
        assertTrue(state.endTimeMillis >= before + frozen + 30_000L)
        assertNotNull(shadowOf(alarmManager).peekNextScheduledAlarm())
    }

    @Test
    fun `cancel clears a paused timer too`() = runTest {
        TimerController.addTime(context, 60_000L)
        TimerController.pause(context)
        TimerController.cancel(context)

        val state = TimerStore.state(context)
        assertTrue(!state.isPaused)
        assertEquals(0L, state.totalMillis)
    }

    /** The ring service is the observable side effect of a FIRE being honored. */
    private fun ringServiceStarted(): Boolean =
        shadowOf(application).nextStartedService
            ?.component?.className == AlarmRingService::class.java.name

    @Test
    fun `fired alarm moves state to ringing and keeps total for display`() = runTest {
        // Deadline already reached: this FIRE is the genuine one.
        TimerStore.setTimer(context, System.currentTimeMillis() - 1L, 60_000L)
        TimerController.onAlarmFired(context)

        val state = TimerStore.state(context)
        assertEquals(0L, state.endTimeMillis)
        assertEquals(60_000L, state.totalMillis)
        assertTrue(state.isRinging)
        assertTrue(ringServiceStarted())
    }

    @Test
    fun `fire slightly before the deadline still rings (tolerance)`() = runTest {
        // Sub-second early delivery (clock step / dispatch slop) is the real alarm.
        TimerStore.setTimer(context, System.currentTimeMillis() + 500L, 60_000L)
        TimerController.onAlarmFired(context)

        assertTrue(TimerStore.state(context).isRinging)
        assertTrue(ringServiceStarted())
    }

    @Test
    fun `fire after cancel does not ring`() = runTest {
        // The FIRE broadcast was enqueued, then a cancel took the mutex first.
        TimerController.addTime(context, 60_000L)
        TimerController.cancel(context)
        TimerController.onAlarmFired(context)

        val state = TimerStore.state(context)
        assertTrue(!state.isRinging)
        assertEquals(0L, state.endTimeMillis)
        assertEquals(0L, state.totalMillis)
        assertTrue(!ringServiceStarted())
    }

    @Test
    fun `cancel after fire silences the ring instead of stranding it`() = runTest {
        // The opposite ordering of the test above: FIRE took the mutex first
        // and the ring service is up; the queued Cancel must end the ring,
        // not just zero the state (which left the alarm playing with every
        // Stop / +1 min rejected by the session guard).
        ringNow()
        TimerController.cancel(context)

        val state = TimerStore.state(context)
        assertTrue(!state.isRinging)
        assertEquals(0L, state.endTimeMillis)
        assertEquals(0L, state.totalMillis)
        assertTrue(ringServiceStopped())
        assertTrue(alarmCardGone())
        assertTrue(fireAlarms().isEmpty())
    }

    @Test
    fun `cancel of a running timer does not touch the ring service`() = runTest {
        TimerController.addTime(context, 60_000L)
        TimerController.cancel(context)

        assertTrue(!ringServiceStopped())
        assertEquals(0L, TimerStore.state(context).totalMillis)
    }

    @Test
    fun `a stale fire arms the newer deadline that was persisted but never scheduled`() = runTest {
        // The process died between persisting an extension and scheduling it: the old
        // FIRE arrives and finds a future deadline with no alarm behind it.
        val end = System.currentTimeMillis() + 90_000L
        TimerStore.setTimer(context, end, 120_000L)
        assertTrue(fireAlarms().isEmpty())

        TimerController.onAlarmFired(context)

        assertTrue(!TimerStore.state(context).isRinging)
        assertEquals(end, fireAlarms().single().triggerAtMs)
    }

    @Test
    fun `fire after extension (deadline in future) does not ring`() = runTest {
        // Deadline reached and the FIRE enqueued, but a + tap landed first and
        // pushed the deadline out. The stale FIRE must not ring the new timer.
        TimerStore.setTimer(context, System.currentTimeMillis() - 1L, 30_000L)
        TimerController.addTime(context, 60_000L)
        val extended = TimerStore.state(context)
        TimerController.onAlarmFired(context)

        val state = TimerStore.state(context)
        assertTrue(!state.isRinging)
        assertEquals(extended.endTimeMillis, state.endTimeMillis)
        assertTrue(state.isRunning(System.currentTimeMillis()))
        assertEquals(1, fireAlarms().size)
        assertTrue(!ringServiceStarted())
    }

    @Test
    fun `an update clears alarms aimed at the old build's components and re-arms the timer`() = runTest {
        // An older build left its alarm clock aimed at a receiver class that no longer exists.
        val end = System.currentTimeMillis() + 120_000L
        TimerStore.setTimer(context, end, 180_000L)
        val oldFire = android.app.PendingIntent.getBroadcast(
            context, 0,
            android.content.Intent(TimerController.ACTION_FIRE)
                .setComponent(android.content.ComponentName(context.packageName, "org.example.old.TimerReceiver")),
            android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        alarmManager.setAlarmClock(AlarmManager.AlarmClockInfo(end, null), oldFire)
        assertEquals(1, fireAlarms().size)

        TimerController.onPackageReplaced(context)

        val fire = fireAlarms().single()
        assertEquals(end, fire.triggerAtMs)
        // ScheduledAlarm has no accessor for its PendingIntent (Robolectric deprecates the field
        // and offers none); the field is the only way to see which receiver the alarm targets.
        @Suppress("DEPRECATION")
        val operation = fire.operation
        assertEquals(TimerReceiver::class.java.name, shadowOf(operation).savedIntent.component?.className)
        assertTrue(TimerStore.state(context).isRunning(System.currentTimeMillis()))
    }

    @Test
    fun `boot recovery rings a timer that expired while powered off`() = runTest {
        TimerStore.setTimer(context, System.currentTimeMillis() - 5_000L, 60_000L)
        TimerController.rescheduleAfterBoot(context)

        val state = TimerStore.state(context)
        assertTrue(state.isRinging)
        assertEquals(0L, state.endTimeMillis)
        assertEquals(60_000L, state.totalMillis)
        assertTrue(ringServiceStarted())
    }

    @Test
    fun `boot recovery resumes ringing after a mid-ring reboot`() = runTest {
        // END_TIME=0 / RINGING_SINCE>0: the state the FIRE guard rejects, which
        // the boot path must still ring.
        TimerStore.setTimer(context, System.currentTimeMillis() - 1L, 60_000L)
        TimerStore.setRinging(context, System.currentTimeMillis() - 5_000L)
        TimerController.rescheduleAfterBoot(context)

        val state = TimerStore.state(context)
        assertTrue(state.isRinging)
        assertEquals(60_000L, state.totalMillis)
        assertTrue(ringServiceStarted())
    }

    @Test
    fun `boot clears a deadline that passed long ago instead of ringing it`() = runTest {
        // Force-stopped with a timer running, or restored from a backup: nothing rang it.
        TimerStore.setTimer(
            context, System.currentTimeMillis() - TimerController.STALE_RING_MS - 60_000L, 60_000L
        )
        TimerController.rescheduleAfterBoot(context)

        val state = TimerStore.state(context)
        assertTrue(!state.isRinging)
        assertEquals(0L, state.endTimeMillis)
        assertEquals(0L, state.totalMillis)
        assertNull(shadowOf(application).nextStartedService)
    }

    @Test
    fun `boot clears a ring persisted long ago instead of ringing again`() = runTest {
        TimerStore.setTimer(context, System.currentTimeMillis() - 1L, 60_000L)
        TimerStore.setRinging(context, System.currentTimeMillis() - 3 * 86_400_000L)
        TimerController.rescheduleAfterBoot(context)

        val state = TimerStore.state(context)
        assertTrue(!state.isRinging)
        assertEquals(0L, state.totalMillis)
        assertNull(shadowOf(application).nextStartedService)
    }

    // --- wall-clock change (ACTION_TIME_CHANGED) -----------------------------

    @Test
    fun `time change re-arms a running timer from the persisted deadline`() = runTest {
        val end = System.currentTimeMillis() + 60_000L
        TimerStore.setTimer(context, end, 60_000L) // persisted, but nothing armed yet
        assertTrue(fireAlarms().isEmpty())

        TimerController.onTimeChanged(context)

        val state = TimerStore.state(context)
        assertEquals(end, state.endTimeMillis) // the wall-clock deadline holds
        assertEquals(1, fireAlarms().size)
        assertEquals(end, fireAlarms().single().triggerAtMs)
        assertNull(shadowOf(application).nextStartedService) // no ring
    }

    @Test
    fun `time change past the deadline rings now`() = runTest {
        // A live timer: its FIRE alarm is armed. Then the clock jumps past the deadline.
        TimerController.addTime(context, 60_000L)
        TimerStore.setTimer(context, System.currentTimeMillis() - 5_000L, 60_000L)

        TimerController.onTimeChanged(context)

        val state = TimerStore.state(context)
        assertTrue(state.isRinging)
        assertEquals(0L, state.endTimeMillis)
        assertEquals(60_000L, state.totalMillis)
        assertTrue(ringServiceStarted())
    }

    @Test
    fun `time change clears a passed deadline whose alarm is gone instead of ringing it`() = runTest {
        // A force-stop (alarms canceled) or a backup restored onto a new phone: the deadline
        // is persisted, no FIRE alarm exists, and the clock steps days later.
        TimerStore.setTimer(context, System.currentTimeMillis() - 3 * 86_400_000L, 60_000L)

        TimerController.onTimeChanged(context)

        val state = TimerStore.state(context)
        assertTrue(!state.isRinging)
        assertEquals(0L, state.endTimeMillis)
        assertEquals(0L, state.totalMillis)
        assertNull(shadowOf(application).nextStartedService)
    }

    @Test
    fun `time change leaves a paused timer alone`() = runTest {
        TimerStore.setTimer(context, 0L, 60_000L)
        TimerStore.setPaused(context, 30_000L)

        TimerController.onTimeChanged(context)

        val state = TimerStore.state(context)
        assertTrue(state.isPaused)
        assertEquals(30_000L, state.pausedRemainingMillis)
        assertTrue(fireAlarms().isEmpty())
        assertNull(shadowOf(application).nextStartedService)
    }

    @Test
    fun `time change during a ring keeps the session and does not restart the ring`() = runTest {
        val session = ringNow()

        TimerController.onTimeChanged(context)

        val state = TimerStore.state(context)
        assertTrue(state.isRinging)
        assertEquals(session, state.ringingSinceMillis) // Stop / +1 min surfaces stay valid
        assertNull(shadowOf(application).nextStartedService) // ringNow consumed the only start
        assertTrue(!ringServiceStopped())
    }

    // --- Settings "Test alarm" ---------------------------------------------------

    @Test
    fun `test alarm rings from idle through the normal ring path`() = runTest {
        TimerController.testAlarm(context)

        val state = TimerStore.state(context)
        assertTrue(state.isRinging)
        assertTrue(state.ringingSinceMillis > 0L)
        assertEquals(0L, state.totalMillis) // no duration for the surfaces to name
        assertTrue(ringServiceStarted())
    }

    @Test
    fun `test alarm runs when the only timer is a deadline that already passed`() = runTest {
        TimerStore.setTimer(context, System.currentTimeMillis() - 3 * 86_400_000L, 60_000L)

        TimerController.testAlarm(context)

        val state = TimerStore.state(context)
        assertTrue(state.isRinging)
        assertEquals(0L, state.totalMillis) // the alarm screen says "Test alarm", not the old timer
        assertTrue(ringServiceStarted())
    }

    @Test
    fun `test alarm is a no-op while a timer is running, paused, or ringing`() = runTest {
        val end = System.currentTimeMillis() + 60_000L
        TimerStore.setTimer(context, end, 60_000L)
        TimerController.testAlarm(context)
        assertEquals(end, TimerStore.state(context).endTimeMillis)
        assertNull(shadowOf(application).nextStartedService)

        TimerStore.setPaused(context, 30_000L)
        TimerController.testAlarm(context)
        assertTrue(TimerStore.state(context).isPaused)
        assertNull(shadowOf(application).nextStartedService)

        val session = ringNow()
        TimerController.testAlarm(context)
        assertEquals(session, TimerStore.state(context).ringingSinceMillis)
        assertNull(shadowOf(application).nextStartedService)
    }

    @Test
    fun `concurrent addTime calls never lose an increment`() = runTest {
        // Every caller (Compose tap, widget action, receiver) launches its own
        // coroutine; each addTime suspends on the DataStore read, so without
        // serialization 20 overlapping calls compute from the same snapshot and
        // the last write wins. Release all 20 from a barrier so they interleave.
        val taps = 20
        val gate = CompletableDeferred<Unit>()
        val before = System.currentTimeMillis()
        val jobs = List(taps) {
            launch {
                gate.await()
                TimerController.addTime(context, 60_000L)
            }
        }
        gate.complete(Unit)
        jobs.joinAll()
        val after = System.currentTimeMillis()

        val state = TimerStore.state(context)
        assertEquals(taps * 60_000L, state.totalMillis)
        assertTrue(state.endTimeMillis >= before + taps * 60_000L)
        assertTrue(state.endTimeMillis <= after + taps * 60_000L)
        assertEquals(1, fireAlarms().size)
        assertEquals(state.endTimeMillis, fireAlarms().first().triggerAtMs)
    }

    @Test
    fun `stop survives a widget refresh failure`() = runTest {
        val session = ringNow()
        // Glance updateAll is IPC to the launcher; if it throws, the alarm
        // must still be silenced and the persisted state idle.
        TimerController.widgetRefresher = { throw IllegalStateException("launcher dead") }
        TimerController.stopRinging(context, session)

        val state = TimerStore.state(context)
        assertTrue(!state.isRinging)
        assertEquals(0L, state.endTimeMillis)
        assertEquals(0L, state.totalMillis)
        assertTrue(ringServiceStopped())
        assertTrue(alarmCardGone())
    }

    @Test
    fun `addTime while ringing silences the ring even when the widget throws`() = runTest {
        ringNow()
        TimerController.widgetRefresher = { throw IllegalStateException("launcher dead") }
        val before = System.currentTimeMillis()
        TimerController.addTime(context, 30_000L)

        val state = TimerStore.state(context)
        assertTrue(!state.isRinging)
        assertTrue(state.endTimeMillis >= before + 30_000L)
        assertEquals(30_000L, state.totalMillis)
        assertTrue(ringServiceStopped())
        assertTrue(alarmCardGone())
        assertEquals(1, fireAlarms().size)
    }

    // ---- Ring-session contract ----

    @Test
    fun `plus one minute silences the ring and starts a fresh 60s timer`() = runTest {
        val session = ringNow()
        val before = System.currentTimeMillis()
        TimerController.extendRing(context, session)

        val state = TimerStore.state(context)
        assertTrue(!state.isRinging)
        assertEquals(TimerController.EXTEND_MILLIS, state.totalMillis)
        assertTrue(state.endTimeMillis >= before + TimerController.EXTEND_MILLIS)
        assertTrue(ringServiceStopped())
        assertTrue(alarmCardGone())
        assertEquals(state.endTimeMillis, fireAlarms().single().triggerAtMs)
    }

    @Test
    fun `a stale stop after plus one minute leaves the new minute running`() = runTest {
        // The old card's Stop was queued behind +1 min; it must not clear the
        // timer +1 min just scheduled (the sequence that used to zero it out).
        val session = ringNow()
        TimerController.extendRing(context, session)
        val extended = TimerStore.state(context)
        TimerController.stopRinging(context, session)

        val state = TimerStore.state(context)
        assertEquals(extended.endTimeMillis, state.endTimeMillis)
        assertEquals(TimerController.EXTEND_MILLIS, state.totalMillis)
        assertTrue(state.isRunning(System.currentTimeMillis()))
        assertEquals(1, fireAlarms().size)
    }

    @Test
    fun `a duplicate plus one minute does not become two minutes`() = runTest {
        val session = ringNow()
        TimerController.extendRing(context, session)
        val first = TimerStore.state(context)
        TimerController.extendRing(context, session)

        val state = TimerStore.state(context)
        assertEquals(first.endTimeMillis, state.endTimeMillis)
        assertEquals(TimerController.EXTEND_MILLIS, state.totalMillis)
        assertEquals(1, fireAlarms().size)
    }

    @Test
    fun `intentional quick-add taps after plus one minute still add`() = runTest {
        // The guard is on the ring session, not on +1m taps in general: two
        // deliberate +1m taps on the RUNNING timer must still count twice.
        val session = ringNow()
        TimerController.extendRing(context, session)
        TimerController.addTime(context, 60_000L)
        TimerController.addTime(context, 60_000L)

        val state = TimerStore.state(context)
        assertEquals(180_000L, state.totalMillis)
        assertEquals(1, fireAlarms().size)
    }

    @Test
    fun `a stale plus one minute does not restart a dismissed alarm`() = runTest {
        val session = ringNow()
        TimerController.stopRinging(context, session)
        TimerController.extendRing(context, session)

        val state = TimerStore.state(context)
        assertTrue(!state.isRinging)
        assertEquals(0L, state.endTimeMillis)
        assertEquals(0L, state.totalMillis)
        assertTrue(fireAlarms().isEmpty())
    }

    @Test
    fun `a stop carrying another session does not end the current ring`() = runTest {
        val session = ringNow()
        TimerController.stopRinging(context, session - 1L)
        TimerController.stopRinging(context, 0L)

        val state = TimerStore.state(context)
        assertTrue(state.isRinging)
        assertEquals(session, state.ringingSinceMillis)
        assertTrue(!alarmCardGone())
    }

    @Test
    fun `stop with the live session ends the ring`() = runTest {
        val session = ringNow()
        TimerController.stopRinging(context, session)

        val state = TimerStore.state(context)
        assertTrue(!state.isRinging)
        assertEquals(0L, state.endTimeMillis)
        assertTrue(ringServiceStopped())
        assertTrue(alarmCardGone())
    }

    @Test
    fun `a clock step after cancel never rings the canceled deadline`() = runTest {
        // Cancel retires the FIRE token itself, not only its alarm. If the clear failed and the
        // deadline were left behind, a later clock step must still not ring it.
        TimerController.addTime(context, 60_000L)
        TimerController.cancel(context)
        TimerStore.setTimer(context, System.currentTimeMillis() - 5_000L, 60_000L) // the leftover

        TimerController.onTimeChanged(context)

        val state = TimerStore.state(context)
        assertTrue(!state.isRinging)
        assertEquals(0L, state.endTimeMillis)
        assertNull(shadowOf(application).nextStartedService)
    }

    // ---- Widget delivery off the critical section ----

    @Test
    fun `a cancellation thrown out of a render does not stop later renders`() = runBlocking {
        // Glance can surface a CancellationException that is not ours. The drain used to exit
        // still holding its slot, so every later request was swallowed.
        var calls = 0
        val delivered = CompletableDeferred<Unit>()
        TimerController.widgetRefresher = {
            calls++
            if (calls == 1) throw kotlinx.coroutines.CancellationException("from the library")
            delivered.complete(Unit)
        }
        TimerController.addTime(context, 30_000L)
        withTimeout(5_000L) {
            while (calls < 1) delay(10)
            delay(50)
            TimerController.addTime(context, 30_000L)
            delivered.await()
        }
    }

    @Test
    fun `a hung widget provider does not cost the others their update`() = runBlocking {
        val reached = mutableListOf<Int>()
        val failure = runCatching {
            withTimeout(5_000L) {
                com.merrimentlabs.ducky.widget.TimerWidget.attemptEach(
                    listOf(
                        suspend { awaitCancellation() },
                        suspend { reached += 2 },
                        suspend { throw IllegalStateException("launcher dead") },
                        suspend { reached += 4 },
                    ),
                    perAttemptMs = 200L,
                )
            }
        }.exceptionOrNull()
        assertEquals(listOf(2, 4), reached)
        // The caller still hears that something failed (it logs it).
        assertTrue(failure is IllegalStateException)
    }

    @Test
    fun `a suspended widget refresher does not block stop, plus one minute, or fire`() = runBlocking {
        // A launcher IPC that never returns. Every command below used to await
        // it under the mutex; now none of them may wait on presentation work.
        // Real time (runBlocking): the timeout must measure actual waiting,
        // not runTest's virtual clock racing DataStore's own dispatcher.
        TimerController.widgetRefresher = { awaitCancellation() }
        withTimeout(5_000L) {
            val session = ringNow()
            TimerController.extendRing(context, session)
            assertTrue(TimerStore.state(context).isRunning(System.currentTimeMillis()))

            // Deadline reached: the due FIRE must ring without waiting either.
            TimerStore.setTimer(context, System.currentTimeMillis() - 1L, 60_000L)
            TimerController.onAlarmFired(context)
            val second = TimerStore.state(context)
            assertTrue(second.isRinging)

            TimerController.stopRinging(context, second.ringingSinceMillis)
            assertTrue(!TimerStore.state(context).isRinging)
            assertEquals(0L, TimerStore.state(context).endTimeMillis)
        }
    }

    @Test
    fun `mutations during a held render coalesce into one final render of the newest state`() = runBlocking {
        // The first render blocks (slow launcher). Two more taps land while it
        // is held. On release the drain must render exactly once more, and
        // that render must see the final state, not one per tap, and never
        // an older snapshot.
        val gate = CompletableDeferred<Unit>()
        val seenTotals = mutableListOf<Long>()
        var renders = 0
        TimerController.widgetRefresher = { ctx ->
            renders++
            if (renders == 1) gate.await()
            seenTotals += TimerStore.state(ctx).totalMillis
        }
        withTimeout(5_000L) {
            TimerController.addTime(context, 30_000L)
            while (renders < 1) delay(10)
            TimerController.addTime(context, 30_000L)
            TimerController.addTime(context, 30_000L)
            gate.complete(Unit)
            while (seenTotals.size < 2) delay(10)
            delay(200) // settle: no third render may follow
        }
        assertEquals(2, renders)
        assertEquals(90_000L, seenTotals.last())
    }

    @Test
    fun `a widget refresh is delivered after a mutation`() = runBlocking {
        // Off the lock, but not fire-and-forget: the render still happens.
        val delivered = CompletableDeferred<Unit>()
        TimerController.widgetRefresher = { delivered.complete(Unit) }
        TimerController.addTime(context, 30_000L)
        withTimeout(5_000L) { delivered.await() }
    }

    // ---- Countdown commands outlive the caller's scope ----

    /**
     * Every scheduled alarm, fire and display tick alike. Reads the shadow's
     * list rather than nextScheduledAlarm, which CONSUMES the alarm it
     * returns and would empty the queue a poll loop is waiting on.
     */
    private fun scheduledAlarms() = shadowOf(alarmManager).scheduledAlarms

    /**
     * A tap on a screen that leaves composition in the same frame: the request
     * is issued, then the scope Compose would have run the work on is
     * canceled. Returns once the request call itself has returned.
     */
    private suspend fun tapThenLeaveComposition(tap: () -> Unit) {
        val tapScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val issued = CompletableDeferred<Unit>()
        tapScope.launch {
            tap()
            tapScope.cancel()
            issued.complete(Unit)
        }
        issued.await()
    }

    @Test
    fun `countdown commands complete after the caller scope is canceled`() = runBlocking {
        // Real time, not runTest's virtual clock: the work lands on the
        // controller's own Dispatchers.Default scope, which runTest cannot
        // advance. Each phase waits on the AlarmManager side, since every
        // mutation writes DataStore first and schedules second.
        withTimeout(5_000L) {
            tapThenLeaveComposition { TimerController.requestAddTime(context, 60_000L) }
            while (fireAlarms().isEmpty()) delay(10)
            var state = TimerStore.state(context)
            assertEquals(60_000L, state.totalMillis)
            assertEquals(state.endTimeMillis, fireAlarms().single().triggerAtMs)

            tapThenLeaveComposition { TimerController.requestTogglePause(context) }
            while (!TimerStore.state(context).isPaused || scheduledAlarms().isNotEmpty()) delay(10)
            state = TimerStore.state(context)
            assertTrue(state.isPaused)
            assertEquals(0L, state.endTimeMillis)
            assertTrue(state.pausedRemainingMillis in 1..60_000L)

            tapThenLeaveComposition { TimerController.requestTogglePause(context) }
            while (fireAlarms().isEmpty()) delay(10)
            state = TimerStore.state(context)
            assertTrue(!state.isPaused)
            assertEquals(state.endTimeMillis, fireAlarms().single().triggerAtMs)

            tapThenLeaveComposition { TimerController.requestCancel(context) }
            while (TimerStore.state(context).totalMillis != 0L || scheduledAlarms().isNotEmpty()) delay(10)
            state = TimerStore.state(context)
            assertEquals(0L, state.totalMillis)
            assertEquals(0L, state.endTimeMillis)
        }
    }
}
