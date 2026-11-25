package com.merrimentlabs.ducky

import com.merrimentlabs.ducky.ui.pond.DuckPainter
import com.merrimentlabs.ducky.ui.pond.PondMath
import com.merrimentlabs.ducky.ui.pond.PondMood
import com.merrimentlabs.ducky.ui.pond.PondParticles
import com.merrimentlabs.ducky.ui.pond.PondSim
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The pond's life apart from the glide (PondGlideTest): the splash each state change earns,
 * the duck's moods (dozing Zs, the alarm dance, the hop and landing), a tap on the water, the
 * fixed particle pools, and the reduced-motion still frames. Plain JVM, at a frozen clock.
 */
class PondSimTest {

    private val now = 10_000_000L
    private val idle = TimerState(0L, 0L, 0L, 0L)
    /** Half a 20-minute timer left: the level holds at 0.5 at the frozen clock. */
    private val halfway = TimerState(now + 600_000L, 1_200_000L, 0L, 0L)
    private val pausedHalf = TimerState(0L, 1_200_000L, 0L, 600_000L)
    private val ringing = TimerState(0L, 60_000L, now - 1_000L, 0L)
    private val dt = 1f / 60f

    private fun pond(state: TimerState, reduced: Boolean = false) = PondSim().apply {
        density = 2.5f
        width = 1000f // 400 dp: a phone in portrait
        height = 2000f
        headerBottom = 220f
        this.reduced = reduced
        setState(state)
    }

    private fun PondSim.frames(n: Int, each: PondSim.() -> Unit = {}) = repeat(n) { step(dt, now); each() }
    private fun PondSim.liveDrops() = particles.dropLife.count { it > 0f }
    private fun PondSim.liveRipples() = (0 until PondParticles.RIPPLES).count { particles.ripMax[it] > 0f && particles.ripAge[it] < 1.4f }
    private fun PondSim.airBubbles() = (0 until PondParticles.BUBBLES).count { particles.bubLife[it] > 0f && particles.bubAir[it] }
    private fun PondSim.waterBubbles() = (0 until PondParticles.BUBBLES).count { particles.bubLife[it] > 0f && !particles.bubAir[it] }
    private fun PondSim.liveZs() = (0 until PondParticles.ZS).count { particles.zAge[it] > 0f && particles.zAge[it] < 2.6f }

    /** Steps until drops first appear (at most [max] frames); returns that frame's count. */
    private fun PondSim.firstSplash(max: Int = 240): Int {
        repeat(max) {
            step(dt, now)
            val n = liveDrops()
            if (n > 0) return n
        }
        return 0
    }

    // ---- layout --------------------------------------------------------------------------

    @Test fun `the full line sits under the header, the empty line just above the floor`() {
        val sim = pond(idle)
        sim.frames(1)
        assertTrue("full ${sim.fullY} below the header", sim.fullY > sim.headerBottom)
        assertTrue("full ${sim.fullY} above empty ${sim.emptyY}", sim.fullY < sim.emptyY)
        assertTrue("empty ${sim.emptyY} above the floor ${sim.floorY}", sim.emptyY < sim.floorY)
        assertEquals(sim.height - 2.5f * 8f, sim.floorY, 1e-3f)
        assertEquals(PondMath.IDLE_LEVEL, sim.level, 1e-4f)
        assertEquals(PondMath.surfaceY(PondMath.IDLE_LEVEL, sim.fullY, sim.emptyY), sim.meanY, 1e-3f)
        // The back wave is a parallax layer a little higher than the front, everywhere.
        for (x in 0..1000 step 50) {
            val gap = sim.backSurfaceAt(x.toFloat()) - sim.surfaceAt(x.toFloat())
            assertTrue("back wave at $x is ${-gap} px above the front", gap < 0f && gap > -2.5f * 30f)
        }
    }

