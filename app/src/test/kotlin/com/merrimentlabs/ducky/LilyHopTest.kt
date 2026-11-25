package com.merrimentlabs.ducky

import com.merrimentlabs.ducky.ui.AppStyle
import com.merrimentlabs.ducky.ui.Palette
import com.merrimentlabs.ducky.ui.pond.DuckPainter
import com.merrimentlabs.ducky.ui.pond.HopMood
import com.merrimentlabs.ducky.ui.pond.LilyHop
import com.merrimentlabs.ducky.ui.pond.PondColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The "Lily pads" widget's picture. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LilyHopTest {

    private val density = 2.625f // a generic large phone (420 dpi), not any one device

    /** Time boxes of the Compact, Bar and Stacked layouts, plus a large placed widget. */
    private val boxes = listOf(52f to 48f, 90f to 48f, 130f to 72f, 180f to 58f, 200f to 54f, 180f to 112f, 320f to 214f, 430f to 190f)

    @Test fun `the duck starts on the first pad and reaches the last just before the end`() {
        assertEquals(0, LilyHop.hopIndex(0f, 6))
        assertEquals(0, LilyHop.hopIndex(0.16f, 6))
        assertEquals(1, LilyHop.hopIndex(1f / 6f + 1e-4f, 6))
        assertEquals(5, LilyHop.hopIndex(0.999f, 6))
        assertEquals(5, LilyHop.hopIndex(1f, 6))
        assertEquals(0, LilyHop.hopIndex(-0.2f, 6))
        var last = 0
        for (i in 0..100) {
            val k = LilyHop.hopIndex(i / 100f, 8)
            assertTrue("never hops backwards", k >= last)
            last = k
        }
        assertEquals(7, last)
    }

    @Test fun `the clock keeps at least 30 dp above the pond band`() {
        for (h in listOf(40f, 48f, 60f, 72f, 96f, 140f, 214f, 400f)) {
            assertTrue("box $h", h - LilyHop.bandDp(h) >= 30f - 1e-3f || LilyHop.bandDp(h) == 0f)
        }
    }

    @Test fun `pads, shore and duck stay inside every widget box`() {
        for ((wd, hd) in boxes) for (maxPads in 1..8) {
            val l = LilyHop.layout(wd * density, hd * density, density, maxPads)
            val fit = LilyHop.layout(wd * density, hd * density, density).padCount
            assertTrue("$wd x $hd: 3 to 8 pads fit", fit in 3..8)
            assertEquals("$wd x $hd, at most $maxPads", minOf(fit, maxPads), l.padCount)
            assertTrue("$wd x $hd: first pad inside", l.padX.first() - l.padW / 2f >= 0f)
            assertTrue("$wd x $hd: pads left of the shore", l.padX.last() + l.padW / 2f <= l.shoreX + 1f)
            assertTrue("$wd x $hd: pads in the band", l.padY - l.padH / 2f >= l.bandTop && l.padY + l.padH <= l.height)
            val duckBottom = l.padY + l.padH * 0.12f
            val duckTop = duckBottom - (DuckPainter.BOTTOM - DuckPainter.TOP) * l.duckScale
            assertTrue("$wd x $hd: duck top $duckTop inside", duckTop >= 0f)
            if (!l.strip) assertTrue("$wd x $hd: duck head stays in the band", duckTop >= l.bandTop - 2f * density)
        }
    }

    /**
     * Every render of a run, the way the widget gets them (as in StepFrameTest): one at the
     * start, then one per display tick of total / 90 (3 to 15 s), never sooner than 5 s, each
     * landing up to 0.8 s late. Returns the pad at each render, then the shore (the pad count)
     * for the ring. The fraction is computed as the widget does, in floats.
     */
    private fun run(total: Long, pads: Int, lateMs: (Int) -> Long): List<Int> {
        val interval = maxOf(5_000L, (total / 90).coerceIn(3_000L, 15_000L))
        val out = mutableListOf<Int>()
        var t = 0L
        var k = 0
        while (t < total) {
            val fraction = (total - t).toFloat() / total
            out += LilyHop.hopIndex(1f - fraction, pads)
            k++
            t = k * interval + lateMs(k)
        }
        out += pads
        return out
    }

    private val totals = listOf(
        5_000L, 10_000L, 13_999L, 14_000L, 20_000L, 25_000L, 30_000L, 35_000L, 45_000L, 55_000L,
        56_000L, 59_000L, 60_000L, 90_000L, 120_000L, 300_000L, 600_000L, 1_800_000L, 3_600_000L,
    )

    @Test fun `the duck never hops two pads between renders, at any tick timing`() {
        val lateness = listOf<(Int) -> Long>({ 0L }, { 800L }, { if (it % 2 == 0) 800L else 0L }, { (it * 137L) % 801L })
        for ((wd, hd) in boxes) for (total in totals) {
            val pads = LilyHop.padCountFor((wd * density).toInt(), (hd * density).toInt(), density, total)
            for (late in lateness) {
                val seq = run(total, pads, late)
                for ((a, b) in seq.zipWithNext()) {
                    assertTrue("$wd x $hd, ${total / 1000.0} s: $seq", b - a in 0..1)
                }
                assertEquals("starts on the first pad", 0, seq.first())
                assertEquals("ends on the shore", pads, seq.last())
            }
        }
    }

    @Test fun `a short timer gets fewer pads, a minute or longer gets every pad that fits`() {
        for ((wd, hd) in boxes) {
            val w = (wd * density).toInt()
            val h = (hd * density).toInt()
            val fit = LilyHop.layout(w.toFloat(), h.toFloat(), density).padCount
            assertEquals(1, LilyHop.padCountFor(w, h, density, 10_000L))
            assertEquals(minOf(fit, 4), LilyHop.padCountFor(w, h, density, 30_000L))
            // 56 s and longer: unchanged from before the cap (8 pads at most fit).
            for (total in listOf(56_000L, 60_000L, 3_600_000L)) assertEquals(fit, LilyHop.padCountFor(w, h, density, total))
            // No timer (idle, Test alarm): every pad.
            assertEquals(fit, LilyHop.padCountFor(w, h, density, 0L))
        }
        // The 30 s case from the bug: an 8-pad widget.
        val wide = LilyHop.padCountFor((430f * density).toInt(), (190f * density).toInt(), density, 30_000L)
        assertEquals(4, wide)
    }

    @Test fun `renders every mood at every size without throwing`() {
        val colors = PondColors.from(
            TimerStore.DEFAULT_SEED, AppStyle.MOUNTAIN_VIEW, dark = false, highContrast = false,
            scheme = Palette.light(TimerStore.DEFAULT_SEED, false, false),
        )
        for ((wd, hd) in boxes + listOf(0.1f to 0.1f, 900f to 900f)) {
            for (mood in HopMood.entries) {
                for (mono in listOf(false, true)) {
                    for (maxPads in listOf(1, 2, 8)) {
                    val bmp = LilyHop.render(
                        mood, progress = 0.55f, colors = colors, mono = mono,
                        widthPx = (wd * density).toInt(), heightPx = (hd * density).toInt(),
                        density = density, cornerPx = 24f, maxPads = maxPads,
                    )
                    assertEquals((wd * density).toInt().coerceIn(LilyHop.MIN_PX, 1200), bmp.width)
                    assertEquals((hd * density).toInt().coerceIn(LilyHop.MIN_PX, 1200), bmp.height)
                    }
                }
            }
        }
    }
}
