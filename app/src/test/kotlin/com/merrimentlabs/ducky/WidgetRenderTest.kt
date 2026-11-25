package com.merrimentlabs.ducky

import android.content.Context
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.Chronometer
import android.widget.FrameLayout
import android.widget.TextView
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.appwidget.compose
import androidx.test.core.app.ApplicationProvider
import androidx.compose.ui.graphics.toArgb
import com.merrimentlabs.ducky.ui.PRESET_SEEDS
import com.merrimentlabs.ducky.ui.Palette
import com.merrimentlabs.ducky.widget.BubblesWidget
import com.merrimentlabs.ducky.widget.EggTimerWidget
import com.merrimentlabs.ducky.widget.PondWidget
import com.merrimentlabs.ducky.widget.TimerWidget
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * Every widget class, rendered to RemoteViews and inflated, in every display mode and every size
 * class the layout code distinguishes:
 *  - height >= 130 dp: Stacked (3 presets from 200 dp wide, else 2; 130x130 is the Square);
 *  - else width >= 250 dp: Bar (every preset; the clock slot gets a subtitle from 90 dp);
 *  - else Compact (clock + ONE preset; ringing = the two actions and no tank).
 * Rendering goes through `compose(state = ...)`, so nothing here reads or writes the DataStore.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WidgetRenderTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    /** Running timers end 10 min 20 s out: "about 10 minutes" whatever the render takes. */
    private val runningLeft = 10 * 60_000L + 20_000L

    private val looks: List<TimerWidget> get() = listOf(TimerWidget(), PondWidget(), BubblesWidget(), EggTimerWidget())

    private fun state(
        mode: String,
        style: String = "mountain_view",
        hc: Boolean = false,
        seed: Int = TimerStore.DEFAULT_SEED,
        total: Long = 15 * 60_000L,
        increments: List<Int>? = null,
        endTime: Long? = null,
    ): Preferences {
        val now = System.currentTimeMillis()
        return mutablePreferencesOf(
            longPreferencesKey("end_time_millis") to (endTime ?: if (mode == "running") now + runningLeft else 0L),
            longPreferencesKey("total_millis") to if (mode == "idle" && endTime == null) 0L else total,
            longPreferencesKey("paused_remaining_millis") to if (mode == "paused") 90_000L else 0L,
            longPreferencesKey("ringing_since_millis") to if (mode == "ringing") now else 0L,
            stringPreferencesKey("style") to style,
            booleanPreferencesKey("high_contrast") to hc,
            intPreferencesKey("seed_color") to seed,
        ).apply {
            increments?.forEachIndexed { i, s -> this[intPreferencesKey("increment_${i + 1}")] = s }
        }
    }

    /** The inflated widget, read the way a launcher (and TalkBack) sees it. */
    private class Rendered(val root: View) {
        val views: List<View> = walk(root)
        val chronos: List<Chronometer> = views.filterIsInstance<Chronometer>()
        val texts: List<String> = views.filter { it is TextView && it !is Chronometer }.map { (it as TextView).text.toString() }

        /** Nodes Glance gave a description (the chronometer describes itself; it is hidden from TalkBack). */
        val described: List<View> = views.filter { it !is Chronometer && it.contentDescription != null }

        /** The time box: the one described node that opens the app. */
        val timeBox: String? = described.map { it.contentDescription.toString() }.singleOrNull { it.endsWith("Opens Ducky.") }

        /** Buttons: described nodes whose wrapper takes the click, minus the time box. */
        val buttons: List<String> = described
            .filter { (it.parent as? View)?.hasOnClickListeners() == true }
            .map { it.contentDescription.toString() }
            .filterNot { it.endsWith("Opens Ducky.") }

        fun click(description: String): Boolean =
            (described.first { it.contentDescription.toString() == description }.parent as View).performClick()

        companion object {
            fun walk(v: View): List<View> =
                listOf(v) + if (v is ViewGroup) (0 until v.childCount).flatMap { walk(v.getChildAt(it)) } else emptyList()
        }
    }

    private fun render(widget: TimerWidget, w: Int, h: Int, prefs: Preferences): Rendered = runBlocking {
        val views = widget.compose(context, size = DpSize(w.dp, h.dp), state = prefs)
        Rendered(views.apply(context, FrameLayout(context)))
    }

    private val ringingButtons = listOf("Add one more minute", "Stop alarm")
    private val defaultPresets = listOf("Add 30 seconds", "Add 1 minute", "Add 5 minutes")

    // ---- Ringing: the contract ----

    @Test
    fun `ringing offers exactly +1 min and Stop at every size and in every look`() {
        val stackedOrBar = listOf(200 to 140, 130 to 130, 300 to 130, 390 to 100, 290 to 70, 250 to 70)
        val compact = listOf(249 to 129, 130 to 60, 100 to 48)
        for (widget in looks) {
            for ((w, h) in stackedOrBar + compact) {
                val tag = "${widget.javaClass.simpleName} $w x $h"
                val r = render(widget, w, h, state("ringing"))
                assertEquals(tag, ringingButtons, r.buttons)
                assertTrue(tag, r.texts.containsAll(listOf("+1 min", "Stop")))
                assertFalse(tag, r.texts.any { it.startsWith("+") && it != "+1 min" })
                if ((w to h) in compact) {
                    // No tank: the row itself says the timer is ringing.
                    assertTrue(tag, r.chronos.isEmpty())
                    assertEquals(tag, null, r.timeBox)
                    assertTrue(tag, r.described.any { it.contentDescription == "Time's up, ringing" })
                } else {
                    assertEquals(tag, "Time's up, ringing. Opens Ducky.", r.timeBox)
                    assertEquals(tag, "00:00", r.chronos.single().text.toString())
                    // A ringing timer never renders as idle.
                    assertFalse(tag, r.texts.contains("Tap + to start"))
                }
            }
        }
    }

    @Test
    fun `the stacked ringing time box says Time's up under the frozen clock`() {
        val r = render(TimerWidget(), 200, 140, state("ringing"))
        assertTrue(r.texts.toString(), r.texts.contains("Time's up"))
        assertEquals("00:00", r.chronos.single().text.toString())
    }

    // ---- Size classes: how many presets each one shows ----

    @Test
    fun `stacked widgets show three presets from 200 dp wide and two below it`() {
        assertEquals(defaultPresets, render(TimerWidget(), 200, 140, state("idle")).buttons)
        assertEquals(defaultPresets.take(2), render(TimerWidget(), 199, 140, state("idle")).buttons)
        // The Square (2x2).
        assertEquals(defaultPresets.take(2), render(TimerWidget(), 130, 130, state("idle")).buttons)
        // 130 dp tall is the Stacked threshold, at any width.
        assertEquals(defaultPresets, render(TimerWidget(), 300, 130, state("idle")).buttons)
    }

    @Test
    fun `below 130 dp tall a wide widget is a bar with every preset and a narrow one is compact with one`() {
        assertEquals(defaultPresets, render(TimerWidget(), 300, 129, state("idle")).buttons)
        assertEquals(defaultPresets, render(TimerWidget(), 250, 70, state("idle")).buttons)
        assertEquals(defaultPresets.take(1), render(TimerWidget(), 249, 129, state("idle")).buttons)
        assertEquals(defaultPresets.take(1), render(TimerWidget(), 130, 60, state("idle")).buttons)
    }

    @Test
    fun `preset buttons follow the configured increments`() {
        val custom = state("idle", increments = listOf(45, 90, 600))
        val r = render(TimerWidget(), 390, 100, custom)
        assertEquals(listOf("Add 45 seconds", "Add 1 minute 30 seconds", "Add 10 minutes"), r.buttons)
        assertTrue(r.texts.toString(), r.texts.containsAll(listOf("+45s", "+1m 30s", "+10m")))
        assertEquals(listOf("Add 45 seconds"), render(TimerWidget(), 130, 60, custom).buttons)
        assertEquals(listOf("Add 45 seconds", "Add 1 minute 30 seconds"), render(TimerWidget(), 130, 130, custom).buttons)
    }

    @Test
    fun `the bar's clock slot gets a subtitle only when it is wide enough`() {
        // 250 dp: the clock slot is pinned at 66 dp, no room for "remaining".
        val narrow = render(TimerWidget(), 250, 70, state("running"))
        assertFalse(narrow.texts.toString(), narrow.texts.contains("remaining"))
        // 290 dp: a ~106 dp slot carries the subtitle; 390 dp: the big clock too.
        assertTrue(render(TimerWidget(), 290, 70, state("running")).texts.contains("remaining"))
        assertTrue(render(TimerWidget(), 390, 100, state("running")).texts.contains("remaining"))
        // Compact never has a subtitle.
        val compact = render(TimerWidget(), 130, 60, state("running"))
        assertFalse(compact.texts.toString(), compact.texts.contains("remaining"))
        assertEquals(1, compact.chronos.size)
    }

    @Test
    fun `a tiny compact widget still renders its clock and one preset`() {
        for (widget in looks) for (mode in listOf("idle", "running", "paused")) {
            val r = render(widget, 100, 48, state(mode))
            val tag = "${widget.javaClass.simpleName} $mode"
            assertEquals(tag, 1, r.chronos.size)
            assertEquals(tag, defaultPresets.take(1), r.buttons)
            assertNotNull(tag, r.timeBox)
        }
    }

    // ---- Modes: what the time box says ----

    @Test
    fun `every look speaks and shows the display mode`() {
        for (widget in looks) for ((w, h) in listOf(200 to 140, 390 to 100, 130 to 60)) {
            val tag = "${widget.javaClass.simpleName} $w x $h"
            val idle = render(widget, w, h, state("idle"))
            assertEquals(tag, "No timer set. Opens Ducky.", idle.timeBox)
            assertEquals(tag, "00:00", idle.chronos.single().text.toString())

            val paused = render(widget, w, h, state("paused"))
            assertEquals(tag, "Timer paused, 1 minute 30 seconds left. Opens Ducky.", paused.timeBox)
            assertEquals(tag, "01:30", paused.chronos.single().text.toString())

            val running = render(widget, w, h, state("running"))
            assertEquals(tag, "Timer, about 10 minutes left. Opens Ducky.", running.timeBox)
            assertTrue(tag, running.chronos.single().isCountDown)

            if (h >= 100) {
                assertTrue(tag, idle.texts.contains("Tap + to start"))
                assertTrue(tag, paused.texts.contains("paused"))
                assertTrue(tag, running.texts.contains("remaining"))
            }
        }
    }

    @Test
    fun `a running clock counts down to the deadline in the launcher's process`() {
        val before = SystemClock.elapsedRealtime()
        val chrono = render(TimerWidget(), 200, 140, state("running")).chronos.single()
        val after = SystemClock.elapsedRealtime()
        assertTrue(chrono.isCountDown)
        // base = elapsedRealtime + (end - now): the deadline on the chronometer's clock.
        assertTrue("base ${chrono.base}", chrono.base in (before + runningLeft - 5_000L)..(after + runningLeft))
    }

    @Test
    fun `a timer with no total still renders in every mode`() {
        // fraction falls back to 0 when total is 0 (a hand-edited or migrated state).
        for (widget in looks) {
            val running = render(widget, 200, 140, state("running", total = 0L))
            assertEquals("Timer, about 10 minutes left. Opens Ducky.", running.timeBox)
            val paused = render(widget, 200, 140, state("paused", total = 0L))
            assertEquals("01:30", paused.chronos.single().text.toString())
        }
    }

    // ---- Styles, contrast, accents, night ----

    @Test
    fun `London shouts its labels and subtitles`() {
        val idle = render(TimerWidget(), 390, 100, state("idle", style = "london"))
        assertTrue(idle.texts.toString(), idle.texts.containsAll(listOf("TAP + TO START", "+30S", "+1M", "+5M")))
        // Descriptions stay in sentence case for TalkBack.
        assertEquals(defaultPresets, idle.buttons)
        val ringing = render(TimerWidget(), 200, 140, state("ringing", style = "london"))
        assertTrue(ringing.texts.toString(), ringing.texts.containsAll(listOf("+1 MIN", "STOP", "TIME'S UP")))
        assertEquals(ringingButtons, ringing.buttons)
        val running = render(PondWidget(), 290, 70, state("running", style = "london"))
        assertTrue(running.texts.toString(), running.texts.contains("REMAINING"))
    }

    @Test
    fun `every style renders every mode with the same controls`() {
        for (style in listOf("mountain_view", "seoul", "london", "beijing")) for (widget in looks) {
            val tag = "$style ${widget.javaClass.simpleName}"
            assertEquals(tag, defaultPresets, render(widget, 250, 70, state("running", style = style)).buttons)
            assertEquals(tag, defaultPresets.take(2), render(widget, 130, 130, state("paused", style = style)).buttons)
            assertEquals(tag, ringingButtons, render(widget, 130, 60, state("ringing", style = style)).buttons)
        }
    }

    @Test
    fun `high contrast and every accent render the tank and the pond looks`() {
        for (hc in listOf(true, false)) for (seed in listOf(PRESET_SEEDS[0], PRESET_SEEDS[5], 0xFF7E57C2.toInt())) for (widget in looks) {
            val tag = "hc=$hc seed=${Integer.toHexString(seed)} ${widget.javaClass.simpleName}"
            val running = render(widget, 200, 140, state("running", hc = hc, seed = seed))
            assertEquals(tag, "Timer, about 10 minutes left. Opens Ducky.", running.timeBox)
            if (widget.javaClass == TimerWidget::class.java) {
                // The tank's clock ink comes from this accent's day scheme, contrast flag included.
                assertEquals(tag, Palette.light(seed, false, hc).onSurface.toArgb(), running.chronos.single().currentTextColor)
            }
            val paused = render(widget, 390, 100, state("paused", hc = hc, seed = seed, style = "london"))
            assertEquals(tag, "01:30", paused.chronos.single().text.toString())
        }
    }

    @Test
    @Config(qualifiers = "night")
    fun `the night palette renders every look in every mode`() {
        val night = Palette.dark(TimerStore.DEFAULT_SEED, false, false)
        for (widget in looks) for (mode in listOf("idle", "running", "paused", "ringing")) {
            val r = render(widget, 200, 140, state(mode))
            val tag = "${widget.javaClass.simpleName} $mode"
            assertNotNull(tag, r.timeBox)
            assertEquals(tag, if (mode == "ringing") ringingButtons else defaultPresets, r.buttons)
            if (widget.javaClass == TimerWidget::class.java) {
                // The tank's clock: night onSurface while running, onSurfaceVariant when stopped.
                val ink = if (mode == "running") night.onSurface else night.onSurfaceVariant
                assertEquals(tag, ink.toArgb(), r.chronos.single().currentTextColor)
            }
        }
    }

    @Test
    fun `the day palette inks the tank clock by mode`() {
        val day = Palette.light(TimerStore.DEFAULT_SEED, false, false)
        assertEquals(day.onSurface.toArgb(), render(TimerWidget(), 200, 140, state("running")).chronos.single().currentTextColor)
        assertEquals(day.onSurfaceVariant.toArgb(), render(TimerWidget(), 200, 140, state("paused")).chronos.single().currentTextColor)
        // The day and night inks differ, so the night test above can tell them apart.
        assertTrue(day.onSurface != Palette.dark(TimerStore.DEFAULT_SEED, false, false).onSurface)
    }

    // ---- Counted looks and the frame trace ----

    /** Fresh log, trace on (ShadowLog.clear() also forgets loggable tags). */
    private fun traceFrames() {
        ShadowLog.clear()
        ShadowLog.setLoggable("DuckyFrames", Log.DEBUG)
    }

    @Test
    fun `a deadline that just passed keeps the last bubble or egg until the ring lands`() {
        val now = System.currentTimeMillis()
        for (widget in listOf(BubblesWidget(), EggTimerWidget())) {
            traceFrames()
            val pending = render(widget, 200, 140, state("idle", total = 120_000L, endTime = now - 500L))
            // Mode is idle (the deadline is not in the future), so the text says so...
            assertEquals("No timer set. Opens Ducky.", pending.timeBox)
            // ...but the picture keeps one item left instead of refilling.
            val line = ShadowLog.getLogsForTag("DuckyFrames").single().msg
            assertTrue(line, line.contains("mode=IDLE"))
            assertTrue(line, Regex("left=1/\\d").containsMatchIn(line))

            traceFrames()
            render(widget, 200, 140, state("idle", total = 120_000L, endTime = now - 120_000L))
            val stale = ShadowLog.getLogsForTag("DuckyFrames").single().msg
            val count = Regex("left=(\\d+)/(\\d+)").find(stale)!!.groupValues
            assertEquals(stale, count[2], count[1]) // long passed: a full, idle picture
        }
    }

    @Test
    fun `the frame trace names the look and its picture state when enabled`() {
        traceFrames()
        render(EggTimerWidget(), 200, 140, state("paused", total = 180_000L))
        render(BubblesWidget(), 390, 100, state("ringing"))
        render(PondWidget(), 200, 140, state("running"))
        render(TimerWidget(), 200, 140, state("paused", total = 180_000L))
        val lines = ShadowLog.getLogsForTag("DuckyFrames").map { it.msg }
        assertEquals(lines.toString(), 4, lines.size)
        assertTrue(lines[0], lines[0].startsWith("look=EGGS") && lines[0].contains("mode=PAUSED") &&
            lines[0].contains("remaining=90000") && lines[0].contains("total=180000") && lines[0].contains("left="))
        assertTrue(lines[1], lines[1].startsWith("look=BUBBLES") && lines[1].contains("mode=RINGING") && lines[1].contains("left=0/"))
        assertTrue(lines[2], lines[2].startsWith("look=LILY_PADS") && lines[2].contains("mode=RUNNING") && lines[2].contains("pad="))
        assertTrue(lines[3], lines[3].startsWith("look=TANK") && lines[3].contains("fraction=0.5"))
    }

    @Test
    fun `the frame trace stays silent by default`() {
        render(EggTimerWidget(), 200, 140, state("running"))
        assertTrue(ShadowLog.getLogsForTag("DuckyFrames").isEmpty())
    }

    // ---- The time box opens the app ----

    @Test
    fun `tapping the time box opens Ducky`() {
        val r = render(TimerWidget(), 200, 140, state("idle"))
        assertTrue(r.click("No timer set. Opens Ducky."))
        val started = shadowOf(context as android.app.Application).nextStartedActivity
        assertEquals(MainActivity::class.java.name, started?.component?.className)
    }
}
