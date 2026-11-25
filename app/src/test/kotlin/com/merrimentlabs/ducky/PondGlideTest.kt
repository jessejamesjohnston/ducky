package com.merrimentlabs.ducky

import com.merrimentlabs.ducky.ui.pond.DuckPainter
import com.merrimentlabs.ducky.ui.pond.PondParticles
import com.merrimentlabs.ducky.ui.pond.PondSim
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The duck on the pond screen reacting to tilt (PondSim with DuckGlide): it glides toward the
 * low side, bumps the wall, settles, stays on the water and out of the readout, and does
 * nothing at all under reduced motion.
 */
class PondGlideTest {

    private val now = 10_000_000L
    /** Half a 20-minute timer left, at a frozen clock: the level stays at 0.5. */
    private val halfway = TimerState(now + 600_000L, 1_200_000L, 0L, 0L)

    private fun pond(reduced: Boolean = false) = PondSim().apply {
        density = 2.5f
        width = 1000f // 400 dp: a phone in portrait
        height = 2000f
        headerBottom = 220f
        this.reduced = reduced
        setState(halfway)
    }

    @Test fun `a tilted nearly full pond on a wide window stays out of the header`() {
        // A tablet in portrait (800 dp) on a stand, rolled well past the dead zone, 95% full.
        val nearlyFull = TimerState(now + 1_140_000L, 1_200_000L, 0L, 0L)
        val p = PondSim().apply {
            density = 2f; width = 1600f; height = 2200f; headerBottom = 180f
            setState(nearlyFull)
        }
        var highest = Float.MAX_VALUE
        var frames = 0
        p.run(4f, 0.4f) {
            frames++
            // After the tilt has settled: the surface's highest point, waves aside.
            if (frames > 120) highest = minOf(highest, (0..1600 step 40).minOf { x -> surfaceAt(x.toFloat()) - frontWave(x.toFloat()) })
        }
        assertTrue("tilted: ${p.tilt}", kotlin.math.abs(p.tilt) > 0.1f)
        assertTrue("high side ${highest} stays at or below the full line ${p.fullY}", highest >= p.fullY - 1f)
        assertTrue("the duck's top stays below the header", p.duckY + DuckPainter.TOP * p.duckScale > p.headerBottom)
    }

    /** Phone rolled by [roll] radians (positive = right side down), else upright. */
    private fun PondSim.hold(roll: Float, dt: Float) {
        val g = 9.81f
        onAccelerometer(-g * kotlin.math.sin(roll), g * kotlin.math.cos(roll), 0f, 0, dt)
    }

    /** Runs [seconds] at 60 fps holding [roll]; [each] sees every frame. */
    private fun PondSim.run(seconds: Float, roll: Float, each: PondSim.() -> Unit = {}) {
        val dt = 1f / 60f
        repeat((seconds * 60).toInt()) {
            hold(roll, dt)
            step(dt, now)
            each()
        }
    }

    private val PondSim.halfWidth get() = maxOf(-DuckPainter.LEFT, DuckPainter.RIGHT) * duckScale

    @Test fun `tipped right, the duck glides right, faces right and bumps the wall`() {
        val sim = pond()
        sim.run(1f, 0f)
        val start = sim.duckX
        sim.run(5f, 0.3f) {
            assertTrue("inside the pond", duckX - halfWidth >= 0f && duckX + halfWidth <= width)
        }
        assertTrue("moved right from $start to ${sim.duckX}", sim.duckX > start + 200f)
        assertTrue("at the right wall: ${sim.duckX}", sim.duckX + sim.halfWidth > sim.width - 2.5f * 12f)
        assertEquals(1f, sim.pose.facing)
        val wallRipple = (0 until PondParticles.RIPPLES).any { sim.particles.ripMax[it] > 0f && sim.particles.ripX[it] > sim.width * 0.8f }
        assertTrue("a ripple where it hit the wall", wallRipple)
    }

