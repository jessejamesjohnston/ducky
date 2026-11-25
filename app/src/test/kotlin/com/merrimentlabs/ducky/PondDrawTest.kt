package com.merrimentlabs.ducky

import android.hardware.Sensor
import android.hardware.SensorManager
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.testTag
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import com.merrimentlabs.ducky.ui.AppStyle
import com.merrimentlabs.ducky.ui.Palette
import com.merrimentlabs.ducky.ui.pond.DuckColors
import com.merrimentlabs.ducky.ui.pond.DuckPainter
import com.merrimentlabs.ducky.ui.pond.Pond
import com.merrimentlabs.ducky.ui.pond.PondColors
import com.merrimentlabs.ducky.ui.pond.PondParticles
import com.merrimentlabs.ducky.ui.pond.PondSim
import com.merrimentlabs.ducky.ui.pond.pondInk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.SensorBuilder
import org.robolectric.shadows.SensorEventBuilder
import org.robolectric.shadows.ShadowSensorManager
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * The pond composable on a real draw pass (native graphics): what it paints for each timer
 * state and color style, the accelerometer it listens to on resume (and lets go of on pause),
 * the haptic tick for a hard bump, and the still pond under reduced motion. Pond only reads
 * the state it is handed, so no DataStore is involved.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h900dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PondDrawTest {

    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val tag = "pond"
    private lateinit var sim: PondSim
    private lateinit var view: View
    private var density = 1f
    private var state by mutableStateOf(TimerState(0L, 0L, 0L, 0L))
    private var reduced by mutableStateOf(false)
    private var pondColors by mutableStateOf<PondColors?>(null)

    private val sensors: ShadowSensorManager
        get() = shadowOf(rule.activity.getSystemService(SensorManager::class.java))

    @Before fun setUp() {
        rule.manualClock()
    }

    private fun colors(style: AppStyle = AppStyle.MOUNTAIN_VIEW, dark: Boolean = false, hc: Boolean = false): PondColors {
        val seed = TimerStore.DEFAULT_SEED
        val scheme = if (dark) Palette.dark(seed, style.monochrome, hc) else Palette.light(seed, style.monochrome, hc)
        return PondColors.from(seed, style, dark, hc, scheme)
    }

    private fun now() = System.currentTimeMillis()
    /** [fill] of a 20-minute timer left; the level drains about 0.1% per real second. */
    private fun running(fill: Float) = TimerState(now() + (1_200_000L * fill).toLong(), 1_200_000L, 0L, 0L)
    private fun paused(fill: Float) = TimerState(0L, 1_200_000L, 0L, (1_200_000L * fill).toLong())
    private fun ringing() = TimerState(0L, 1_200_000L, now(), 0L)

    private fun show(
        c: PondColors,
        initial: TimerState,
        still: Boolean = false,
        squareBottom: Boolean = false,
        direction: LayoutDirection = LayoutDirection.Ltr,
    ) {
        state = initial
        reduced = still
        pondColors = c
        // Seeded visitors: a long running timer here may bring one, the same one every run.
        sim = PondSim(visitorRng = Random(3)).apply { headerBottom = 120f }
        rule.setContent {
            view = LocalView.current
            density = LocalDensity.current.density
            CompositionLocalProvider(LocalLayoutDirection provides direction) {
                Box(Modifier.size(360.dp, 640.dp).testTag(tag)) {
                    Pond(sim, state, pondColors!!, reduced, 28.dp, Modifier.size(360.dp, 640.dp), squareBottomCorners = squareBottom)
                }
            }
        }
        rule.pump(1)
    }

    /**
     * A software draw of the whole compose view (the pond sits at its top left). The stock
     * captureToImage waits for a vsync redraw that the manual clock never delivers.
     */
    private fun capture(): PixelMap {
        lateinit var bmp: Bitmap
        rule.runOnUiThread {
            bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bmp))
        }
        return bmp.asImageBitmap().toPixelMap()
    }

    /** First draw sizes the sim; [frames] then step it. */
    private fun settle(frames: Int = 30): PixelMap {
        capture()
        rule.pump(frames)
        return capture()
    }

    private fun near(a: Color, b: Color, tol: Float = 0.03f) =
        abs(a.red - b.red) <= tol && abs(a.green - b.green) <= tol && abs(a.blue - b.blue) <= tol

    private fun dist(a: Color, b: Color) = abs(a.red - b.red) + abs(a.green - b.green) + abs(a.blue - b.blue)

    /** A column far from the duck (it starts near 30% of the width). */
    private fun clearColumn() = if (sim.duckX < sim.width / 2f) (sim.width * 0.85f).toInt() else (sim.width * 0.15f).toInt()

    private fun yellowNearDuck(px: PixelMap): Int {
        val s = sim.duckScale
        var n = 0
        val x0 = (sim.duckX + DuckPainter.LEFT * s).toInt().coerceAtLeast(0)
        val x1 = (sim.duckX + DuckPainter.RIGHT * s).toInt().coerceAtMost(px.width - 1)
        val y0 = (sim.duckY + DuckPainter.TOP * s).toInt().coerceAtLeast(0)
        val y1 = (sim.duckY + DuckPainter.BOTTOM * s).toInt().coerceAtMost(px.height - 1)
        for (y in y0..y1 step 2) for (x in x0..x1 step 2) if (near(px[x, y], DuckColors.BODY, 0.06f)) n++
        return n
    }

    private fun inkNearDuck(px: PixelMap): Int {
        val s = sim.duckScale
        var n = 0
        val x0 = (sim.duckX + DuckPainter.LEFT * s).toInt().coerceAtLeast(0)
        val x1 = (sim.duckX + DuckPainter.RIGHT * s).toInt().coerceAtMost(px.width - 1)
        val y0 = (sim.duckY + DuckPainter.TOP * s).toInt().coerceAtLeast(0)
        val y1 = (sim.duckY + DuckPainter.BOTTOM * s).toInt().coerceAtMost(px.height - 1)
        for (y in y0..y1) for (x in x0..x1) if (near(px[x, y], DuckColors.INK, 0.08f)) n++
        return n
    }

    // ---- what it paints ------------------------------------------------------------------

    /** The two bottom corners' pixels: (left, right), 2 px in from each edge. */
    private fun bottomCorners(px: PixelMap): Pair<Color, Color> {
        val w = (360f * density).toInt()
        val h = (640f * density).toInt()
        return px[2, h - 3] to px[w - 3, h - 3]
    }

    @Test fun `on its own the pond rounds both bottom corners`() {
        val c = colors()
        show(c, running(0.5f))
        val (left, right) = bottomCorners(settle())
        assertTrue("left corner is sky: $left", near(left, c.sky, 0.02f))
        assertTrue("right corner is sky: $right", near(right, c.sky, 0.02f))
    }

    private fun assertSquareBottom(direction: LayoutDirection) {
        val c = colors()
        show(c, running(0.5f), squareBottom = true, direction = direction)
        val (left, right) = bottomCorners(settle())
        assertTrue("$direction: left corner is water: $left", dist(left, c.sky) > 0.3f)
        assertTrue("$direction: right corner is water: $right", dist(right, c.sky) > 0.3f)
    }

    @Test fun `beside the controls both bottom corners are square`() = assertSquareBottom(LayoutDirection.Ltr)

    @Test fun `right to left too`() = assertSquareBottom(LayoutDirection.Rtl)

    @Test fun `before its first step the pond is only sky`() {
        val c = colors()
        show(c, TimerState(0L, 0L, 0L, 0L))
        val px = capture()
        assertTrue("the first draw sizes the sim", sim.width > 0f && sim.height > 0f)
        assertTrue(sim.duckX.isNaN())
        for (y in 0 until px.height step 97) for (x in 0 until px.width step 89) {
            assertTrue("sky at $x,$y", near(px[x, y], c.sky, 0.01f))
        }
    }

    @Test fun `idle, the water fills most of the pond and the duck floats on it`() {
        val c = colors()
        show(c, TimerState(0L, 0L, 0L, 0L))
        val px = settle()
        assertEquals(360f * density, sim.width, 1f)
        val x = clearColumn()
        val surface = sim.surfaceAt(x.toFloat()).toInt()
        assertTrue("sky well above the water", near(px[x, surface - (90 * density).toInt()], c.sky, 0.02f))
        val water = px[x, surface + (40 * density).toInt()]
        assertTrue("water below the surface: $water", dist(water, c.sky) > 0.3f)
        assertTrue("the duck is drawn: ${yellowNearDuck(px)}", yellowNearDuck(px) > 100)
    }

    @Test fun `the waterline follows the fill`() {
        val c = colors()
        show(c, running(0.9f))
        val high = settle(60)
        val x = clearColumn()
        val highSurface = sim.surfaceAt(x.toFloat())
        state = running(0.3f) // not a transition: the level follows the deadline
        rule.pump(90)
        val low = capture()
        val lowSurface = sim.surfaceAt(x.toFloat())
        assertTrue("lower water: $highSurface -> $lowSurface", lowSurface > highSurface + 200f * density)
        val between = ((highSurface + lowSurface) / 2f).toInt()
        assertTrue("water there at 90%", dist(high[x, between], c.sky) > 0.3f)
        assertTrue("sky there at 30%", near(low[x, between], c.sky, 0.02f))
    }

    @Test fun `a refill throws droplets that are drawn over everything`() {
        val c = colors()
        show(c, running(0.5f))
        settle(30)
        state = state.copy(endTimeMillis = state.endTimeMillis + 480_000L, totalMillis = state.totalMillis + 480_000L) // +8 min: ADD
        rule.pump(2)
        val px = capture()
        var live = 0
        var drawn = 0
        for (i in 0 until PondParticles.DROPS) if (sim.particles.dropLife[i] > 0f) {
            live++
            // Sample the drop's body away from its white highlight (up and left of center); the
            // exact center sits on the highlight's antialiased edge, and drops can overlap.
            val r = sim.particles.dropR[i]
            val body = listOf(0f to 0.45f, 0.45f to 0f, 0.35f to 0.35f, 0f to 0f).any { (dx, dy) ->
                val x = (sim.particles.dropX[i] + dx * r).toInt()
                val y = (sim.particles.dropY[i] + dy * r).toInt()
                x in 0 until px.width && y in 0 until px.height && near(px[x, y], c.droplet, 0.05f)
            }
            if (body) drawn++
        }
        assertEquals(10, live)
        assertTrue("$drawn of $live drops painted in the droplet color", drawn >= live / 2)
    }

    @Test fun `ringing, the pond is drained and bubbles rise behind the duck`() {
        val c = colors()
        show(c, ringing())
        val px = settle(50)
        val x = clearColumn()
        assertTrue("sky at mid-height", near(px[x, px.height / 2], c.sky, 0.02f))
        assertTrue("air bubbles", (0 until PondParticles.BUBBLES).any { sim.particles.bubLife[it] > 0f && sim.particles.bubAir[it] })
        assertTrue("the duck sits on the floor", sim.duckY + DuckPainter.BOTTOM * sim.duckScale > sim.height - 30 * density)
        assertTrue(yellowNearDuck(px) > 100)
    }

    @Test fun `paused, the pond draws with sleepy Zs, a drowsy duck and rising bubbles`() {
        val c = colors()
        show(c, paused(0.6f))
        capture()
        rule.pump(80) // the first Z comes after a second
        val px = capture()
        assertTrue("a Z", (0 until PondParticles.ZS).any { sim.particles.zAge[it] > 0f && sim.particles.zAge[it] < 2.6f })
        assertTrue("drowsy: ${sim.pose.eyeOpen}", sim.pose.eyeOpen < 0.5f)
        assertTrue(yellowNearDuck(px) > 100)
        // Ambient bubbles rise through the deep water too.
        rule.pump(200)
        capture()
        assertTrue("water bubbles", (0 until PondParticles.BUBBLES).any { sim.particles.bubLife[it] > 0f && !sim.particles.bubAir[it] })
    }

    @Test fun `high contrast draws an ink outline around the duck and a line on the surface`() {
        val plain = colors()
        show(plain, TimerState(0L, 0L, 0L, 0L))
        val a = settle(10)
        val inkPlain = inkNearDuck(a)

        val hc = colors(hc = true)
        pondColors = hc
        rule.pump(2)
        val b = capture()
        val inkHc = inkNearDuck(b)
        assertTrue("outline ink: plain $inkPlain vs high contrast $inkHc", inkHc > inkPlain + 200)
        val x = clearColumn()
        val line = b[x, sim.surfaceAt(x.toFloat()).toInt()]
        assertTrue("surface line in the primary color: $line vs ${hc.surfaceLine}", near(line, hc.surfaceLine!!, 0.08f))
    }

    @Test fun `dark theme paints a dark sky over the water`() {
        val c = colors(dark = true)
        show(c, running(0.6f))
        val px = settle(20)
        val x = clearColumn()
        val surface = sim.surfaceAt(x.toFloat()).toInt()
        assertTrue("dark sky", near(px[x, surface - (90 * density).toInt()], c.sky, 0.02f))
        assertTrue("sky is dark", c.sky.red + c.sky.green + c.sky.blue < 0.6f)
        assertTrue("water below", dist(px[x, surface + (40 * density).toInt()], c.sky) > 0.15f)
    }

    /** Darkness of a dot center against the point halfway to the next dot on its row. */
    private fun dotContrast(px: PixelMap): List<Float> {
        val step = 7f * density
        val out = mutableListOf<Float>()
        var row = 0
        var y = step / 2f
        while (y < px.height) {
            val sx = sim.surfaceAt(0f).coerceAtLeast(sim.surfaceAt(sim.width))
            if (y > sx + 60f * density && y < sim.height - 90f * density) {
                var x = if (row % 2 == 0) step / 2f else step
                while (x + step < px.width) {
                    if (abs(x - sim.duckX) > 60f * density) {
                        val dot = px[x.toInt(), y.toInt()]
                        val gap = px[(x + step / 2f).toInt(), y.toInt()]
                        out += (gap.red + gap.green + gap.blue) - (dot.red + dot.green + dot.blue)
                    }
                    x += 3 * step
                }
            }
            y += 4 * step; row += 4
        }
        return out
    }

    @Test fun `London draws a dot-matrix on the water and a line along its surface`() {
        val mv = colors()
        show(mv, running(0.8f))
        val plain = dotContrast(settle(10))
        val london = colors(style = AppStyle.LONDON)
        pondColors = london
        rule.pump(1)
        val px = capture()
        val dots = dotContrast(px)
        assertTrue("sampled ${dots.size} dots", dots.size >= 20)
        val darker = dots.count { it > 0.12f }
        assertTrue("London: $darker of ${dots.size} dot centers darker than the gap", darker >= dots.size * 0.8f)
        assertTrue("Mountain View has no dots", plain.count { it > 0.12f } <= plain.size / 10)
        val x = clearColumn()
        val line = px[x, sim.surfaceAt(x.toFloat()).toInt()]
        assertTrue("surface line $line vs ${london.surfaceLine}", near(line, london.surfaceLine!!, 0.08f))
        // In the dark, the London dots are light instead.
        pondColors = colors(style = AppStyle.LONDON, dark = true)
        rule.pump(1)
        val darkDots = dotContrast(capture())
        assertTrue("dark London dots are lighter", darkDots.count { it < -0.06f } >= darkDots.size * 0.8f)
    }

    // ---- sensor, haptics, lifecycle ------------------------------------------------------

    private fun addAccelerometer(): SensorManager {
        val sm = rule.activity.getSystemService(SensorManager::class.java)
        shadowOf(sm).addSensor(accelerometer())
        return sm
    }

    private fun accelerometer(): Sensor = SensorBuilder.newBuilder().setType(Sensor.TYPE_ACCELEROMETER).build()

    private var sensorTime = 5_000_000_000L

    /** Holds the phone rolled by [roll] (positive = right side down) for [frames] frames. */
    private fun hold(sm: SensorManager, roll: Float, frames: Int) {
        val g = 9.81f
        repeat(frames / 6) {
            val e = SensorEventBuilder
                .newBuilder(accelerometer(), floatArrayOf(-g * sin(roll), g * cos(roll), 0f))
                .setTimestamp(sensorTime)
                .build()
            sensorTime += 100_000_000L
            shadowOf(sm).sendSensorEventToListeners(e)
            rule.pump(6)
        }
    }

    @Test fun `tilting the phone tilts the water, and a hard bump into the wall ticks`() {
        val sm = addAccelerometer()
        show(colors(), running(0.5f))
        settle(30)
        assertEquals("listening while resumed", 1, shadowOf(sm).listeners.size)
        assertNotEquals(HapticFeedbackConstants.CLOCK_TICK, shadowOf(view).lastHapticFeedbackPerformed())
        // An accuracy report carries no reading: the water ignores it.
        val level = sim.tilt
        shadowOf(sm).listeners.single().onAccuracyChanged(null, SensorManager.SENSOR_STATUS_ACCURACY_HIGH)
        rule.pump(1)
        assertEquals(level, sim.tilt, 1e-4f)
        hold(sm, -0.35f, 300)
        assertTrue("left side down: the surface rises on the left: ${sim.tilt}", sim.tilt > 0.15f)
        assertTrue("duck at the left: ${sim.duckX}", sim.duckX < sim.width * 0.25f)
        val before = sim.hardBumps
        hold(sm, 0.35f, 240)
        assertTrue("water tilts the other way: ${sim.tilt}", sim.tilt < -0.15f)
        assertTrue("a hard bump: ${sim.hardBumps - before}", sim.hardBumps > before)
        assertEquals(HapticFeedbackConstants.CLOCK_TICK, shadowOf(view).lastHapticFeedbackPerformed())
    }

    @Test fun `paused screen lets go of the sensor and the water levels out`() {
        val sm = addAccelerometer()
        show(colors(), running(0.5f))
        settle(10)
        hold(sm, 0.3f, 120)
        assertTrue("right side down: ${sim.tilt}", sim.tilt < -0.15f)
        rule.activityRule.scenario.moveToState(Lifecycle.State.STARTED) // onPause
        assertTrue("unregistered on pause", shadowOf(sm).listeners.isEmpty())
        rule.pump(300)
        assertEquals("eases back to level", 0f, sim.tilt, 0.01f)
        rule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        assertEquals("listening again on resume", 1, shadowOf(sm).listeners.size)
    }

    @Test fun `turning reduced motion on drops the sensor and stills the pond`() {
        val sm = addAccelerometer()
        show(colors(), paused(0.5f))
        settle(20)
        assertEquals(1, shadowOf(sm).listeners.size)
        reduced = true
        rule.pump(2)
        assertTrue("no sensor under reduced motion", shadowOf(sm).listeners.isEmpty())
        assertTrue(sim.reduced)
        // The readout is laid out somewhere off the water, so it moves nothing.
        sim.setReadoutBounds(0f, -5_000f, 10f, 10f)
        stillPolls(1)
        val x = sim.duckX
        val tick = sim.tick.longValue
        val a = capture()
        stillPolls(3)
        assertEquals("nothing moves", x, sim.duckX, 0f)
        assertEquals("no redraw while the picture is unchanged", tick, sim.tick.longValue)
        val b = capture()
        for (y in 0 until a.height step 37) for (xx in 0 until a.width step 29) assertEquals(a[xx, y], b[xx, y])
        assertEquals(0.36f, sim.pose.eyeOpen, 0f)
        // A state change is a new still picture.
        val mean = sim.meanY
        state = paused(0.2f)
        stillPolls(3)
        assertEquals(tick + 1, sim.tick.longValue)
        assertTrue("lower water: $mean -> ${sim.meanY}", sim.meanY > mean + 100f)
    }

    @Test fun `starting under reduced motion never registers the sensor`() {
        val sm = addAccelerometer()
        show(colors(), ringing(), still = true)
        capture()
        stillPolls(3)
        assertTrue(shadowOf(sm).listeners.isEmpty())
        assertEquals(0f, sim.level, 0f)
        assertEquals(-7f, sim.pose.rotation, 0f)
    }

    /** The still pond re-derives its level every 200 ms: [n] polls' worth of test frames, with room. */
    private fun stillPolls(n: Int) = rule.pump(20 * n)

    @Test fun `closing the screen stops the frame loop`() {
        show(colors(), running(0.5f))
        settle(10)
        rule.activityRule.scenario.moveToState(Lifecycle.State.DESTROYED)
        val tick = sim.tick.longValue
        rule.pump(30)
        assertEquals("no more frames once the screen is gone", tick, sim.tick.longValue)
    }

    // ---- pond ink ------------------------------------------------------------------------

    private fun inkLayers(ink: PondSim) {
        rule.setContent {
            view = LocalView.current
            density = LocalDensity.current.density
            Column {
                Box(Modifier.size(100.dp).pondInk(ink, above = true).background(Color.Red))
                Box(Modifier.size(100.dp).pondInk(ink, above = false).background(Color.Blue))
            }
        }
        rule.pump(1)
    }

    @Test fun `pond ink shows the sky copy above the water and the water copy below it`() {
        val ink = PondSim().apply {
            density = 3f; width = 1080f; height = 1920f; headerBottom = 120f
            setState(running(0.5f))
            step(1f / 60f, now())
        }
        ink.setReadoutBounds(390f, ink.meanY - 150f, 300f, 300f) // the surface crosses its middle
        inkLayers(ink)
        val px = capture()
        val box = (100 * density).toInt()
        assertEquals(300, box)
        for (x in listOf(5, 80, 150, 220, 295)) {
            val line = (ink.surfaceAt(ink.readoutLeft + x) - ink.readoutTop).toInt()
            assertTrue("line inside the box at $x: $line", line in 40..260)
            assertEquals("sky copy above the water at $x", Color.Red, px[x, line - 15])
            assertNotEquals("sky copy clipped under the water at $x", Color.Red, px[x, line + 15])
            assertEquals("water copy under the water at $x", Color.Blue, px[x, box + line + 15])
            assertNotEquals("water copy clipped in the air at $x", Color.Blue, px[x, box + line - 15])
        }
    }

    @Test fun `before the pond has a readout or a step, only the sky copy draws`() {
        val ink = PondSim()
        inkLayers(ink)
        val px = capture()
        val box = (100 * density).toInt()
        for (y in 0 until box step 25) for (x in 0 until box step 25) {
            assertEquals(Color.Red, px[x, y])
            assertNotEquals(Color.Blue, px[x, box + y])
        }
    }
}
