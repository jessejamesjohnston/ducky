package com.merrimentlabs.ducky

import com.merrimentlabs.ducky.ui.pond.PondSim
import com.merrimentlabs.ducky.ui.pond.PondVisitors
import com.merrimentlabs.ducky.ui.pond.VisitorKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * The occasional underwater visitor: only on timers longer than five minutes, only while one
 * runs (a paused one lets a visitor finish), never under reduced motion; under the surface and
 * clear of the readout; gone once across. Plain JVM, frozen clock, seeded visitor randomness.
 */
class PondVisitorsTest {

    private val now = 10_000_000L
    private val dt = 1f / 60f

    private fun running(totalMillis: Long, fill: Float = 0.6f) =
        TimerState(now + (totalMillis * fill).toLong(), totalMillis, 0L, 0L)

    private fun pond(state: TimerState, seed: Int = 1, reduced: Boolean = false) =
        PondSim(visitorRng = Random(seed)).apply {
            density = 2.5f
            width = 1000f // 400 dp: a phone in portrait
            height = 2000f
            headerBottom = 220f
            this.reduced = reduced
            setState(state)
        }

    /** Frames until a visitor appears (at most [maxSeconds] of on-screen time); null = none. */
    private fun PondSim.firstVisitor(maxSeconds: Float = 200f): Float? {
        var t = 0f
        while (t < maxSeconds) {
            step(dt, now)
            t += dt
            if (visitors.kind != null) return t
        }
        return null
    }

    private val twentyMinutes = 1_200_000L
    private val fiveMinutes = PondVisitors.MIN_TOTAL_MILLIS

    @Test fun `a timer of exactly five minutes, or less, never gets a visitor`() {
        for (total in listOf(60_000L, 240_000L, fiveMinutes)) {
            for (seed in 1..5) {
                val sim = pond(running(total), seed)
                assertNull("total $total, seed $seed", sim.firstVisitor())
                assertTrue(sim.visitors.nextAt.isNaN())
            }
        }
    }

    @Test fun `a long timer gets one, soon or within the gap`() {
        val times = (1..12).map { seed ->
            pond(running(fiveMinutes + 1_000L), seed).firstVisitor()
                ?: error("seed $seed never got a visitor")
        }
        assertTrue("never before the soonest: $times", times.all { it >= PondVisitors.SOON_MIN - dt })
        assertTrue("never after the longest gap: $times", times.all { it <= PondVisitors.GAP_MAX + dt })
        assertTrue("some come soon: $times", times.any { it <= PondVisitors.SOON_MAX + dt })
        assertTrue("some wait for the gap: $times", times.any { it >= PondVisitors.GAP_MIN - dt })
    }

    @Test fun `coming back on screen is a fresh chance`() {
        // Seeds whose first draw waits the long gap: a new screen session redraws.
        var redrawn = 0
        for (seed in 1..20) {
            val sim = pond(running(twentyMinutes), seed)
            sim.step(dt, now)
            val first = sim.visitors.nextAt
            sim.onShown()
            sim.step(dt, now)
            if (sim.visitors.nextAt != first) redrawn++
        }
        assertEquals("every session draws again", 20, redrawn)
    }

    @Test fun `none under reduced motion, and one swimming is cleared`() {
        assertNull(pond(running(twentyMinutes), reduced = true).firstVisitor())
        val sim = pond(running(twentyMinutes))
        assertTrue(sim.firstVisitor() != null)
        sim.reduced = true
        sim.step(dt, now)
        assertNull(sim.visitors.kind)
    }

    @Test fun `idle or ringing ponds get none, and a stopped timer fades its visitor out`() {
        assertNull(pond(TimerState(0L, 0L, 0L, 0L)).firstVisitor())
        assertNull(pond(TimerState(0L, twentyMinutes, now - 1_000L, 0L)).firstVisitor())
        val sim = pond(running(twentyMinutes))
        assertTrue(sim.firstVisitor() != null)
        repeat(60) { sim.step(dt, now) } // fully faded in
        assertEquals(1f, sim.visitors.alpha, 1e-3f)
        sim.setState(TimerState(0L, twentyMinutes, 0L, 0L)) // canceled
        repeat((PondVisitors.FADE / dt).toInt() + 2) { sim.step(dt, now) }
        assertNull("faded and gone", sim.visitors.kind)
        assertNull("and no new one while idle", sim.firstVisitor())
    }

