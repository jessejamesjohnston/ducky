package com.merrimentlabs.ducky

import android.graphics.Bitmap
import com.merrimentlabs.ducky.ui.AppStyle
import com.merrimentlabs.ducky.ui.PRESET_SEEDS
import com.merrimentlabs.ducky.ui.Palette
import com.merrimentlabs.ducky.ui.pond.BubbleColors
import com.merrimentlabs.ducky.ui.pond.BubblePop
import com.merrimentlabs.ducky.ui.pond.PondMath
import com.merrimentlabs.ducky.ui.pond.DuckPainter
import com.merrimentlabs.ducky.ui.pond.EggHatch
import com.merrimentlabs.ducky.ui.pond.HopMood
import com.merrimentlabs.ducky.ui.pond.LilyHop
import com.merrimentlabs.ducky.ui.pond.PondColors
import com.merrimentlabs.ducky.ui.pond.StepFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The "Bubbles" and "Egg timer" widget pictures. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CountedScenesTest {

    private val density = 2.625f // a generic large phone (420 dpi), not any one device

    /** Time boxes of the Compact, Bar and Stacked layouts, plus large placed widgets. */
    private val boxes = listOf(52f to 48f, 90f to 48f, 130f to 72f, 180f to 58f, 200f to 54f, 180f to 112f, 320f to 214f, 430f to 190f)

    private fun colors(seed: Int = TimerStore.DEFAULT_SEED, style: AppStyle = AppStyle.MOUNTAIN_VIEW, dark: Boolean = false, hc: Boolean = false) =
        PondColors.from(seed, style, dark, hc,
            if (dark) Palette.dark(seed, style.monochrome, hc) else Palette.light(seed, style.monochrome, hc))

    private fun bubbles(f: StepFrame, w: Float = 320f, h: Float = 140f, c: PondColors = colors(), mono: Boolean = false) =
        BubblePop.render(f, c, mono, (w * density).toInt(), (h * density).toInt(), density, 24f * density)

    private fun eggs(f: StepFrame, w: Float = 320f, h: Float = 140f, c: PondColors = colors(), mono: Boolean = false) =
        EggHatch.render(f, c, mono, (w * density).toInt(), (h * density).toInt(), density, 24f * density)

    private fun frame(mood: HopMood, remaining: Long, count: Int) = StepFrame.of(mood, remaining, 120_000L, count)

    /** How many pixels differ from the first one: a blank render has none. */
    private fun Bitmap.variety(): Int {
        val first = getPixel(0, height - 1)
        var n = 0
        for (y in 0 until height step 3) for (x in 0 until width step 3) if (getPixel(x, y) != first) n++
        return n
    }

    @Test fun `bubbles stay in the band, beside the duck, at every widget size`() {
        // Every cap a timer can bring (a short timer gets fewer bubbles), at every size.
        for ((wd, hd) in boxes) for (cap in 1..8) {
            val l = BubblePop.layout(wd * density, hd * density, density, cap)
            val tag = "$wd x $hd, cap $cap"
            val full = BubblePop.layout(wd * density, hd * density, density).count
            assertTrue("$tag: 3 to 8 bubbles without a cap", full in 3..8)
            assertEquals("$tag: the cap or the width, whichever is less", minOf(cap, full), l.count)
            assertTrue("$tag: bubbles above the band top", l.bubbleY - l.bubbleR >= l.bandTop)
            assertTrue("$tag: last bubble inside", l.bubbleX.last() + l.bubbleR <= l.width)
            val duckRight = l.duckX + DuckPainter.RIGHT * l.duckScale
            assertTrue("$tag: first bubble clear of the duck", l.bubbleX.first() - l.bubbleR >= duckRight)
            val duckTop = l.duckWaterline + DuckPainter.TOP * l.duckScale
            assertTrue("$tag: duck head in the band", duckTop >= l.bandTop)
            assertTrue("$tag: duck inside", l.duckWaterline + DuckPainter.BOTTOM * l.duckScale <= l.height)
            assertEquals("$tag: same band as Lily pads", l.bandTop, hd * density - LilyHop.bandDp(hd) * density, 0.01f)
        }
    }

    @Test fun `a box bigger than the renderer draws is counted at the size it is drawn`() {
        // A 450 x 220 dp widget at density 4 has a 1720 px wide time box; the renderers draw at
        // most 1200 px and let the launcher scale up. The frame used to count the full width
        // (8) while the picture had room for fewer, so the last egg hatched 7.5 s early.
        val d = 4f
        val w = 1720
        val h = 576
        assertEquals(BubblePop.layout(1200f, 576f, d).count, BubblePop.widthCount(w, h, d))
        assertEquals(EggHatch.layout(1200f, 576f, d).count, EggHatch.widthCount(w, h, d))
        assertTrue(EggHatch.widthCount(w, h, d) < EggHatch.layout(w.toFloat(), h.toFloat(), d).count)
        // In range, nothing changes.
        assertEquals(BubblePop.layout(780f, 341f, density).count, BubblePop.widthCount(780, 341, density))
    }

    @Test fun `eggs and ducklings stay in the band at every widget size`() {
        for ((wd, hd) in boxes) for (cap in 1..8) {
            val l = EggHatch.layout(wd * density, hd * density, density, cap)
            val tag = "$wd x $hd, cap $cap"
            val full = EggHatch.layout(wd * density, hd * density, density).count
            assertTrue("$tag: 3 to 8 eggs without a cap", full in 3..8)
            assertEquals("$tag: the cap or the width, whichever is less", minOf(cap, full), l.count)
            assertTrue("$tag: eggs inside", l.eggX.first() - l.eggW >= 0f && l.eggX.last() + l.eggW / 2f <= l.width)
            val ducklingTop = l.ducklingWaterline + DuckPainter.TOP * l.ducklingScale
            assertTrue("$tag: duckling head $ducklingTop in the band ${l.bandTop}", ducklingTop >= l.bandTop)
            assertTrue("$tag: egg top in the band", l.eggBase - l.eggH >= l.bandTop)
            assertTrue("$tag: nest inside", l.nestBottom <= l.height)
            l.mamaX?.let { x ->
                assertFalse("$tag: no grown duck in a strip", l.strip)
                assertTrue("$tag: duck head in the band", l.mamaWaterline + DuckPainter.TOP * l.mamaScale >= l.bandTop)
                assertTrue("$tag: duck left of the eggs", x + DuckPainter.RIGHT * l.mamaScale <= l.eggX.first() - l.eggW / 2f)
            }
            assertEquals("$tag: same band as Lily pads", l.bandTop, hd * density - LilyHop.bandDp(hd) * density, 0.01f)
        }
    }

    @Test fun `two renders inside one slice are the same picture`() {
        val count = BubblePop.layout(320f * density, 140f * density, density).count
        val a = frame(HopMood.RUNNING, 118_000L, count)
        val b = frame(HopMood.RUNNING, 106_000L, count)
        assertEquals(a, b)
        assertTrue("bubbles", bubbles(a).sameAs(bubbles(b)))
        val e = EggHatch.layout(320f * density, 140f * density, density).count
        assertTrue("eggs", eggs(frame(HopMood.RUNNING, 118_000L, e)).sameAs(eggs(frame(HopMood.RUNNING, 112_000L, e))))
    }

    @Test fun `each pop, hatch, the last minute and the ring draw a different picture`() {
        val count = 6
        val pictures = listOf(
            frame(HopMood.IDLE, 0L, count),
            frame(HopMood.RUNNING, 119_000L, count), // all left, running
            frame(HopMood.RUNNING, 99_000L, count),  // one gone
            frame(HopMood.RUNNING, 79_000L, count),  // two gone
            frame(HopMood.RUNNING, 59_000L, count),  // last minute
            frame(HopMood.PAUSED, 59_000L, count),   // asleep
            frame(HopMood.DONE, 0L, count),          // ringing
        )
        for (render in listOf(::bubbles, ::eggs)) {
            val bitmaps = pictures.map { render(it, 320f, 140f, colors(), false) }
            for (i in bitmaps.indices) for (j in i + 1 until bitmaps.size) {
                assertFalse("${pictures[i]} vs ${pictures[j]}", bitmaps[i].sameAs(bitmaps[j]))
            }
        }
    }

    @Test fun `renders every state at every size without throwing`() {
        for ((wd, hd) in boxes + listOf(0.1f to 0.1f, 13f to 13f, 900f to 900f)) {
            for (mood in HopMood.entries) for (mono in listOf(false, true)) for (remaining in listOf(110_000L, 30_000L)) {
                val bl = BubblePop.layout(wd * density, hd * density, density).count
                val b = bubbles(frame(mood, remaining, bl), wd, hd, colors(), mono)
                assertEquals((wd * density).toInt().coerceIn(BubblePop.MIN_PX, 1200), b.width)
                assertEquals((hd * density).toInt().coerceIn(BubblePop.MIN_PX, 1200), b.height)
                val el = EggHatch.layout(wd * density, hd * density, density).count
                val e = eggs(frame(mood, remaining, el), wd, hd, colors(), mono)
                assertEquals((hd * density).toInt().coerceIn(EggHatch.MIN_PX, 1200), e.height)
            }
        }
    }

    @Test fun `bubble rims stand out from the sky for every accent, theme, contrast and style`() {
        // Non-text graphics need 3:1 (WCAG 1.4.11); the rim is what makes a bubble countable.
        for (seed in PRESET_SEEDS) for (dark in listOf(false, true)) for (hc in listOf(false, true))
            for (style in AppStyle.entries) {
                val c = colors(seed, style, dark, hc)
                val k = BubbleColors.of(c, style.monochrome)
                val tag = "seed=%08x dark=$dark hc=$hc style=${style.key}".format(seed)
                assertTrue("$tag rim ${PondMath.contrast(k.rim, c.sky)}", PondMath.contrast(k.rim, c.sky) >= 3f)
                assertTrue("$tag gold rim ${PondMath.contrast(k.goldRim, c.sky)}", PondMath.contrast(k.goldRim, c.sky) >= 3f)
            }
    }

    @Test fun `every accent, theme, contrast and style draws a picture`() {
        for (seed in PRESET_SEEDS) for (dark in listOf(false, true)) for (hc in listOf(false, true))
            for (style in AppStyle.entries) {
                val c = colors(seed, style, dark, hc)
                val tag = "seed=%08x dark=$dark hc=$hc style=${style.key}".format(seed)
                for (f in listOf(frame(HopMood.RUNNING, 50_000L, 6), frame(HopMood.DONE, 0L, 6))) {
                    assertTrue("$tag bubbles", bubbles(f, 200f, 72f, c, style.monochrome).variety() > 200)
                    assertTrue("$tag eggs", eggs(f, 200f, 72f, c, style.monochrome).variety() > 200)
                }
            }
    }
}
