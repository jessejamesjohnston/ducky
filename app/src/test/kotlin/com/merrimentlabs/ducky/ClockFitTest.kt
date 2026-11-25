package com.merrimentlabs.ducky

import android.content.Context
import android.os.Looper
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The pond's clock in the space it gets. NATIVE graphics: the legacy mode measures text as a few
 * dp wide. Robolectric answers "no" to full-screen intents, so the alarm banner is up here,
 * which leaves a short window even less room than on a phone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ClockFitTest {
    @get:Rule val rule = createEmptyComposeRule()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val realWidgetRefresher = TimerController.widgetRefresher

    @Before fun setUp() = runBlocking {
        TimerController.widgetRefresher = {}
        TimerStore.setPaused(context, 3_600_000L) // "1:00:00", the widest everyday clock
    }

    @After fun tearDown() = runBlocking {
        TimerStore.clearTimer(context)
        TimerController.widgetRefresher = realWidgetRefresher
    }

    private class Clock(val layout: TextLayoutResult, val shownHeightPx: Float, val shownWidthPx: Float)

    private fun clock(): Clock {
        rule.manualClock()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        repeat(60) { rule.pump(1); shadowOf(Looper.getMainLooper()).idle() }
        val node = rule.onAllNodes(hasText("1:00:00"), useUnmergedTree = true).fetchSemanticsNodes().first()
        val results = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
        return Clock(results.first(), node.boundsInRoot.height, node.boundsInRoot.width).also { scenario.close() }
    }

    @Test
    @Config(qualifiers = "w411dp-h420dp")
    fun `in a short window (split screen) the whole clock shows, smaller`() {
        val c = clock()
        assertEquals(1, c.layout.lineCount)
        // The box is clamped to the space it got; the paragraph is the text's own height.
        val needs = c.layout.multiParagraph.height
        assertTrue("clipped: $needs px of text in a ${c.shownHeightPx} px box", needs <= c.shownHeightPx + 1f)
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp", fontScale = 2.0f)
    fun `on a phone at 200 percent text the clock keeps its full size on one line`() {
        val c = clock()
        val density = context.resources.displayMetrics.density
        assertEquals(1, c.layout.lineCount)
        // 76 sp at 200%: Android 14+ scales large text nonlinearly, about 265 dp for "1:00:00".
        assertTrue("width ${c.shownWidthPx / density} dp", c.shownWidthPx / density in 255f..300f)
    }
}
