package com.merrimentlabs.ducky.ui.pond

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.merrimentlabs.ducky.TimerState
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** What the pond is doing, derived only from persisted state (never from the clock). */
enum class PondMood { IDLE, RUNNING, PAUSED, RINGING }

/**
 * How the level should move when the persisted state changes shape. Chosen from the state
 * transition itself, never from a frame-to-frame level delta: a +5 min on a fresh 5 min timer
 * barely moves the level but still earns a splash, and coming back from the background after
 * minutes must not look like a refill.
 */
enum class PondTransition {
    /** No event: the level simply follows the deadline. */
    NONE,
    /** First frame: land exactly on the level, no animation. */
    SNAP,
    /** Time added (start, +30s/+1m/+5m, resume-by-add): springy rise, small splash. */
    ADD,
    /** Cancel of a running or paused timer: the playful refill. */
    CANCEL,
    /** Stop while ringing: a calm refill. */
    STOP,
    /** +1 min while ringing: a full, bouncy refill. */
    EXTEND,
    /** Idle to ringing with no countdown (Settings test alarm): a quick drain. */
    DRAIN,
}

/** The fields of [TimerState] that shape the pond. */
data class PondSnapshot(
    val mood: PondMood,
    val endTimeMillis: Long,
    val totalMillis: Long,
    val pausedRemainingMillis: Long,
)

/** Spring feel for one transition: stiffness (1/s^2) and damping ratio. */
data class SpringSpec(val stiffness: Float, val dampingRatio: Float)

object PondMath {

    /** The idle pond is full-ish, not brim-full, so a started timer visibly rises. */
    const val IDLE_LEVEL = 0.88f

    fun snapshot(state: TimerState): PondSnapshot = PondSnapshot(
        mood = moodOf(state),
        endTimeMillis = state.endTimeMillis,
        totalMillis = state.totalMillis,
        pausedRemainingMillis = state.pausedRemainingMillis,
    )

    /**
     * Mood from the persisted flags only. A deadline that has passed but not yet flipped to
     * ringing still reads RUNNING (at level 0), so the pond never flashes idle in that gap.
     */
    fun moodOf(state: TimerState): PondMood = when {
        state.isRinging -> PondMood.RINGING
        state.isPaused -> PondMood.PAUSED
        state.endTimeMillis > 0L -> PondMood.RUNNING
        else -> PondMood.IDLE
    }

    /** Target water level 0..1 at wall-clock [nowMillis]; the display source of truth. */
    fun targetLevel(s: PondSnapshot, nowMillis: Long): Float = when (s.mood) {
        PondMood.IDLE -> IDLE_LEVEL
        PondMood.RINGING -> 0f
        PondMood.PAUSED ->
            if (s.totalMillis > 0) (s.pausedRemainingMillis.toFloat() / s.totalMillis).coerceIn(0f, 1f)
            else 0f
        PondMood.RUNNING ->
            if (s.totalMillis > 0)
                ((s.endTimeMillis - nowMillis).coerceAtLeast(0L).toFloat() / s.totalMillis).coerceIn(0f, 1f)
            else 0f
    }

    fun classify(prev: PondSnapshot?, next: PondSnapshot): PondTransition {
        if (prev == null) return PondTransition.SNAP
        if (prev == next) return PondTransition.NONE
        val p = prev.mood
        val n = next.mood
        return when {
            (p == PondMood.RUNNING || p == PondMood.PAUSED) && n == PondMood.IDLE -> PondTransition.CANCEL
            p == PondMood.RINGING && n == PondMood.IDLE -> PondTransition.STOP
            p == PondMood.RINGING && n == PondMood.RUNNING -> PondTransition.EXTEND
            p == PondMood.IDLE && n == PondMood.RINGING -> PondTransition.DRAIN
            p == PondMood.IDLE && n == PondMood.RUNNING -> PondTransition.ADD
            (p == PondMood.RUNNING || p == PondMood.PAUSED) && n == PondMood.RUNNING &&
                next.totalMillis > prev.totalMillis -> PondTransition.ADD
            else -> PondTransition.NONE
        }
    }

    fun springFor(t: PondTransition): SpringSpec = when (t) {
        PondTransition.ADD -> SpringSpec(stiffness = 70f, dampingRatio = 0.42f)
        PondTransition.CANCEL -> SpringSpec(stiffness = 34f, dampingRatio = 0.36f)
        PondTransition.EXTEND -> SpringSpec(stiffness = 40f, dampingRatio = 0.40f)
        PondTransition.STOP -> SpringSpec(stiffness = 12f, dampingRatio = 0.90f)
        PondTransition.DRAIN -> SpringSpec(stiffness = 26f, dampingRatio = 1.0f)
        PondTransition.NONE, PondTransition.SNAP -> SpringSpec(stiffness = 40f, dampingRatio = 1.0f)
    }