    @Test fun `tipped left, it goes left`() {
        val sim = pond()
        sim.run(1f, 0f)
        val start = sim.duckX
        sim.run(5f, -0.3f)
        assertTrue("moved left from $start to ${sim.duckX}", sim.duckX < start - 50f || sim.duckX - sim.halfWidth < 2.5f * 12f)
        assertEquals(-1f, sim.pose.facing)
    }

    @Test fun `levelled again, it settles where it is instead of swimming back`() {
        val sim = pond()
        sim.run(5f, 0.3f)
        val atWall = sim.duckX
        sim.run(6f, 0f)
        val settled = sim.duckX
        sim.run(1f, 0f)
        // What's left is its own slow wander (about 20 px/s at most), not the glide.
        assertTrue("still after 6 s: moved ${abs(sim.duckX - settled)} px in 1 s", abs(sim.duckX - settled) < 25f)
        assertTrue("stayed near the wall: $atWall -> ${sim.duckX}", sim.duckX > atWall - 60f)
    }

    @Test fun `a slight tilt in the hand is not a slide`() {
        // Against the same pond held level: the duck's own wander is the same in both.
        val level = pond()
        val tilted = pond()
        level.run(2f, 0f)
        tilted.run(2f, 0f)
        level.run(4f, 0f)
        tilted.run(4f, 0.025f)
        assertTrue("drifted ${tilted.duckX - level.duckX} px", abs(tilted.duckX - level.duckX) < 2f)
    }

    @Test fun `it rides the surface and never leaves the water`() {
        val sim = pond()
        sim.run(1f, 0f)
        for (roll in listOf(0.3f, -0.3f, 0.12f, -0.5f, 0f)) {
            sim.run(2.5f, roll) {
                // The waterline follows the surface with a little lag when the phone swings; the
                // duck's bottom never lifts out of the water, nor does it sink under.
                val draft = DuckPainter.BOTTOM * duckScale
                val gap = duckY - surfaceAt(duckX)
                assertTrue("lifted out by $gap px at roll $roll", gap > -draft)
                assertTrue("sank by $gap px at roll $roll", gap < 2f * draft)
                assertTrue(duckX - halfWidth >= 0f && duckX + halfWidth <= width)
            }
        }
    }

    /** The readout across the middle at the water's height, as on a phone (no room beside it). */
    private fun PondSim.readoutAtWater() {
        setReadoutBounds(300f, meanY - 120f, 400f, 200f)
    }

    private fun PondSim.bodyIntoReadout(): Float {
        val right = duckX + DuckPainter.RIGHT * duckScale
        val left = duckX + DuckPainter.LEFT * duckScale
        return minOf(right - 300f, 700f - left) // > 0: the body overlaps the digits' columns
    }

    @Test fun `level, it never rests behind the readout when the water is at its height`() {
        val sim = pond()
        sim.run(0.5f, 0f)
        sim.readoutAtWater()
        sim.run(3f, 0f) // the band arrived: it swims out to its side
        for (roll in listOf(0.3f, -0.3f, 0.3f, -0.3f)) {
            sim.run(3f, roll)
            sim.run(4f, 0f) // levelled: it swims out from behind the digits
            assertTrue("rests overlapping the readout by ${sim.bodyIntoReadout()} px", sim.bodyIntoReadout() <= 0f)
        }
        // Held level, its own wandering never crosses the digits.
        var worst = Float.NEGATIVE_INFINITY
        sim.run(30f, 0f) { worst = maxOf(worst, bodyIntoReadout()) }
        assertTrue("wandered into the readout by $worst px", worst <= 0f)
    }

    /**
     * Seen on a phone: after the first tilt the duck stopped following while the water
     * kept tilting. With the water at the readout's height, the readout's side was a wall and a
     * phone's centered digits leave no room beside them, so the duck sat pinned at one edge.
     */
    @Test fun `it follows every tilt, either way, over and over, with the readout at the water`() {
        val sim = pond()
        sim.run(0.5f, 0f)
        sim.readoutAtWater()
        sim.run(3f, 0f)
        val reach = 2.5f * 40f // within 40 dp of the pond's wall it was tipped toward
        repeat(6) { i ->
            sim.run(5f, 0.3f)
            assertTrue("tilt ${2 * i + 1} right: duck at ${sim.duckX}", sim.duckX + sim.halfWidth > sim.width - reach)
            assertEquals(1f, sim.pose.facing)
            sim.run(5f, -0.3f)
            assertTrue("tilt ${2 * i + 2} left: duck at ${sim.duckX}", sim.duckX - sim.halfWidth < reach)
            assertEquals(-1f, sim.pose.facing)
            if (i == 2) sim.run(4f, 0f) // a rest in between
        }
    }