    @Test fun `a paused timer lets the visitor finish but sends no new one`() {
        val sim = pond(running(twentyMinutes))
        assertTrue(sim.firstVisitor() != null)
        sim.setState(TimerState(0L, twentyMinutes, 0L, twentyMinutes / 2))
        val x0 = sim.visitors.x
        repeat(30) { sim.step(dt, now) }
        assertNotEquals("still swimming", x0, sim.visitors.x)
        assertEquals("not fading", 1f, sim.visitors.alpha, 0.51f)
        repeat(60 * 60) { sim.step(dt, now) } // long enough to cross any pond
        assertNull(sim.visitors.kind)
        assertNull("none while paused", sim.firstVisitor())
    }

    @Test fun `it swims across under the surface, clear of the readout, and leaves`() {
        for (seed in 1..8) {
            val sim = pond(running(twentyMinutes), seed)
            sim.setReadoutBounds(300f, 1000f, 400f, 250f) // origin 0: pond-local 1000..1250
            assertTrue(sim.firstVisitor() != null)
            val v = sim.visitors
            val k = v.kind!!
            val startX = v.x
            var frames = 0
            while (v.kind != null && frames < 60 * 60) {
                val halfH = k.heightUnits * v.scale / 2f
                val top = v.y - halfH
                for (x in listOf(v.x - k.widthUnits * v.scale / 2f, v.x, v.x + k.widthUnits * v.scale / 2f)) {
                    assertTrue("seed $seed: top $top under the surface ${sim.surfaceAt(x)}", top > sim.surfaceAt(x))
                }
                val bottom = v.y + halfH
                assertTrue("seed $seed: clear of the readout ($top..$bottom)", bottom < 1000f - 3f * 2.5f || top > 1250f + 3f * 2.5f)
                assertTrue("seed $seed: above the floor", bottom < sim.floorY)
                sim.step(dt, now)
                frames++
            }
            assertNull("seed $seed crossed and left", v.kind)
            assertTrue("seed $seed came from an edge", startX < 0f || startX > sim.width)
            assertFalse("seed $seed: the next one is booked", v.nextAt.isNaN())
            assertTrue(v.nextAt - sim.clock in PondVisitors.GAP_MIN - 1f..PondVisitors.GAP_MAX + 1f)
        }
    }

    @Test fun `no room, no visitor, and a nearly empty pond looks again later`() {
        val sim = pond(running(twentyMinutes, fill = 0.02f))
        assertNull(sim.firstVisitor(40f))
        assertFalse("still scheduled", sim.visitors.nextAt.isNaN())
    }

    @Test fun `all three come by, both ways, never the same one twice in a row`() {
        val kinds = mutableSetOf<VisitorKind>()
        val dirs = mutableSetOf<Float>()
        for (seed in 1..6) {
            val sim = pond(running(twentyMinutes), seed)
            var last: VisitorKind? = null
            var seen: VisitorKind? = null
            var t = 0f
            while (t < 900f) {
                sim.step(dt, now)
                t += dt
                val k = sim.visitors.kind
                if (k != null && k != seen) {
                    if (last != null) assertNotEquals("seed $seed repeated $k", last, k)
                    last = k
                    kinds += k
                    dirs += sim.visitors.dir
                }
                seen = k
            }
        }
        assertEquals(VisitorKind.entries.toSet(), kinds)
        assertEquals(setOf(1f, -1f), dirs)
    }

    @Test fun `a narrow pond shrinks the visitor to fit`() {
        val sim = pond(running(twentyMinutes)).apply { width = 300f }
        assertTrue(sim.firstVisitor() != null)
        val v = sim.visitors
        assertTrue(v.kind!!.widthUnits * v.scale <= 300f * 0.45f + 0.01f)
    }
}