    @Test fun `the sim clock runs with frames and stands still under reduced motion`() {
        val sim = pond(halfway)
        sim.frames(60)
        assertEquals(1f, sim.clock, 1e-3f)
        val still = pond(halfway, reduced = true)
        repeat(10) { still.step(0.2f, now) }
        assertEquals(0f, still.clock, 0f)
    }

    @Test fun `nothing steps before the first state or before layout`() {
        val noState = PondSim().apply { width = 1000f; height = 2000f }
        noState.step(dt, now)
        assertTrue(noState.duckX.isNaN())
        val noLayout = PondSim().apply { setState(idle) }
        noLayout.step(dt, now)
        assertTrue(noLayout.duckX.isNaN())
        assertEquals(0L, noLayout.tick.longValue)
    }

    // ---- splashes ------------------------------------------------------------------------

    @Test fun `starting a timer rises with a small splash on the same frame`() {
        val sim = pond(idle)
        sim.frames(30)
        assertEquals(0, sim.liveDrops())
        val ripplesBefore = sim.liveRipples()
        sim.setState(TimerState(now + 1_200_000L, 1_200_000L, 0L, 0L))
        sim.frames(1)
        assertEquals("ADD throws 10 drops at the duck", 10, sim.liveDrops())
        assertTrue("and a ripple", sim.liveRipples() > ripplesBefore)
        for (i in 0 until PondParticles.DROPS) if (sim.particles.dropLife[i] > 0f) {
            assertTrue("drop $i near the duck", abs(sim.particles.dropX[i] - sim.duckX) < 2.5f * 60f)
        }
        // The level springs up past full and settles at the new target.
        var peak = 0f
        sim.frames(180) { peak = maxOf(peak, level) }
        assertTrue("overshoots: $peak", peak > 1.0f)
        assertEquals(1f, sim.level, 1e-3f)
    }

    @Test fun `cancel refills playfully, splashing only when the water reaches its level`() {
        val sim = pond(halfway)
        sim.frames(30)
        sim.setState(idle)
        sim.frames(1)
        assertEquals("no splash on the cancel frame", 0, sim.liveDrops())
        var crossed = false
        val first = run {
            repeat(240) {
                sim.step(dt, now)
                if (sim.level >= PondMath.IDLE_LEVEL) crossed = true
                val n = sim.liveDrops()
                if (n > 0) return@run n
            }
            0
        }
        assertTrue("the splash waited for the water to reach idle", crossed)
        assertEquals("a burst across the pond plus one at the duck", 22 + 12, first)
        assertTrue("drops all across the width", (0 until PondParticles.DROPS).any { sim.particles.dropLife[it] > 0f && abs(sim.particles.dropX[it] - sim.duckX) > 2.5f * 60f })
        sim.frames(300)
        assertEquals(PondMath.IDLE_LEVEL, sim.level, 1e-3f)
        assertEquals("every drop has fallen back in", 0, sim.liveDrops())
    }

    @Test fun `plus one minute while ringing is a bouncy refill with a big splash`() {
        val sim = pond(ringing)
        sim.frames(30)
        assertEquals(0, sim.liveDrops())
        sim.setState(TimerState(now + 60_000L, 120_000L, 0L, 0L))
        sim.frames(1)
        assertEquals(0, sim.liveDrops())
        assertEquals("a burst of 12 across plus 10 at the duck", 12 + 10, sim.firstSplash())
    }

    @Test fun `stop while ringing is a calm refill with a little splash`() {
        val sim = pond(ringing)
        sim.frames(30)
        sim.setState(idle)
        sim.frames(1)
        assertEquals(0, sim.liveDrops())
        assertEquals(5, sim.firstSplash())
    }

    @Test fun `the test alarm drains without a splash`() {
        val sim = pond(idle)
        sim.frames(30)
        sim.setState(TimerState(0L, 0L, now, 0L)) // idle -> ringing, total 0: DRAIN
        var drops = 0
        sim.frames(20) { drops = maxOf(drops, liveDrops()) }
        assertEquals(0, drops)
        assertTrue("the level is going down: ${sim.level}", sim.level < PondMath.IDLE_LEVEL - 0.05f)
        sim.frames(240)
        assertEquals(0f, sim.level, 1e-3f)
    }