    @Test fun `it follows every tilt at any water level, after resting and after the screen sleeps`() {
        val reach = 2.5f * 40f
        for (left in listOf(1_200_000L, 900_000L, 600_000L, 300_000L, 60_000L)) {
            val sim = pond()
            sim.setState(TimerState(now + left, 1_200_000L, 0L, 0L))
            sim.run(0.5f, 0f)
            sim.readoutAtWater()
            for (round in 0 until 3) {
                sim.run(5f, 0.3f)
                assertTrue("level ${left / 12_000}%, round $round right: ${sim.duckX}", sim.duckX + sim.halfWidth > sim.width - reach)
                sim.run(6f, 0f) // rests (against the wall or wherever it stopped)
                sim.run(5f, -0.3f)
                assertTrue("level ${left / 12_000}%, round $round left: ${sim.duckX}", sim.duckX - sim.halfWidth < reach)
                // The screen sleeps: the sensor goes away, frames stop, then come back.
                sim.onSensorGone()
                sim.step(0.05f, now)
                sim.step(5f, now)
            }
        }
    }

    @Test fun `a full-width glide into the wall gets a haptic tick, a gentle one does not`() {
        val sim = pond()
        sim.run(1f, 0f)
        sim.run(6f, 0.12f)
        assertEquals("a gentle tilt never ticks", 0, sim.hardBumps)
        sim.run(5f, -0.35f) // over to the left wall
        val before = sim.hardBumps
        sim.run(4f, 0.35f) // then all the way across
        assertTrue("hard bumps: ${sim.hardBumps - before}", sim.hardBumps - before >= 1)
    }

    @Test fun `a long pause in the frames does not throw the duck`() {
        val sim = pond()
        sim.run(2f, 0.3f)
        val x = sim.duckX
        sim.hold(0.3f, 0.02f)
        sim.step(30f, now) // back from the background
        assertTrue("jumped ${abs(sim.duckX - x)} px", abs(sim.duckX - x) <= 2.5f * 260f * 0.05f + 1f)
    }

    @Test fun `under reduced motion the duck stays still, whatever the phone does`() {
        val sim = pond(reduced = true)
        sim.step(0f, now)
        val x = sim.duckX
        val y = sim.duckY
        assertEquals(1f, sim.pose.facing)
        for (roll in listOf(0.3f, -0.3f, 0.6f)) {
            repeat(30) { sim.hold(roll, 0.02f); sim.step(0.2f, now) }
            assertEquals(x, sim.duckX)
            assertEquals(y, sim.duckY)
            assertEquals(0, sim.hardBumps)
        }
        assertTrue("no ripples", (0 until PondParticles.RIPPLES).none { sim.particles.ripMax[it] > 0f })
        // Not stepped as a sim either: the still pose sits on the level surface.
        assertEquals(0f, sim.tilt)
    }

    @Test
    fun `one non-finite sensor sample is ignored, not carried forever`() {
        val p = pond()
        p.run(1f, 0.1f)
        p.onAccelerometer(Float.NaN, 9.81f, 0f, 0, 1f / 60f)
        p.onAccelerometer(0f, 9.81f, Float.POSITIVE_INFINITY, 0, 1f / 60f)
        p.onAccelerometer(0f, 9.81f, 0f, 0, Float.NaN)
        p.run(1f, 0.1f)
        assertTrue("tilt ${p.tilt}", p.tilt.isFinite())
        assertTrue("duck ${p.duckX}", p.duckX.isFinite())
        assertTrue(p.surfaceAt(p.width / 2).isFinite())
    }
}
