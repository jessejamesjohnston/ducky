package com.merrimentlabs.ducky

import android.content.Context
import android.os.Looper
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.merrimentlabs.ducky.ui.AppStyle
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The full-screen alarm. The rule is empty so the ring is persisted BEFORE the activity
 * starts: AlarmActivity leaves on its first non-ringing state.
 */
@RunWith(RobolectricTestRunner::class)
// A phone-sized screen: Robolectric's default 320x470 dp puts the action tiles off the bottom.
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
class AlarmActivityTest {
    @get:Rule val rule = createEmptyComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val realWidgetRefresher = TimerController.widgetRefresher
    private var scenario: ActivityScenario<AlarmActivity>? = null
    private var activity: AlarmActivity? = null

    /** The controller's mutex: held by a test, no command can change the persisted state. */
    private val controllerMutex: Mutex =
        TimerController::class.java.getDeclaredField("mutex").apply { isAccessible = true }
            .get(null) as Mutex

    private var holding = false

    private fun hold() {
        assertTrue(controllerMutex.tryLock())
        holding = true
    }

    private fun release() {
        holding = false
        controllerMutex.unlock()
    }

    @Before
    fun reset() {
        TimerController.widgetRefresher = {}
        runBlocking {
            TimerStore.clearTimer(context)
            TimerStore.setStyle(context, TimerStore.DEFAULT_STYLE)
        }
    }

    @After
    fun restore() {
        if (holding) release()
        // Let an in-flight command finish under its own lock before the next test starts.
        runBlocking { controllerMutex.lock(); controllerMutex.unlock() }
        scenario?.close()
        TimerController.resetWidgetDelivery()
        TimerController.widgetRefresher = realWidgetRefresher
        runBlocking {
            TimerStore.clearTimer(context)
            TimerStore.setStyle(context, TimerStore.DEFAULT_STYLE)
        }
    }

    private fun state() = runBlocking { TimerStore.state(context) }

    /** Persists a ring that started [agoMs] ago, for a timer of [total] ms. Returns its session. */
    private fun ringing(total: Long, agoMs: Long = 5_000L): Long = runBlocking {
        TimerStore.setTimer(context, System.currentTimeMillis() - agoMs, total)
        val session = System.currentTimeMillis() - agoMs
        TimerStore.setRinging(context, session)
        session
    }

    private fun launch() {
        rule.manualClock()
        scenario = ActivityScenario.launch(AlarmActivity::class.java).also { sc ->
            sc.onActivity { activity = it }
        }
    }

    /**
     * A few frames with the main looper drained: the click's state write reaches the
     * recomposer through a main-looper message, which frame pumping alone does not run.
     */
    private fun settle() = repeat(3) {
        rule.pump(1)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun awaitTrue(done: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!done()) {
            if (System.currentTimeMillis() > deadline) fail("condition not reached in 5 s")
            rule.pump(1) // frames, so a new state recomposes and its effects run
        }
    }

    /** The screen called finishAndRemoveTask (Robolectric records it; no real task to end). */
    private fun awaitFinished() = awaitTrue { activity!!.isFinishing }

    @Test
    fun `a ringing timer shows its length, how long it has rung, and both actions`() {
        ringing(total = 90_000L)
        launch()
        rule.awaitNode { onNodeWithText("Time's up") }
        rule.onNodeWithText("0:00").assertExists()
        // "1:30 timer · ringing for 0:05": the elapsed part depends on the clock, the rest not.
        rule.awaitNode { onNodeWithText("1:30 timer · ringing for", substring = true) }
        rule.awaitNode { onNodeWithContentDescription("Stop alarm") }.assertIsEnabled()
        rule.onNodeWithContentDescription("Add one more minute").assertIsEnabled()
    }

    @Test
    fun `the Settings test alarm names no duration`() {
        ringing(total = 0L)
        launch()
        rule.awaitNode { onNodeWithText("Test alarm · ringing for", substring = true) }
    }

    @Test
    fun `an upper-case style shouts the headline`() {
        runBlocking { TimerStore.setStyle(context, AppStyle.LONDON.key) }
        ringing(total = 60_000L)
        launch()
        rule.awaitNode { onNodeWithText("TIME'S UP") }
    }

    @Test
    fun `Stop acts once, on this ring's session, and the screen leaves`() {
        val session = ringing(total = 60_000L)
        launch()
        val stop = rule.awaitNode { onNodeWithContentDescription("Stop alarm") }
        hold() // the command waits until the buttons are checked
        stop.performClick()
        settle()
        rule.onNodeWithContentDescription("Stop alarm").assertIsNotEnabled()
        rule.onNodeWithContentDescription("Add one more minute").assertIsNotEnabled()
        assertEquals(session, state().ringingSinceMillis) // nothing applied yet

        release()
        awaitTrue { !state().isRinging }
        assertEquals(0L, state().totalMillis)
        awaitFinished()
    }

    @Test
    fun `a new ring gets working buttons even if the screen never saw the old one end`() {
        val first = ringing(total = 60_000L)
        launch()
        val extend = rule.awaitNode { onNodeWithContentDescription("Add one more minute") }
        hold()
        extend.performClick()
        settle()
        rule.onNodeWithContentDescription("Stop alarm").assertIsNotEnabled()
        // The screen next sees a different ring, never the running minute in between (it was
        // in the background then): that ring's buttons work.
        val second = first + 61_000L
        runBlocking { TimerStore.setRinging(context, second) }
        settle()
        rule.onNodeWithContentDescription("Stop alarm").assertIsEnabled()
        rule.onNodeWithContentDescription("Add one more minute").assertIsEnabled()
        release()
        // The held +1 min was for the first ring: stale, it changes nothing.
        settle()
        assertEquals(second, state().ringingSinceMillis)
    }

    @Test
    fun `+1 min turns the ring into a fresh minute and the screen leaves`() {
        ringing(total = 60_000L)
        launch()
        val extend = rule.awaitNode { onNodeWithContentDescription("Add one more minute") }
        hold()
        extend.performClick()
        settle()
        rule.onNodeWithContentDescription("Stop alarm").assertIsNotEnabled()
        rule.onNodeWithContentDescription("Add one more minute").assertIsNotEnabled()

        release()
        awaitTrue { state().isRunning(System.currentTimeMillis()) }
        assertFalse(state().isRinging)
        assertEquals(TimerController.EXTEND_MILLIS, state().totalMillis)
        awaitFinished()
    }

    @Test
    fun `the screen leaves when the ring is stopped from another surface`() {
        val session = ringing(total = 60_000L)
        launch()
        rule.awaitNode { onNodeWithContentDescription("Stop alarm") }
        runBlocking { TimerController.stopRinging(context, session) } // the notification's Stop
        awaitFinished()
    }

    @Test
    fun `opened with nothing ringing, the screen closes at once`() {
        launch()
        awaitFinished()
    }


}