    @Test fun `five quick taps fill the droplet pool and never overflow it`() {
        val sim = pond(halfway)
        sim.frames(30)
        for (k in 1..5) {
            sim.setState(TimerState(now + 600_000L + k * 60_000L, 1_200_000L + k * 60_000L, 0L, 0L))
            sim.frames(1)
        }
        assertEquals("48 slots, 50 drops thrown", PondParticles.DROPS, sim.liveDrops())
    }

    // ---- moods ---------------------------------------------------------------------------

    @Test fun `paused water is calmer than running water at the same level`() {
        fun swell(state: TimerState): Float {
            val sim = pond(state)
            sim.frames(240)
            var amp = 0f
            sim.frames(120) { for (x in 0..1000 step 25) amp = maxOf(amp, abs(frontWave(x.toFloat()))) }
            return amp
        }
        val running = swell(halfway)
        val paused = swell(pausedHalf)
        assertTrue("paused $paused vs running $running", paused < running * 0.6f)
        val idleAmp = swell(idle)
        assertTrue("idle $idleAmp between", idleAmp > paused && idleAmp < running * 1.01f)
    }

    @Test fun `paused, the duck dozes and sleepy Zs drift up`() {
        val sim = pond(pausedHalf)
        assertEquals(PondMood.PAUSED, sim.mood)
        var maxZs = 0
        var lowestEye = 1f
        sim.frames(60 * 4) {
            maxZs = maxOf(maxZs, liveZs())
            lowestEye = minOf(lowestEye, pose.eyeOpen)
        }
        assertTrue("Zs appear", maxZs >= 1)
        assertTrue("at most one Z at a time plus the fading one", maxZs <= 2)
        assertTrue("drowsy lids: $lowestEye", lowestEye < 0.5f)
        // A live Z rises and starts above the duck's head.
        val i = (0 until PondParticles.ZS).first { sim.particles.zAge[it] > 0f && sim.particles.zAge[it] < 2.4f }
        val y0 = sim.particles.zY[i]
        assertTrue("Z above the duck", y0 < sim.duckY)
        sim.frames(6)
        assertTrue("Z rises: $y0 -> ${sim.particles.zY[i]}", sim.particles.zY[i] < y0)
    }

    @Test fun `ringing, the stranded duck celebrates with bubbles and hops`() {
        val sim = pond(ringing)
        sim.frames(1)
        assertTrue("level 0 strands the duck: mean ${sim.meanY} vs floor limit ${sim.floorLimit}",
            sim.meanY >= sim.floorLimit)
        val x0 = sim.duckX
        var air = 0
        var wing = 0f
        var beak = 0f
        var highest = Float.MAX_VALUE
        sim.frames(60 * 5) {
            air = maxOf(air, airBubbles())
            wing = maxOf(wing, pose.wing)
            beak = maxOf(beak, pose.beak)
            highest = minOf(highest, duckY)
            // Air bubbles rise behind the duck (it faces right), never across its face.
            for (b in 0 until PondParticles.BUBBLES) if (particles.bubLife[b] > 0f && particles.bubAir[b]) {
                assertTrue("bubble $b behind the duck", particles.bubX[b] < duckX + 2.5f * 6f)
            }
        }
        assertTrue("air bubbles", air >= 3)
        assertTrue("wings flap: $wing", wing > 0.5f)
        assertTrue("it quacks: $beak", beak > 0.3f)
        assertTrue("a hop lifts it: ${sim.floorLimit - highest} px", sim.floorLimit - highest > 2.5f * 12f)
        assertEquals("it stays where it landed", x0, sim.duckX, 2.5f * 2f)
        assertEquals("no ambient water bubbles in a puddle", 0, sim.waterBubbles())
    }