    /**
     * Level (0..1, overshoot allowed) to surface y. Level 1 sits at [fullY] (leaving room for
     * the floating duck above it), level 0 at [emptyY] (a shallow puddle on the floor).
     */
    fun surfaceY(level: Float, fullY: Float, emptyY: Float): Float =
        emptyY + (fullY - emptyY) * level.coerceIn(-0.05f, 1.25f)

    /**
     * Where the duck's waterline sits: on the surface while it floats, but its bottom can
     * never pass the floor ([floorLimit] = the lowest allowed waterline y).
     */
    fun duckWaterline(surfaceY: Float, floorLimit: Float): Float = min(surfaceY, floorLimit)

    // ---- springs -------------------------------------------------------------------------

    /**
     * One semi-implicit Euler step of a damped spring pulling [x] toward [target]. Sub-steps
     * keep stiff springs stable at low frame rates. Writes into [out] (x, v) to stay
     * allocation-free in the frame loop.
     */
    fun springStep(
        x: Float, v: Float, target: Float,
        stiffness: Float, dampingRatio: Float, dt: Float, out: FloatArray,
    ) {
        var px = x
        var pv = v
        val damping = 2f * dampingRatio * sqrt(stiffness)
        val steps = max(1, (dt / 0.008f).toInt() + 1)
        val h = dt / steps
        repeat(steps) {
            val a = -stiffness * (px - target) - damping * pv
            pv += a * h
            px += pv * h
        }
        out[0] = px
        out[1] = pv
    }

    /** Exponential smoothing factor for a time constant, frame-rate independent. */
    fun smoothing(dt: Float, timeConstant: Float): Float =
        if (timeConstant <= 0f) 1f else 1f - exp(-dt / timeConstant)

    fun lowPass(prev: Float, sample: Float, alpha: Float): Float = prev + (sample - prev) * alpha

    // ---- tilt ----------------------------------------------------------------------------

    /** Never a wild angle: the pond tilts at most this far (about 14 degrees). */
    const val MAX_TILT_RAD = 0.25f

    /**
     * Screen-space tilt of the water surface, in radians (positive = the surface rises toward
     * the LEFT of the screen, i.e. the screen's slope dy/dx = tan(tilt) with y pointing down).
     *
     * [ax], [ay], [az] are a (low-passed) accelerometer reading in the device's natural axes;
     * [rotation] is the display rotation (0..3 quarter turns, as Surface.ROTATION_*), so a
     * landscape pond reads level instead of pinned at the clamp. A phone lying flat on the
     * counter has no meaningful roll, so the tilt fades to level as the reading leaves the
     * screen plane; upside-down readings clamp.
     */
    fun tiltRadians(ax: Float, ay: Float, az: Float, rotation: Int, max: Float = MAX_TILT_RAD): Float {
        // Remap to screen axes (x right, y up) for the current display rotation.
        val sx: Float
        val sy: Float
        when (rotation and 3) {
            1 -> { sx = -ay; sy = ax }
            2 -> { sx = -ax; sy = -ay }
            3 -> { sx = ay; sy = -ax }
            else -> { sx = ax; sy = ay }
        }
        val planar = sqrt(sx * sx + sy * sy)
        val total = sqrt(sx * sx + sy * sy + az * az)
        if (total < 1e-3f || planar < 1e-3f) return 0f
        val roll = atan2(sx, sy)
        // Fade: flat (planar < 35% of g) -> 0, clearly upright (> 70%) -> full.
        val weight = smoothstep(0.35f, 0.70f, planar / total)
        return roll.coerceIn(-max, max) * weight
    }

    fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    // ---- ink -----------------------------------------------------------------------------

    fun contrast(a: Color, b: Color): Float {
        val la = a.luminance()
        val lb = b.luminance()
        return (max(la, lb) + 0.05f) / (min(la, lb) + 0.05f)
    }

    /** The ink (of two) with the higher contrast against [background]. */
    fun bestInk(background: Color, light: Color, dark: Color): Color =
        if (contrast(background, light) >= contrast(background, dark)) light else dark

    /** Lowest contrast of [ink] against any color of a two-stop gradient (sampled). */
    fun minContrastOver(ink: Color, top: Color, bottom: Color, samples: Int = 9): Float {
        var worst = Float.MAX_VALUE
        for (i in 0 until samples) {
            val t = i / (samples - 1f)
            worst = min(worst, contrast(ink, androidx.compose.ui.graphics.lerp(top, bottom, t)))
        }
        return worst
    }
}
