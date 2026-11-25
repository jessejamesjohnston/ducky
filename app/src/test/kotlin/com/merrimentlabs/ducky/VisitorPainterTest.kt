package com.merrimentlabs.ducky

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.merrimentlabs.ducky.ui.pond.PondSim
import com.merrimentlabs.ducky.ui.pond.VisitorColors
import com.merrimentlabs.ducky.ui.pond.VisitorKind
import com.merrimentlabs.ducky.ui.pond.VisitorPainter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs
import kotlin.random.Random

/**
 * Each visitor paints its own colors where the sim says it is, facing its way, and nothing
 * once it has gone. Also the two-pane controls' growth with the pane height.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VisitorPainterTest {

    private val now = 10_000_000L
    private val dt = 1f / 60f

    /** A sim with a visitor of [kind] fully faded in, mid-pond. */
    private fun simWith(kind: VisitorKind): PondSim {
        for (seed in 1..200) {
            val sim = PondSim(visitorRng = Random(seed)).apply {
                density = 2.5f; width = 1000f; height = 2000f; headerBottom = 220f
                setState(TimerState(now + 720_000L, 1_200_000L, 0L, 0L))
            }
            var t = 0f
            while (t < 200f && sim.visitors.kind == null) { sim.step(dt, now); t += dt }
            if (sim.visitors.kind != kind) continue
            // Swim on until it is well inside the pond and fully faded in.
            while (sim.visitors.kind != null && (sim.visitors.x < 350f || sim.visitors.x > 650f)) sim.step(dt, now)
            if (sim.visitors.kind == kind && sim.visitors.alpha >= 1f) return sim
        }
        error("no seed brought a $kind")
    }

    private fun render(sim: PondSim) = ImageBitmap(1000, 2000).also { img ->
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(img), Size(1000f, 2000f)) {
            with(VisitorPainter()) { drawVisitor(sim.visitors) }
        }
    }.toPixelMap()

    private fun Color.near(o: Color, tol: Float = 0.12f) =
        abs(red - o.red) < tol && abs(green - o.green) < tol && abs(blue - o.blue) < tol

    private fun countNear(sim: PondSim, color: Color): Int {
        val px = render(sim)
        val v = sim.visitors
        val k = v.kind!!
        val hw = (k.widthUnits * v.scale / 2f).toInt()
        val hh = (k.heightUnits * v.scale / 2f).toInt()
        var n = 0
        for (y in (v.y.toInt() - hh).coerceAtLeast(0)..(v.y.toInt() + hh).coerceAtMost(1999) step 2) {
            for (x in (v.x.toInt() - hw).coerceAtLeast(0)..(v.x.toInt() + hw).coerceAtMost(999) step 2) {
                if (px[x, y].near(color)) n++
            }
        }
        return n
    }

    @Test fun `the diver is a dark suit with a yellow tank`() {
        val sim = simWith(VisitorKind.DIVER)
        assertTrue(countNear(sim, VisitorColors.SUIT) > 200)
        assertTrue(countNear(sim, VisitorColors.TANK) > 50)
    }

    @Test fun `the submarine is a yellow hull with portholes`() {
        val sim = simWith(VisitorKind.SUBMARINE)
        assertTrue(countNear(sim, VisitorColors.HULL) > 300)
        assertTrue(countNear(sim, VisitorColors.GLASS) > 20)
    }

    @Test fun `the monster is green`() {
        val sim = simWith(VisitorKind.NESSIE)
        assertTrue(countNear(sim, VisitorColors.NESSIE) > 300)
    }

    @Test fun `it faces the way it swims`() {
        val sim = simWith(VisitorKind.NESSIE)
        val v = sim.visitors
        // The head sits ahead of center: more green in the leading third than the trailing one.
        val px = render(sim)
        val third = (VisitorKind.NESSIE.widthUnits * v.scale / 3f).toInt()
        fun greenIn(x0: Int, x1: Int): Int {
            var n = 0
            for (y in (v.y - 60f * v.scale / 2f).toInt()..(v.y + 60f * v.scale / 2f).toInt() step 2)
                for (x in x0.coerceIn(0, 999)..x1.coerceIn(0, 999) step 2) if (px[x, y].near(VisitorColors.NESSIE)) n++
            return n
        }
        val ahead = if (v.dir > 0f) greenIn(v.x.toInt() + third / 2, v.x.toInt() + 3 * third / 2)
            else greenIn(v.x.toInt() - 3 * third / 2, v.x.toInt() - third / 2)
        val behindTop = if (v.dir > 0f) (v.y - 25f * v.scale).toInt() else (v.y - 25f * v.scale).toInt()
        // The neck and head rise ahead: green above the body's top line, on the leading side only.
        var leadHigh = 0
        var trailHigh = 0
        for (x in 0 until 1000 step 2) {
            val y = behindTop
            if (y in 0 until 2000 && px[x, y].near(VisitorColors.NESSIE)) {
                if ((x - v.x) * v.dir > 0f) leadHigh++ else trailHigh++
            }
        }
        assertTrue("green ahead: $ahead", ahead > 50)
        assertTrue("head up ahead ($leadHigh) not behind ($trailHigh)", leadHigh > trailHigh)
    }

    @Test fun `nothing is drawn without a visitor`() {
        val sim = PondSim(visitorRng = Random(1)).apply { density = 2.5f; width = 1000f; height = 2000f }
        val px = render(sim)
        for (y in 0 until 2000 step 50) for (x in 0 until 1000 step 50) assertEquals(0f, px[x, y].alpha, 0f)
    }

    @Test fun `two-pane controls grow only on tall panes`() {
        assertEquals(0f, controlsGrowth(400.dp), 0f) // a phone in landscape
        assertEquals(0f, controlsGrowth(GROW_FROM), 0f)
        assertEquals(0.5f, controlsGrowth((GROW_FROM + GROW_TO) / 2f), 1e-4f)
        assertEquals(1f, controlsGrowth(GROW_TO), 0f)
        assertEquals(1f, controlsGrowth(1400.dp), 0f)
    }

    @Test fun `stacked controls grow only on wide windows`() {
        assertEquals(0f, stackedGrowth(411.dp), 0f) // a phone held upright, a foldable's cover screen
        assertEquals(0f, stackedGrowth(STACKED_GROW_FROM), 0f)
        assertEquals(0.5f, stackedGrowth((STACKED_GROW_FROM + STACKED_GROW_TO) / 2f), 1e-4f)
        assertEquals(1f, stackedGrowth(859.dp), 0f) // an unfolded foldable held upright
    }
}