    @Test fun `when the water vanishes under it the duck drops onto the floor with a splash`() {
        // A ring that arrives with water still in the pond (the clock stepped past the
        // deadline): running -> ringing has no refill spring, so the level is 0 at once.
        val sim = pond(halfway)
        sim.frames(30)
        val y0 = sim.duckY
        sim.setState(ringing)
        var frame = -1
        var drops = 0
        for (f in 0 until 60) {
            sim.step(dt, now)
            if (frame < 0 && sim.liveDrops() > 0) { frame = f; drops = sim.liveDrops() }
        }
        assertTrue("it fell from $y0", y0 < sim.floorLimit - 2.5f * 100f)
        assertTrue("landed after a fall, not at once: frame $frame", frame in 5..40)
        assertEquals("the landing throws 8 drops", 8, drops)
        assertEquals("and rests on the floor", sim.floorLimit, sim.duckY, 2.5f * 4f)
    }

    @Test fun `left idle, the duck gets around to a quack`() {
        val sim = pond(idle)
        var beak = 0f
        var look = 0f
        var ripples = 0
        var bubbles = 0
        sim.frames(60 * 300) {
            beak = maxOf(beak, pose.beak)
            look = minOf(look, pose.lookX)
            ripples = maxOf(ripples, liveRipples())
            bubbles = maxOf(bubbles, waterBubbles())
        }
        assertTrue("beak opened: $beak", beak > 0.3f)
        assertTrue("looked over its shoulder: $look", look < -0.5f)
        assertTrue("paddled or bubbled a ripple", ripples > 0)
        assertTrue("ambient bubbles rise in deep water", bubbles > 0)
    }

    // ---- poke ----------------------------------------------------------------------------

    @Test fun `a tap on the water ripples under the finger and the duck looks over`() {
        val sim = pond(halfway)
        sim.frames(60)
        val duck = sim.duckX
        val right = duck + 300f
        sim.poke(right)
        assertTrue("ripple at the tap", (0 until PondParticles.RIPPLES).any { sim.particles.ripX[it] == right && sim.particles.ripAge[it] < 0.1f })
        sim.frames(30)
        assertTrue("looks toward the tap: ${sim.pose.lookX}", sim.pose.lookX * sim.pose.facing > 0.6f)
        sim.frames(60) // the glance lasts about a second
        sim.poke(duck - 300f)
        sim.frames(30)
        assertTrue("looks the other way: ${sim.pose.lookX}", sim.pose.lookX * sim.pose.facing < -0.6f)
    }

    @Test fun `a tap does nothing under reduced motion or before layout`() {
        val still = pond(halfway, reduced = true)
        still.step(0f, now)
        still.poke(500f)
        assertEquals(0, still.liveRipples())
        val unlaid = PondSim().apply { density = 2.5f; setState(halfway) }
        unlaid.poke(500f)
        assertEquals(0, unlaid.liveRipples())
    }

    // ---- full pools ----------------------------------------------------------------------

    @Test fun `a full bubble pool spawns nothing and overwrites nothing`() {
        val sim = pond(ringing)
        sim.frames(1)
        // Every slot holds a long-lived water bubble resting under the surface.
        for (i in 0 until PondParticles.BUBBLES) {
            sim.particles.bubLife[i] = 50f; sim.particles.bubAir[i] = false; sim.particles.bubY[i] = sim.floorY; sim.particles.bubR[i] = 1f
        }
        sim.frames(60 * 2) // the alarm dance would release air bubbles
        assertEquals("no air bubble found a slot", 0, sim.airBubbles())
        assertEquals(PondParticles.BUBBLES, sim.waterBubbles())

        val calm = pond(idle)
        calm.frames(1)
        for (i in 0 until PondParticles.BUBBLES) {
            calm.particles.bubLife[i] = 50f; calm.particles.bubAir[i] = false; calm.particles.bubY[i] = calm.floorY; calm.particles.bubR[i] = 1f
        }
        calm.frames(60 * 4) // ambient bubbles would rise every couple of seconds
        assertEquals(PondParticles.BUBBLES, calm.waterBubbles())
        assertTrue("none moved: the pool kept its bubbles", (0 until PondParticles.BUBBLES).all { calm.particles.bubY[it] == calm.floorY })
    }

