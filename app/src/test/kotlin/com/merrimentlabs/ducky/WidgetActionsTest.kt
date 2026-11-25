package com.merrimentlabs.ducky

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.action.actionParametersOf
import androidx.glance.appwidget.compose
import androidx.test.core.app.ApplicationProvider
import com.merrimentlabs.ducky.widget.AddTimeAction
import com.merrimentlabs.ducky.widget.BubblesWidget
import com.merrimentlabs.ducky.widget.RingAction
import com.merrimentlabs.ducky.widget.TimerWidget
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The widget's buttons, end to end: the rendered button's click broadcasts to Glance's
 * ActionCallbackBroadcastReceiver, which runs AddTimeAction / RingAction against the real
 * TimerController and TimerStore. Ringing actions carry the ring session they were drawn for
 * (the ringing contract in docs/ARCHITECTURE.md): a Stop drawn for an older ring does nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WidgetActionsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val application = context as Application
    private val realWidgetRefresher = TimerController.widgetRefresher
    private val glanceId = object : GlanceId {}

    @Before
    fun resetState() = runBlocking {
        TimerStore.clearTimer(context)
        // Nothing is placed on a launcher here; skip the update round-trip.
        TimerController.widgetRefresher = {}
    }

    @After
    fun restore() {
        TimerController.resetWidgetDelivery()
        TimerController.widgetRefresher = realWidgetRefresher
        runBlocking { TimerStore.clearTimer(context) }
    }

    /** A ringing timer, as AlarmManager's FIRE leaves it. Returns its ring session. */
    private suspend fun ringNow(): Long {
        TimerStore.setTimer(context, System.currentTimeMillis() - 1L, 60_000L)
        TimerController.onAlarmFired(context)
        val session = TimerStore.state(context).ringingSinceMillis
        assertTrue(session > 0L)
        return session
    }

    private fun walk(v: View): List<View> =
        listOf(v) + if (v is ViewGroup) (0 until v.childCount).flatMap { walk(v.getChildAt(it)) } else emptyList()

    /** Renders [widget] from the live store and taps the button TalkBack calls [description]. */
    private fun tap(description: String, widget: TimerWidget = TimerWidget(), w: Int = 200, h: Int = 140) = runBlocking {
        val prefs = context.timerDataStore.data.first()
        val root = widget.compose(context, size = DpSize(w.dp, h.dp), state = prefs).apply(context, FrameLayout(context))
        val button = walk(root).first { it.contentDescription?.toString() == description }.parent as View
        assertTrue(button.performClick())
    }

    /** The broadcast the last tap sent, aimed at Glance's callback receiver. */
    private fun lastCallbackBroadcast(): Intent {
        shadowOf(Looper.getMainLooper()).idle()
        val intent = shadowOf(application).broadcastIntents.last()
        assertEquals("androidx.glance.appwidget.action.ActionCallbackBroadcastReceiver", intent.component?.className)
        return intent
    }

    /** Polls the store (the receiver runs the callback off the main thread), bounded. */
    private fun awaitState(what: String, check: (TimerState) -> Boolean): TimerState {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (true) {
            shadowOf(Looper.getMainLooper()).idle()
            val state = runBlocking { TimerStore.state(context) }
            if (check(state)) return state
            if (System.nanoTime() > deadline) throw AssertionError("timed out waiting for $what: $state")
            Thread.sleep(20)
        }
    }

    // ---- Through the rendered buttons ----

    @Test
    fun `tapping a preset on the widget starts a timer of that length`() {
        val before = System.currentTimeMillis()
        tap("Add 30 seconds")
        lastCallbackBroadcast()
        val state = awaitState("a 30 s timer") { it.totalMillis == 30_000L }
        assertTrue(state.endTimeMillis >= before + 30_000L)
    }

    @Test
    fun `tapping a preset on a running timer adds to it`() {
        runBlocking { TimerController.addTime(context, 60_000L) }
        tap("Add 5 minutes", BubblesWidget(), 390, 100)
        awaitState("1 min + 5 min") { it.totalMillis == 360_000L }
    }

    @Test
    fun `tapping Stop on the ringing widget stops that ring`() {
        runBlocking { ringNow() }
        tap("Stop alarm")
        lastCallbackBroadcast()
        val state = awaitState("ring stopped") { !it.isRinging }
        assertEquals(0L, state.endTimeMillis)
        assertEquals(0L, state.totalMillis)
    }

    @Test
    fun `tapping +1 min on the ringing widget silences it and runs a fresh minute`() {
        runBlocking { ringNow() }
        val before = System.currentTimeMillis()
        tap("Add one more minute", TimerWidget(), 130, 60)
        lastCallbackBroadcast()
        val state = awaitState("a one-minute timer") { !it.isRinging && it.endTimeMillis > 0L }
        assertEquals(TimerController.EXTEND_MILLIS, state.totalMillis)
        assertTrue(state.endTimeMillis >= before + TimerController.EXTEND_MILLIS)
    }

    // ---- The callbacks directly ----

    /** A predefined click is playing: effectId alone defaults to 0, which IS EFFECT_CLICK. */
    private fun assertClickHaptic() {
        val vibrator = shadowOf(context.getSystemService(Vibrator::class.java))
        assertTrue("nothing vibrated", vibrator.isVibrating)
        assertEquals(VibrationEffect.EFFECT_CLICK, vibrator.effectId)
    }

    @Test
    fun `no haptic before any tap`() {
        assertFalse(shadowOf(context.getSystemService(Vibrator::class.java)).isVibrating)
    }

    @Test
    fun `AddTimeAction adds its seconds and confirms with a click haptic`() = runBlocking {
        AddTimeAction().onAction(context, glanceId, actionParametersOf(AddTimeAction.SECONDS to 90))
        assertEquals(90_000L, TimerStore.state(context).totalMillis)
        assertClickHaptic()
    }

    @Test
    fun `AddTimeAction without seconds does nothing`() = runBlocking {
        AddTimeAction().onAction(context, glanceId, actionParametersOf())
        assertEquals(TimerState(0L, 0L, 0L, 0L), TimerStore.state(context).copy(lastTickMillis = 0L))
    }

    @Test
    fun `RingAction confirms a live Stop with a click haptic`() = runBlocking {
        val session = ringNow()
        RingAction().onAction(context, glanceId, actionParametersOf(RingAction.KIND to RingAction.STOP, RingAction.SESSION to session))
        assertClickHaptic()
    }

    @Test
    fun `RingAction without a kind or a session does nothing`() = runBlocking {
        val session = ringNow()
        RingAction().onAction(context, glanceId, actionParametersOf(RingAction.SESSION to session))
        RingAction().onAction(context, glanceId, actionParametersOf(RingAction.KIND to RingAction.STOP))
        RingAction().onAction(context, glanceId, actionParametersOf(RingAction.KIND to "snooze", RingAction.SESSION to session))
        assertEquals(session, TimerStore.state(context).ringingSinceMillis)
    }

    @Test
    fun `RingAction STOP for the live session clears the ring and EXTEND for a stale one does not`() = runBlocking {
        val session = ringNow()
        RingAction().onAction(context, glanceId, actionParametersOf(RingAction.KIND to RingAction.EXTEND, RingAction.SESSION to session - 1))
        assertEquals(session, TimerStore.state(context).ringingSinceMillis)
        RingAction().onAction(context, glanceId, actionParametersOf(RingAction.KIND to RingAction.STOP, RingAction.SESSION to session))
        assertEquals(0L, TimerStore.state(context).ringingSinceMillis)
    }

    @Test
    fun `RingAction EXTEND for the live session runs a fresh minute`() = runBlocking {
        val session = ringNow()
        RingAction().onAction(context, glanceId, actionParametersOf(RingAction.KIND to RingAction.EXTEND, RingAction.SESSION to session))
        val state = TimerStore.state(context)
        assertEquals(0L, state.ringingSinceMillis)
        assertEquals(TimerController.EXTEND_MILLIS, state.totalMillis)
    }
}
