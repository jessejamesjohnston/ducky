package com.merrimentlabs.ducky.ui.pond

import kotlin.math.ceil

/**
 * The duck's sideways motion along the water surface: a small rigid body with mass, pushed by
 * the slope of the tilted water (and a sideways jolt), pulled by its own swimming, slowed by
 * water drag, and stopped by walls with a damped bounce. The surface itself carries it up and
 * down ([PondSim]'s buoyancy), so this is one dimension: x along the pond, in pixels.
 *
 * Pure and deterministic: the same inputs always give the same result, whatever the frame
 * rate. A frame's dt is cut into fixed sub-steps of at most [STEP] seconds and clamped to
 * [MAX_DT] first, so a frame after the app comes back from the background can't throw the duck.
 * Walls are checked after every sub-step and the position is clamped, so it can never tunnel
 * through one, however large the dt.
 */
object DuckGlide {
    /** The fixed sub-step, in seconds. */
    const val STEP = 1f / 120f
    /** The longest frame simulated at once (a resumed app's first frame is not 10 s long). */
    const val MAX_DT = 0.1f
    /** Speed kept after a bounce. Well under 1: the duck bumps and settles, it doesn't ricochet. */
    const val RESTITUTION = 0.35f

    /** Indexes into the state array. */
    const val X = 0
    const val V = 1
    /** Out: the hardest wall impact in this step (speed into the wall, px/s; 0 = none). */
    const val IMPACT = 2
    /** Out: which wall that was, -1 (left) or 1 (right); 0 = none. */
    const val SIDE = 3
    const val SIZE = 4

    /**
     * Advances [s] (x, v; impact and side are written) by [dtIn] seconds.
     *
     * Acceleration along the surface = [accel] (tilt slope and jolt, px/s²) +
     * [stiffness] × ([target] − x) (the duck swimming toward where it wants to be), with
     * [damping] (1/s) as linear drag, integrated implicitly so any drag is stable. Speed is
     * capped at [maxSpeed]. The duck stays in [lo]..[hi]; hitting a wall faster than
     * [restSpeed] bounces it back at [RESTITUTION] of its speed, slower than that it just
     * comes to rest against the wall (no endless micro-bounces while the phone stays tilted).
     */
    fun step(
        s: FloatArray,
        dtIn: Float,
        accel: Float,
        target: Float,
        stiffness: Float,
        damping: Float,
        lo: Float,
        hi: Float,
        maxSpeed: Float,
        restSpeed: Float,
    ) {
        s[IMPACT] = 0f
        s[SIDE] = 0f
        val left = if (hi >= lo) lo else (lo + hi) / 2f
        val right = if (hi >= lo) hi else left
        val dt = if (dtIn.isNaN()) 0f else dtIn.coerceIn(0f, MAX_DT)
        if (dt <= 0f) {
            s[X] = s[X].coerceIn(left, right)
            return
        }
        val n = ceil(dt / STEP).toInt().coerceAtLeast(1)
        val h = dt / n
        var x = s[X]
        var v = s[V]
        repeat(n) {
            val a = accel + stiffness * (target - x)
            v = ((v + a * h) / (1f + damping * h)).coerceIn(-maxSpeed, maxSpeed)
            x += v * h
            if (x < left) {
                x = left
                if (v < 0f) {
                    if (-v > s[IMPACT]) { s[IMPACT] = -v; s[SIDE] = -1f }
                    v = if (-v < restSpeed) 0f else -v * RESTITUTION
                }
            } else if (x > right) {
                x = right
                if (v > 0f) {
                    if (v > s[IMPACT]) { s[IMPACT] = v; s[SIDE] = 1f }
                    v = if (v < restSpeed) 0f else -v * RESTITUTION
                }
            }
        }
        s[X] = x
        s[V] = v
    }
}