    @Test fun `a full Z pool spawns no new Z`() {
        val sim = pond(pausedHalf)
        sim.frames(50) // the first Z is due at 1 s
        assertEquals(0, sim.liveZs())
        for (i in 0 until PondParticles.ZS) { sim.particles.zAge[i] = 0.5f; sim.particles.zX[i] = -500f }
        sim.frames(20)
        assertEquals(PondParticles.ZS, sim.liveZs())
        assertTrue("no Z was re-placed at the duck", (0 until PondParticles.ZS).all { sim.particles.zX[it] < -400f })
    }

    // ---- reduced motion ------------------------------------------------------------------

    @Test fun `reduced motion draws each mood as a still pose at the exact level`() {
        val paused = pond(pausedHalf, reduced = true)
        paused.step(0f, now)
        assertEquals(0.5f, paused.level, 1e-6f)
        assertEquals(0.36f, paused.pose.eyeOpen, 0f)
        assertEquals(0.5f, paused.pose.lookY, 0f)
        assertEquals(0f, paused.pose.wing, 0f)

        val ring = pond(ringing, reduced = true)
        ring.step(0f, now)
        assertEquals(0f, ring.level, 0f)
        assertEquals(1f, ring.pose.eyeOpen, 0f)
        assertEquals(0.45f, ring.pose.wing, 0f)
        assertEquals(0.35f, ring.pose.beak, 0f)
        assertEquals(-7f, ring.pose.rotation, 0f)
        assertEquals(-1f, ring.pose.lookY, 0f)

        val calm = pond(idle, reduced = true)
        calm.step(0f, now)
        assertEquals(PondMath.IDLE_LEVEL, calm.level, 1e-6f)
        assertEquals(0f, calm.pose.beak, 0f)
        assertEquals(1f, calm.pose.eyeOpen, 0f)
        // Nothing moving: no particles, and stepping again does not bump the frame.
        assertEquals(0, calm.liveDrops() + calm.liveRipples() + calm.liveZs())
    }

    @Test fun `turning reduced motion on clears every particle`() {
        val sim = pond(halfway)
        sim.frames(30)
        sim.setState(TimerState(now + 1_200_000L, 1_800_000L, 0L, 0L)) // +10 min: ADD
        sim.frames(1)
        assertTrue(sim.liveDrops() > 0)
        sim.reduced = true
        assertEquals(0, sim.liveDrops())
        assertEquals(0, sim.liveRipples())
        sim.step(0f, now)
        assertFalse("the still duck sits on the surface", sim.duckY.isNaN())
        assertEquals(sim.surfaceAt(sim.duckX), sim.duckY, 1e-3f)
        // Same picture again: the frame counter only moves when the picture changes.
        sim.setReadoutBounds(300f, 900f, 400f, 200f)
        sim.step(0f, now)
        val tick = sim.tick.longValue
        sim.step(0f, now)
        sim.step(0f, now)
        assertEquals(tick, sim.tick.longValue)
        sim.setState(pausedHalf)
        sim.step(0f, now)
        assertEquals(tick + 1, sim.tick.longValue)
    }

    @Test fun `stranded at the floor the duck stays on the floor`() {
        val sim = pond(ringing)
        sim.frames(60)
        val bottom = sim.duckY + DuckPainter.BOTTOM * sim.duckScale
        assertTrue("bottom $bottom never under the floor ${sim.floorY}", bottom <= sim.floorY + 0.01f)
    }

    @Test
    fun `a still pond with no readout laid out yet redraws only when its picture changes`() {
        val p = pond(pausedHalf, reduced = true) // setReadoutBounds never called
        p.frames(30)
        val ticks = p.tick.longValue
        p.frames(30)
        assertEquals("nothing changed, nothing redrawn", ticks, p.tick.longValue)
    }
}
