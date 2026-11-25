package com.merrimentlabs.ducky

import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.ComposeTestRule
import android.content.Context
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.swipe
import com.merrimentlabs.ducky.ui.Palette
import com.merrimentlabs.ducky.ui.SettingsScreen
import com.merrimentlabs.ducky.ui.TimerTheme
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
import org.robolectric.shadows.ShadowDialog
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import kotlin.math.abs

/**
 * [awaitNode] that also drains the main looper each pass: DataStore and IO hand-backs are posted
 * there, and frame pumping alone does not run them (the licenses list went missing about one run
 * in three after ComposeSmokeTest shared the JVM).
 */
private fun ComposeTestRule.customColorAwaitDrained(
    timeoutMs: Long = 5_000,
    find: ComposeTestRule.() -> SemanticsNodeInteraction,
): SemanticsNodeInteraction = awaitNode(timeoutMs) {
    shadowOf(Looper.getMainLooper()).idle()
    waitForIdle()
    find()
}

/**
 * The custom accent dialog: the hue wheel (pointer), its slider twin (TalkBack), Cancel and
 * "Use color". NATIVE graphics so the wheel's Canvas actually draws (captureToImage) and the
 * About header's launcher icon can be rasterized.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h2200dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsCustomColorTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val context: Context get() = rule.activity.applicationContext
    private val realWidgetRefresher = TimerController.widgetRefresher

    @Before
    fun setUp() {
        reset()
        TimerController.widgetRefresher = {}
        rule.setContent { TimerTheme { SettingsScreen(onBack = {}, onOpenLicenses = {}) } }
        rule.customColorAwaitDrained { onNodeWithText("Custom color…") }
    }

    @After
    fun tearDown() {
        reset()
        TimerController.resetWidgetDelivery()
        TimerController.widgetRefresher = realWidgetRefresher
    }

    private fun reset() = runBlocking {
        val c = androidx.test.core.app.ApplicationProvider.getApplicationContext<Context>()
        TimerStore.clearTimer(c)
        TimerStore.setStyle(c, TimerStore.DEFAULT_STYLE)
        TimerStore.setSeedColor(c, TimerStore.DEFAULT_SEED)
    }

    private fun seed(): Int = runBlocking { TimerStore.settings(context).seedColorArgb }

    private fun awaitSeed(expected: Int) {
        val deadline = System.currentTimeMillis() + 5_000
        while (seed() != expected) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("seed ${seed()} != $expected")
            rule.pump(1)
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    private fun openDialog() {
        rule.onNodeWithText("Custom color…").performClick()
        rule.customColorAwaitDrained { onNodeWithText("Custom color") }
        rule.pump(5)
    }

    private val slider get() = rule.onNodeWithContentDescription("Color wheel")

    /** The hue the slider reports to TalkBack ("120 degrees"). */
    private fun spokenHue(): Int {
        val state = slider.fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription)
        return Regex("""(\d+) degrees?""").matchEntire(state!!)!!.groupValues[1].toInt()
    }

    /**
     * Taps the wheel at [angle] degrees (0 = right, 90 = down, the wheel's own convention).
     * The wheel has no semantics of its own; it sits between the title and the slider.
     */
    private fun wheelPoint(angle: Double): Offset {
        val title = rule.onNodeWithText("Custom color").fetchSemanticsNode().boundsInRoot
        val bar = slider.fetchSemanticsNode().boundsInRoot
        val centerY = (title.bottom + bar.top) / 2f
        val radius = (bar.top - title.bottom) * 0.38f
        val rad = Math.toRadians(angle)
        // performTouchInput positions are relative to the node it runs on (the slider).
        return Offset(
            bar.center.x + radius * kotlin.math.cos(rad).toFloat() - bar.left,
            centerY + radius * kotlin.math.sin(rad).toFloat() - bar.top,
        )
    }

    private fun assertHueNear(expected: Int, actual: Int) {
        val d = abs(expected - actual).let { minOf(it, 360 - it) }
        assertTrue("hue $actual, expected about $expected", d <= 8)
    }

    @Test
    fun `the slider picks a hue and Use color stores that accent`() {
        openDialog()
        slider.performSemanticsAction(SemanticsActions.SetProgress) { it(120f) }
        rule.pump(2)
        assertEquals(120, spokenHue())
        rule.onNodeWithText("Use color").performClick()
        val expected = Palette.hsl(120f, 0.85f, 0.40f).toArgb()
        awaitSeed(expected)
        rule.pump(5)
        rule.onNodeWithText("Custom color").assertDoesNotExist()
        // A custom accent: no swatch is selected and the row offers a change.
        rule.customColorAwaitDrained { onNodeWithText("Custom color · tap to change") }
        rule.onNodeWithContentDescription("Water blue").assertExists()
    }

    @Test
    fun `the dialog opens on the current accent's hue`() {
        openDialog()
        assertEquals(Palette.hueOf(TimerStore.DEFAULT_SEED).let { Math.round(it) }, spokenHue())
    }

    @Test
    fun `Cancel closes the dialog and keeps the accent`() {
        openDialog()
        slider.performSemanticsAction(SemanticsActions.SetProgress) { it(300f) }
        rule.pump(2)
        rule.onNodeWithText("Cancel").performClick()
        rule.pump(5)
        rule.onNodeWithText("Custom color").assertDoesNotExist()
        repeat(20) { rule.pump(1); shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(5) }
        assertEquals(TimerStore.DEFAULT_SEED, seed())
        rule.onNodeWithText("Custom color…").assertExists()
    }

    @Test
    fun `tapping and dragging on the wheel move the hue`() {
        openDialog()
        slider.performTouchInput { click(wheelPoint(90.0)) }
        rule.pump(5)
        assertHueNear(90, spokenHue())
        slider.performTouchInput { click(wheelPoint(200.0)) }
        rule.pump(5)
        assertHueNear(200, spokenHue())
        slider.performTouchInput {
            swipe(start = wheelPoint(0.0), end = wheelPoint(300.0), durationMillis = 300)
        }
        rule.pump(5)
        assertHueNear(300, spokenHue())
        // The wheel's hue is what "Use color" stores.
        val hue = spokenHue()
        rule.onNodeWithText("Use color").performClick()
        awaitSeedNear(hue)
    }

    private fun awaitSeedNear(hue: Int) {
        val deadline = System.currentTimeMillis() + 5_000
        while (true) {
            val s = seed()
            if (s != TimerStore.DEFAULT_SEED) { assertHueNear(hue, Math.round(Palette.hueOf(s))); return }
            if (System.currentTimeMillis() > deadline) throw AssertionError("no accent stored")
            rule.pump(1)
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    @Test
    fun `the wheel draws its ring and knob`() {
        openDialog()
        slider.performSemanticsAction(SemanticsActions.SetProgress) { it(0f) }
        rule.waitForIdle()
        // captureToImage times out on a dialog window here, so draw its decor view by hand.
        val decor = ShadowDialog.getLatestDialog().window!!.decorView
        assertTrue("dialog never laid out", decor.width > 0 && decor.height > 0)
        val bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
        rule.runOnUiThread { decor.draw(Canvas(bitmap)) }
        val pixels = IntArray(bitmap.width * bitmap.height).also {
            bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        }
        fun count(test: (r: Int, g: Int, b: Int) -> Boolean) =
            pixels.count { test(AndroidColor.red(it), AndroidColor.green(it), AndroidColor.blue(it)) }
        // The ring sweeps every hue: its cyan quarter is there...
        val cyan = count { r, g, b -> r < 90 && g > 170 && b > 170 }
        // ...and the middle previews hue 0 at 85% / 40%: about (189, 15, 15), a disc of red.
        val preview = count { r, g, b -> r in 170..210 && g < 40 && b < 40 }
        assertTrue("ring not drawn ($cyan cyan pixels)", cyan > 300)
        assertTrue("preview not drawn ($preview red pixels)", preview > 3_000)
    }
}
