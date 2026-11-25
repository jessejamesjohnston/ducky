package com.merrimentlabs.ducky

import com.merrimentlabs.ducky.ui.pond.PondMath
import com.merrimentlabs.ducky.ui.pond.PondMood
import com.merrimentlabs.ducky.ui.pond.PondSnapshot
import com.merrimentlabs.ducky.ui.pond.PondTransition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class PondMathTest {

    private fun running(end: Long, total: Long) = PondSnapshot(PondMood.RUNNING, end, total, 0L)
    private fun paused(rem: Long, total: Long) = PondSnapshot(PondMood.PAUSED, 0L, total, rem)
    private val idle = PondSnapshot(PondMood.IDLE, 0L, 0L, 0L)
    private val ringing = PondSnapshot(PondMood.RINGING, 0L, 60_000L, 0L)

    // ---- level mapping -------------------------------------------------------------------

    @Test fun `running level is remaining over total from the deadline`() {
        val s = running(end = 100_000L, total = 60_000L)
        assertEquals(1f, PondMath.targetLevel(s, 40_000L), 1e-6f)
        assertEquals(0.5f, PondMath.targetLevel(s, 70_000L), 1e-6f)
        assertEquals(0f, PondMath.targetLevel(s, 100_000L), 1e-6f)
        // Past the deadline but not yet ringing: empty, never negative.
        assertEquals(0f, PondMath.targetLevel(s, 130_000L), 1e-6f)
    }

    @Test fun `level is continuous between display ticks`() {
        val s = running(end = 300_000L, total = 300_000L)
        var prev = PondMath.targetLevel(s, 0L)
        for (t in 1L..300_000L step 7L) {
            val l = PondMath.targetLevel(s, t)
            assertTrue("level stepped at $t", prev - l in 0f..(8f / 300_000f))
            prev = l
        }
    }

    @Test fun `paused level is frozen, idle is full-ish, ringing is empty`() {
        assertEquals(0.25f, PondMath.targetLevel(paused(15_000L, 60_000L), 0L), 1e-6f)
        assertEquals(0.25f, PondMath.targetLevel(paused(15_000L, 60_000L), 999_999L), 1e-6f)
        assertEquals(PondMath.IDLE_LEVEL, PondMath.targetLevel(idle, 5L), 0f)
        assertEquals(0f, PondMath.targetLevel(ringing, 5L), 0f)
        // Test alarm: total 0 never divides by zero.
        assertEquals(0f, PondMath.targetLevel(running(10L, 0L), 0L), 0f)
        assertEquals(0f, PondMath.targetLevel(paused(15_000L, 0L), 0L), 0f)
    }

    @Test fun `mood comes from persisted flags`() {
        assertEquals(PondMood.IDLE, PondMath.moodOf(TimerState(0, 0, 0, 0)))
        assertEquals(PondMood.RUNNING, PondMath.moodOf(TimerState(5, 10, 0, 0)))
        assertEquals(PondMood.PAUSED, PondMath.moodOf(TimerState(0, 10, 0, 4)))
        assertEquals(PondMood.RINGING, PondMath.moodOf(TimerState(0, 10, 99, 0)))
    }

    @Test fun `surface maps level between full and empty lines`() {
        assertEquals(200f, PondMath.surfaceY(1f, fullY = 200f, emptyY = 1000f), 1e-3f)
        assertEquals(1000f, PondMath.surfaceY(0f, 200f, 1000f), 1e-3f)
        assertEquals(600f, PondMath.surfaceY(0.5f, 200f, 1000f), 1e-3f)
        // Overshoot is allowed a little, never unbounded.
        assertTrue(PondMath.surfaceY(9f, 200f, 1000f) >= 200f - 0.25f * 800f - 1e-3f)
    }

    @Test fun `duck waterline rests on the floor once the water is too shallow`() {
        assertEquals(500f, PondMath.duckWaterline(500f, floorLimit = 900f), 0f)
        assertEquals(900f, PondMath.duckWaterline(950f, floorLimit = 900f), 0f)
    }

    // ---- transitions ---------------------------------------------------------------------

    @Test fun `transitions come from state changes`() {
        assertEquals(PondTransition.SNAP, PondMath.classify(null, idle))
        assertEquals(PondTransition.NONE, PondMath.classify(idle, idle))
        assertEquals(PondTransition.ADD, PondMath.classify(idle, running(100, 60_000)))
        assertEquals(PondTransition.ADD, PondMath.classify(running(100, 60_000), running(400, 360_000)))
        assertEquals(PondTransition.ADD, PondMath.classify(paused(10, 60_000), running(400, 90_000)))
        assertEquals(PondTransition.NONE, PondMath.classify(paused(10, 60_000), running(400, 60_000)))
        assertEquals(PondTransition.NONE, PondMath.classify(running(100, 60_000), paused(10, 60_000)))
        assertEquals(PondTransition.CANCEL, PondMath.classify(running(100, 60_000), idle))
        assertEquals(PondTransition.CANCEL, PondMath.classify(paused(10, 60_000), idle))
        assertEquals(PondTransition.NONE, PondMath.classify(running(100, 60_000), ringing))
        assertEquals(PondTransition.STOP, PondMath.classify(ringing, idle))
        assertEquals(PondTransition.EXTEND, PondMath.classify(ringing, running(100, 60_000)))
        assertEquals(PondTransition.DRAIN, PondMath.classify(idle, ringing))
        // A wall-clock re-derive moves the deadline without adding time: no splash.
        assertEquals(PondTransition.NONE, PondMath.classify(running(100, 60_000), running(900, 60_000)))
    }

    // ---- springs -------------------------------------------------------------------------

    @Test fun `spring converges to the target`() {
        val out = FloatArray(2)
        var x = 0f
        var v = 0f
        repeat(600) {
            PondMath.springStep(x, v, 1f, 40f, 0.5f, 1f / 60f, out)
            x = out[0]; v = out[1]
        }
        assertEquals(1f, x, 1e-3f)
        assertEquals(0f, v, 1e-3f)
    }

    @Test fun `underdamped spring overshoots, critically damped does not`() {
        fun peak(zeta: Float): Float {
            val out = FloatArray(2)
            var x = 0f; var v = 0f; var max = 0f
            repeat(300) {
                PondMath.springStep(x, v, 1f, 40f, zeta, 1f / 120f, out)
                x = out[0]; v = out[1]; if (x > max) max = x
            }
            return max
        }
        assertTrue(peak(0.36f) > 1.2f)
        assertTrue(peak(1.0f) <= 1.0005f)
    }

    /** Peak of a 0 -> 1 refill with a transition's spring (one frame at 60 fps, 3 s). */
    private fun refillPeak(t: PondTransition): Float {
        val spec = PondMath.springFor(t)
        val out = FloatArray(2)
        var x = 0f; var v = 0f; var max = 0f
        repeat(180) {
            PondMath.springStep(x, v, 1f, spec.stiffness, spec.dampingRatio, 1f / 60f, out)
            x = out[0]; v = out[1]; if (x > max) max = x
        }
        return max
    }

    @Test fun `each transition has its own refill feel`() {
        // Cancel is the playful refill, +1 min while ringing bouncy, add springy; stop is calm.
        val cancel = refillPeak(PondTransition.CANCEL)
        val extend = refillPeak(PondTransition.EXTEND)
        val add = refillPeak(PondTransition.ADD)
        val stop = refillPeak(PondTransition.STOP)
        assertTrue("cancel overshoots: $cancel", cancel > 1.25f)
        assertTrue("extend overshoots: $extend", extend > 1.2f)
        assertTrue("add overshoots: $add", add > 1.15f)
        assertTrue("stop barely overshoots: $stop", stop < 1.01f)
        assertTrue("cancel livelier than stop", cancel > stop + 0.2f)
        // The test alarm's drain and the follow-the-deadline default never overshoot.
        assertTrue(refillPeak(PondTransition.DRAIN) <= 1.0005f)
        assertTrue(refillPeak(PondTransition.NONE) <= 1.0005f)
        assertEquals(PondMath.springFor(PondTransition.NONE), PondMath.springFor(PondTransition.SNAP))
        assertTrue("the drain is slower than an add",
            PondMath.springFor(PondTransition.DRAIN).stiffness < PondMath.springFor(PondTransition.ADD).stiffness)
    }

    @Test fun `spring is stable at a janky frame rate`() {
        val out = FloatArray(2)
        var x = 0f; var v = 0f
        repeat(40) {
            PondMath.springStep(x, v, 1f, 70f, 0.42f, 0.05f, out)
            x = out[0]; v = out[1]
            assertTrue("blew up: $x", abs(x) < 3f)
        }
        assertEquals(1f, x, 0.05f)
    }

    @Test fun `smoothing is frame-rate independent`() {
        var a = 0f
        repeat(60) { a = PondMath.lowPass(a, 1f, PondMath.smoothing(1f / 60f, 0.2f)) }
        var b = 0f
        repeat(120) { b = PondMath.lowPass(b, 1f, PondMath.smoothing(1f / 120f, 0.2f)) }
        assertEquals(a, b, 1e-3f)
    }

    // ---- tilt ----------------------------------------------------------------------------

    private val g = 9.81f

    @Test fun `upright portrait is level`() {
        assertEquals(0f, PondMath.tiltRadians(0f, g, 0f, 0), 1e-6f)
    }

    @Test fun `tilt follows roll and its sign`() {
        val a = 0.1f // radians, inside the clamp
        // Right side down: accelerometer x goes negative, surface rises on the right (tilt < 0).
        val right = PondMath.tiltRadians(-g * sin(a), g * cos(a), 0f, 0)
        assertEquals(-a, right, 1e-4f)
        val left = PondMath.tiltRadians(g * sin(a), g * cos(a), 0f, 0)
        assertEquals(a, left, 1e-4f)
    }

    @Test fun `tilt is clamped`() {
        val big = PondMath.tiltRadians(-g, 0.2f, 0f, 0)
        assertEquals(-PondMath.MAX_TILT_RAD, big, 1e-6f)
        val upsideDown = PondMath.tiltRadians(0.5f, -g, 0f, 0)
        assertTrue(abs(upsideDown) <= PondMath.MAX_TILT_RAD + 1e-6f)
    }

    @Test fun `flat on the counter is level`() {
        assertEquals(0f, PondMath.tiltRadians(0.4f, 0.3f, g, 0), 1e-6f)
        assertEquals(0f, PondMath.tiltRadians(0f, 0f, 0f, 0), 0f)
        // Half-reclined phones still tilt, just less.
        val reclined = PondMath.tiltRadians(-g * 0.3f, g * 0.3f, g * 0.9f, 0)
        assertTrue(reclined < 0f && reclined > -PondMath.MAX_TILT_RAD)
    }

    @Test fun `every display rotation reads level when held upright for it`() {
        // Upright in each rotation, gravity reaction points to the screen's top edge.
        assertEquals(0f, PondMath.tiltRadians(0f, g, 0f, 0), 1e-5f)   // portrait
        assertEquals(0f, PondMath.tiltRadians(g, 0f, 0f, 1), 1e-5f)   // landscape (ROTATION_90)
        assertEquals(0f, PondMath.tiltRadians(0f, -g, 0f, 2), 1e-5f)  // reverse portrait
        assertEquals(0f, PondMath.tiltRadians(-g, 0f, 0f, 3), 1e-5f)  // reverse landscape
    }

    @Test fun `rotation remap keeps the sign in landscape`() {
        val a = 0.1f
        // ROTATION_90, screen's right side dips: screen-right is device -y.
        val t = PondMath.tiltRadians(g * cos(a), g * sin(a), 0f, 1)
        assertEquals(-a, t, 1e-4f)
    }
}
