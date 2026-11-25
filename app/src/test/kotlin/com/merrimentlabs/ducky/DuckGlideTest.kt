package com.merrimentlabs.ducky

import com.merrimentlabs.ducky.ui.pond.DuckGlide
import com.merrimentlabs.ducky.ui.pond.DuckGlide.IMPACT
import com.merrimentlabs.ducky.ui.pond.DuckGlide.SIDE
import com.merrimentlabs.ducky.ui.pond.DuckGlide.V
import com.merrimentlabs.ducky.ui.pond.DuckGlide.X
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** The duck's glide along the surface: a pure, deterministic step. Units: px and seconds. */
class DuckGlideTest {

    private val lo = 0f
    private val hi = 1000f
    private val maxSpeed = 600f
    private val rest = 40f

    private fun state(x: Float, v: Float = 0f) = floatArrayOf(x, v, 0f, 0f)

    /** Runs [seconds] at a frame interval of [frame], returning the state and the impacts seen. */
    private fun run(
        s: FloatArray, seconds: Float, frame: Float, accel: Float,
        target: Float = 0f, stiffness: Float = 0f, damping: Float = 1.5f,
    ): List<Float> {
        val impacts = mutableListOf<Float>()
        var t = 0f
        while (t < seconds - 1e-6f) {
            DuckGlide.step(s, frame, accel, target, stiffness, damping, lo, hi, maxSpeed, rest)
            if (s[IMPACT] > 0f) impacts += s[IMPACT]
            t += frame
        }
        return impacts
    }

    @Test fun `a tilt accelerates the duck to its terminal glide speed`() {
        val s = state(100f)
        run(s, 1.5f, 1f / 60f, accel = 300f)
        // Terminal speed is accel / drag = 200 px/s; after 1.5 s (2.25 time constants) about 90% of it.
        assertTrue("v=${s[V]}", s[V] in 170f..200f)
        // x = v_t * t - v_t / drag * (1 - e^(-drag t)) = 300 - 119 px.
        assertEquals("moved right", 281f, s[X], 4f)
        val left = state(900f)
        run(left, 1.5f, 1f / 60f, accel = -300f)
        assertTrue("the other way: v=${left[V]}", left[V] in -200f..-170f)
    }

    @Test fun `the same motion at any frame rate`() {
        val results = listOf(1f / 30f, 1f / 60f, 1f / 90f, 1f / 120f, 1f / 144f).map { frame ->
            val s = state(100f)
            run(s, 1.2f, frame, accel = 250f)
            s[X]
        }
        for (x in results) assertEquals("x at 1.2 s: $results", results[1], x, 1.5f)
    }

    @Test fun `deterministic, the same inputs give the same result`() {
        val a = state(400f, 50f)
        val b = state(400f, 50f)
        run(a, 3f, 1f / 60f, accel = -180f, target = 600f, stiffness = 1f)
        run(b, 3f, 1f / 60f, accel = -180f, target = 600f, stiffness = 1f)
        assertTrue(a.contentEquals(b))
    }

    @Test fun `a bounce off a wall keeps well under its energy`() {
        val s = state(998f, 300f)
        DuckGlide.step(s, 1f / 60f, 0f, 0f, 0f, 0f, lo, hi, maxSpeed, rest)
        assertEquals(300f, s[IMPACT], 1f)
        assertEquals(1f, s[SIDE])
        assertEquals(-300f * DuckGlide.RESTITUTION, s[V], 1f)
        val energyKept = (s[V] * s[V]) / (300f * 300f)
        assertTrue("kept $energyKept of the energy", energyKept < 0.15f)
        assertTrue(s[X] <= hi)
        val l = state(4f, -300f)
        DuckGlide.step(l, 1f / 60f, 0f, 0f, 0f, 0f, lo, hi, maxSpeed, rest)
        assertEquals(-1f, l[SIDE])
        assertTrue(l[V] > 0f && l[X] >= lo)
    }

    @Test fun `a gentle arrival rests against the wall instead of bouncing`() {
        val s = state(999.9f, 20f)
        DuckGlide.step(s, 1f / 60f, 0f, 0f, 0f, 0f, lo, hi, maxSpeed, rest)
        assertEquals(hi, s[X])
        assertEquals(0f, s[V])
        assertEquals(20f, s[IMPACT], 1f) // still reported, the caller decides it's too soft to show
    }

    @Test fun `held tilted, it bumps a few times then rests against the low wall`() {
        val s = state(200f)
        val impacts = run(s, 8f, 1f / 60f, accel = 300f)
        assertEquals(hi, s[X])
        assertEquals(0f, s[V], 1e-3f)
        // The first bump is the hard one, each next one is softer, and they stop.
        val bounces = impacts.filter { it >= rest }
        assertTrue("bounces $bounces", bounces.size in 1..4)
        assertTrue(bounces.zipWithNext().all { (a, b) -> b < a })
    }

    @Test fun `levelled, it coasts to a stop`() {
        val s = state(300f, 150f)
        run(s, 6f, 1f / 60f, accel = 0f)
        assertTrue("v=${s[V]}", abs(s[V]) < 0.05f)
        val x = s[X]
        run(s, 2f, 1f / 60f, accel = 0f)
        assertEquals("at rest it stays put", x, s[X], 0.05f)
        // Coasting distance is v / drag: 150 / 1.5 = 100 px.
        assertEquals(400f, x, 1.5f)
    }

    @Test fun `swimming home settles without overshoot`() {
        val s = state(200f)
        var maxX = 0f
        var t = 0f
        while (t < 6f) {
            DuckGlide.step(s, 1f / 60f, 0f, 500f, 3f, 3.29f, lo, hi, maxSpeed, rest)
            maxX = maxOf(maxX, s[X]); t += 1f / 60f
        }
        assertEquals(500f, s[X], 2f)
        assertTrue("overshoot to $maxX", maxX <= 501f)
    }

    @Test fun `no tunnelling and no leap, whatever the frame`() {
        // A 10 s frame (the app back from the background): clamped to MAX_DT.
        val s = state(990f, 590f)
        DuckGlide.step(s, 10f, 5000f, 0f, 0f, 0f, lo, hi, maxSpeed, rest)
        assertTrue("x=${s[X]}", s[X] in lo..hi)
        val far = state(500f, 0f)
        DuckGlide.step(far, 10f, 1e6f, 0f, 0f, 0f, lo, hi, maxSpeed, rest)
        assertTrue("moved at most maxSpeed x MAX_DT", far[X] - 500f <= maxSpeed * DuckGlide.MAX_DT + 1e-3f)
        // Even at huge speeds over many frames, never outside the walls.
        val wild = state(500f, 0f)
        for (i in 0 until 2000) {
            DuckGlide.step(wild, 0.1f, if (i % 50 < 25) 1e5f else -1e5f, 0f, 0f, 0f, lo, hi, maxSpeed, rest)
            assertTrue(wild[X] in lo..hi)
        }
    }

    @Test fun `odd inputs stay safe`() {
        val s = state(500f, 10f)
        DuckGlide.step(s, Float.NaN, 100f, 0f, 0f, 1f, lo, hi, maxSpeed, rest)
        assertEquals(500f, s[X])
        DuckGlide.step(s, -1f, 100f, 0f, 0f, 1f, lo, hi, maxSpeed, rest)
        assertEquals(500f, s[X])
        // Walls that crossed (a tiny pond): the duck sits in the middle.
        val t = state(10f)
        DuckGlide.step(t, 1f / 60f, 100f, 0f, 0f, 1f, 60f, 40f, maxSpeed, rest)
        assertEquals(50f, t[X])
    }
}
