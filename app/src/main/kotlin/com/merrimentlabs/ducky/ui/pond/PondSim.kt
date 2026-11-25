package com.merrimentlabs.ducky.ui.pond

import androidx.compose.runtime.mutableLongStateOf
import com.merrimentlabs.ducky.TimerState
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.tan
import kotlin.random.Random

/**
 * The pond's physics and the duck's little life, advanced once per display frame.
 *
 * DISPLAY ONLY. The level is re-derived every frame from the persisted deadline
 * ([PondMath.targetLevel]); the only thing that is ever animated is an *offset* from that
 * target, which springs back to zero after a transition (add time, cancel, stop). So the
 * drain never lags the clock, and nothing here can move the timer.
 *
 * All state is plain fields (no per-frame allocation); [tick] is the one snapshot value,
 * bumped per frame and read only in draw code, so a frame invalidates drawing and never
 * recomposes the screen.
 */
class PondSim(seed: Int = 7, visitorRng: Random = Random.Default) {

    /** Bumped every simulated frame; read inside draw lambdas only. */
    val tick = mutableLongStateOf(0L)
    val pose = DuckPose()
    private val rng = Random(seed)
    /** The occasional diver, submarine or monster under the duck (long timers only). */
    val visitors = PondVisitors(visitorRng)

    // ---- layout (pixels, pond-local), written by the screen on layout ---------------------
    var width = 0f
    var height = 0f
    var density = 1f
    /** Bottom of the header (and banner) in pond coordinates: the water stays below it. */
    var headerBottom = 0f
    /** Surface y at level 1 (below the header, leaving room for the floating duck). */
    var fullY = 0f
        private set
    /** Surface y at level 0 (a shallow puddle above the floor). */
    var emptyY = 0f
        private set
    /** The pond floor: where a stranded duck's bottom rests. */
    var floorY = 0f
        private set
    /** Pixels per duck SVG unit. */
    var duckScale = 1f
        private set
    /** Sim clock in seconds (frozen under reduced motion). */
    val clock: Float get() = time

    private fun layoutDerived() {
        duckScale = min(dp(104f), width * 0.27f) / DuckPainter.WIDTH_UNITS
        floorY = height - dp(8f)
        emptyY = height - dp(22f)
        fullY = (headerBottom - DuckPainter.TOP * duckScale + dp(14f)).coerceAtMost(emptyY - dp(48f))
    }
    /** Readout bounds in pond coordinates (the duck swims around them); NaN = none. */
    /** The pond's own origin in root coordinates (layout writes it). */
    var originX = 0f
    var originY = 0f
    /** Left of the tappable area in pond coordinates (tap ripples land under the finger). */
    var areaLeft = 0f
    var readoutLeft = Float.NaN
    var readoutTop = Float.NaN
    var readoutRight = Float.NaN
    var readoutBottom = Float.NaN

    /** Readout bounds from root coordinates. */
    fun setReadoutBounds(rootX: Float, rootY: Float, w: Float, h: Float) {
        readoutLeft = rootX - originX
        readoutTop = rootY - originY
        readoutRight = readoutLeft + w
        readoutBottom = readoutTop + h
    }

    private var pokeX = 0f
    private var pokeT = -1f

    /** A tap on the water: a ripple under the finger, and the duck looks over. */
    fun poke(x: Float) {
        if (reduced || width <= 0f) return
        particles.spawnRipple(x, dp(36f))
        ampBoost += 0.12f
        pokeX = x
        pokeT = 0f
    }

    // ---- inputs ---------------------------------------------------------------------------
    var reduced = false
        set(value) {
            if (field != value) {
                field = value
                if (value) particles.clear()
            }
        }
    private var snap: PondSnapshot? = null
    private var pending = PondTransition.NONE
    var mood = PondMood.IDLE
        private set
    /** Length of the current timer (visitors only come on long ones). */
    var totalMillis = 0L
        private set

    /** The pond came (back) on screen. */
    fun onShown() = visitors.onShown()

    /** Feed the persisted state (every recomposition is fine: unchanged state is a no-op). */
    fun setState(state: TimerState) {
        val next = PondMath.snapshot(state)
        val t = PondMath.classify(snap, next)
        if (t != PondTransition.NONE && pending != PondTransition.SNAP) pending = t
        if (next.mood == PondMood.RINGING && mood != PondMood.RINGING) ringStart = time
        snap = next
        totalMillis = next.totalMillis
        mood = next.mood
    }

    // ---- level ----------------------------------------------------------------------------
    /** Displayed level: the deadline-derived target plus a decaying transition offset. */
    var level = 0f
        private set
    private var offset = 0f
    private var offsetVel = 0f
    private var spring = PondMath.springFor(PondTransition.NONE)
    /** A refill's splash fires when the rushing water first reaches its level. */
    private var deferredSplash = PondTransition.NONE
    var meanY = 0f
        private set

    // ---- water ----------------------------------------------------------------------------
    private var time = 0f
    private var phase = 0f
    private var speedF = 0.6f
    private var ampF = 0.7f
    private var ampBoost = 0f
    private var amp = 0f
    private var k1 = 0.01f
    private var k2 = 0.02f
    private var k1b = 0.01f
    private var k2b = 0.02f

    // ---- tilt / slosh ---------------------------------------------------------------------
    private var gx = 0f
    private var gy = 9.81f
    private var gz = 0f
    private var hasGravity = false
    private var lateral = 0f
    private var tiltTarget = 0f
    var tilt = 0f
        private set
    private var tiltVel = 0f
    private var slope = 0f
    private var slosh = 0f
    private var sloshVel = 0f

    // ---- duck -----------------------------------------------------------------------------
    var duckX = Float.NaN
        private set
    var duckY = Float.NaN
        private set
    private var duckVx = 0f
    private var duckVy = 0f
    private var rot = 0f
    private var rotVel = 0f
    private var squash = 0f
    private var squashVel = 0f
    private var facingTarget = 1f
    private var facingHold = 0f
    private var homeOffset = 0f
    private var dodgeSide = 0
    /** The readout's near edge for the duck's center while [dodgeSide] is set. */
    private var dodgeEdge = 0f
    /** A tilt (or the glide it started) is moving the duck: it may pass behind the readout. */
    private var carried = false
    /** The duck's glide along the surface: x, v, and the last step's wall impact. */
    private val glide = FloatArray(DuckGlide.SIZE)
    /** Hard wall bumps so far; the screen turns each new one into a light haptic tick. */
    var hardBumps = 0
        private set
    private var lastHardBump = -10f
    /** Last frame's waterline under the duck. */
    private var lastRest = Float.NaN
    private var lookX = 0.2f
    private var lookY = 0.1f
    private var eyeBase = 1f
    private var blinkT = -1f
    private var nextBlink = 2f
    private var doubleBlinkAt = -1f
    private var ringStart = 0f
    private var lastCycle = -1
    private var nextRingBubble = 0f
    private var nextZ = 1f
    private var nextAmbientBubble = 1.5f
    private var nextWakeRipple = 0f

    private enum class Act { NONE, LOOK, PADDLE, RUFFLE, NOD, QUACK }
    private var act = Act.NONE
    private var actT = 0f
    private var actDir = 1f
    private var nextAct = 5f

    companion object {
        private const val TWO_PI = (2 * PI).toFloat()
        /** The duck's gap beside the readout, as a share of the readout's height (time + hint). */
        const val SIDE_GAP_SHARE = 0.4f
        /** The crest line steps aside while the surface is this close to the readout's height... */
        const val CREST_GAP_PAD_DP = 6f
        /** ...and comes back fully over this much more. */
        const val CREST_GAP_FADE_DP = 16f

        // The glide (see DuckGlide), in dp and seconds.
        /** Below this tilt the duck ignores the slope: a phone held nearly level isn't a slide. */
        const val GLIDE_DEAD_RAD = 0.035f
        /** From this tilt on the slope is fully in charge. */
        const val GLIDE_FULL_RAD = 0.09f
        /** Gravity along the surface is this × sin(tilt), in dp/s². At the 14° clamp: ~270. */
        const val GLIDE_G_DP = 1100f
        /** A sideways jolt of 1 m/s² pushes the duck this hard (dp/s²), against the jolt. */
        const val GLIDE_JOLT_DP = 60f
        /** Water drag, 1/s: at the clamp the duck glides at most ~180 dp/s, and coasts to rest. */
        const val GLIDE_DRAG = 1.5f
        const val GLIDE_MAX_SPEED_DP = 260f
        /** Slower into a wall than this and it just rests against it. */
        const val GLIDE_REST_SPEED_DP = 18f
        /** The duck's own swimming: the spring toward where it wants to be. */
        const val SWIM_STIFFNESS = 3.0f
        const val SWIM_DAMPING = 3.29f // 2 × 0.95 × sqrt(3): nearly critical, never overshoots
        /** Out of bounds (the readout's band arrived under it): swim out briskly. */
        const val ESCAPE_STIFFNESS = 12f
        const val ESCAPE_DAMPING = 6.6f
    }

    /** Drops, bubbles, ripples and Zs (see [PondParticles]). */
    val particles = PondParticles(this, rng)

    private val tmp = FloatArray(2)
    private var stillSignature = Float.NaN

    private fun dp(v: Float) = v * density
    private fun rand(a: Float, b: Float) = a + (b - a) * rng.nextFloat()

    // =======================================================================================
    // Sensor input (main thread). Accelerometer in device axes; rotation = display rotation.
    // =======================================================================================
    fun onAccelerometer(ax: Float, ay: Float, az: Float, rotation: Int, dt: Float) {
        // One NaN would stay in the low-pass filter for good.
        if (!ax.isFinite() || !ay.isFinite() || !az.isFinite() || !dt.isFinite()) return
        if (!hasGravity) {
            gx = ax; gy = ay; gz = az; hasGravity = true
        } else {
            val a = PondMath.smoothing(dt, 0.18f)
            gx = PondMath.lowPass(gx, ax, a)
            gy = PondMath.lowPass(gy, ay, a)
            gz = PondMath.lowPass(gz, az, a)
        }
        tiltTarget = PondMath.tiltRadians(gx, gy, gz, rotation)
        // What the low-pass removed is motion: a sideways jolt sloshes the water. Mapped to
        // screen x for the rotation, and clamped so a dropped phone isn't a tsunami.
        val lx = ax - gx
        val ly = ay - gy
        val sx = when (rotation and 3) { 1 -> -ly; 2 -> -lx; 3 -> ly; else -> lx }
        lateral = sx.coerceIn(-6f, 6f)
    }

    /** Sensor unregistered (paused screen, reduced motion): ease back to level. */
    fun onSensorGone() {
        tiltTarget = 0f
        lateral = 0f
        hasGravity = false
    }

    // =======================================================================================
    // Surface
    // =======================================================================================

    /** Front surface y at pond-local [x] for the current frame. */
    fun surfaceAt(x: Float): Float =
        meanY + slope * (x - width * 0.5f) + frontWave(x) + slosh * cos(PI.toFloat() * x / max(width, 1f))

    fun frontWave(x: Float): Float =
        amp * (0.62f * sin(k1 * x - phase) + 0.38f * sin(k2 * x + 1.7f * phase + 1.3f))

    /** The back layer sits a little higher and moves on its own rhythm (parallax). */
    fun backSurfaceAt(x: Float): Float =
        meanY + slope * (x - width * 0.5f) - dp(6f) +
            amp * 0.8f * (0.6f * sin(k1b * x + 0.8f * phase + 2.1f) + 0.4f * sin(k2b * x - 1.35f * phase + 0.4f)) +
            slosh * 0.8f * cos(PI.toFloat() * x / max(width, 1f))

    private fun slopeAt(x: Float): Float {
        val h = dp(10f)
        return (surfaceAt(x + h) - surfaceAt(x - h)) / (2f * h)
    }

    val floorLimit: Float get() = floorY - DuckPainter.BOTTOM * duckScale

    /** How far the front surface can rise above [meanY] right now: waves plus slosh. */
    val waveReach: Float get() = amp + abs(slosh)
    /** The surface's tilt slope this frame. */
    val slopeNow: Float get() = slope

    // =======================================================================================
    // Frame step
    // =======================================================================================

    fun step(dtIn: Float, nowMillis: Long) {
        val s = snap ?: return
        if (width <= 0f || height <= 0f) return
        val dt = dtIn.coerceIn(0f, 0.05f)
        layoutDerived()
        if (reduced) {
            visitors.clear()
            stepStill(s, nowMillis)
            // Only redraw when the still picture actually changed (level, mood, layout).
            // readoutTop is NaN until the readout's first layout, and NaN never equals itself.
            val readout = if (readoutTop.isNaN()) -1f else readoutTop
            val sig = level * 7919f + mood.ordinal * 31f + width + height * 3f + readout + headerBottom
            if (sig != stillSignature) { stillSignature = sig; tick.longValue++ }
            return
        }
        stillSignature = Float.NaN
        time += dt
        wavelengths()

        // ---- level ----
        val target = PondMath.targetLevel(s, nowMillis)
        when (val p = pending) {
            PondTransition.NONE -> Unit
            PondTransition.SNAP -> { offset = 0f; offsetVel = 0f; placeDuckAtRest(target) }
            else -> {
                offset = level - target
                spring = PondMath.springFor(p)
                when (p) {
                    PondTransition.ADD -> { offsetVel += 0.30f; splash(p) }
                    PondTransition.DRAIN -> Unit
                    else -> deferredSplash = p
                }
            }
        }
        pending = PondTransition.NONE
        val before = offset
        PondMath.springStep(offset, offsetVel, 0f, spring.stiffness, spring.dampingRatio, dt, tmp)
        offset = tmp[0]; offsetVel = tmp[1]
        if (deferredSplash != PondTransition.NONE && (before < 0f) != (offset < 0f)) {
            splash(deferredSplash)
            deferredSplash = PondTransition.NONE
        }
        if (abs(offset) < 1e-4f && abs(offsetVel) < 1e-3f) {
            offset = 0f; offsetVel = 0f
            if (deferredSplash != PondTransition.NONE) { splash(deferredSplash); deferredSplash = PondTransition.NONE }
        }
        level = target + offset
        // A refill's overshoot may rise past full, but never up into the header.
        meanY = max(PondMath.surfaceY(level, fullY, emptyY), min(headerBottom + dp(20f), fullY))

        // ---- water motion by mood ----
        val spT = when (mood) {
            PondMood.IDLE -> 0.55f
            PondMood.RUNNING -> 1.0f
            PondMood.PAUSED -> 0.22f
            PondMood.RINGING -> 0.9f
        }
        val amT = when (mood) {
            PondMood.IDLE -> 0.70f
            PondMood.RUNNING -> 1.0f
            PondMood.PAUSED -> 0.38f
            PondMood.RINGING -> 0.65f
        }
        val sm = PondMath.smoothing(dt, 0.9f)
        speedF = PondMath.lowPass(speedF, spT, sm)
        ampF = PondMath.lowPass(ampF, amT, sm)
        ampBoost *= exp(-dt / 1.1f)
        phase = (phase + dt * 1.3f * (speedF + ampBoost * 0.6f)) % (TWO_PI * 1000f)
        val depth = floorY - meanY
        val depthScale = 0.25f + 0.75f * PondMath.smoothstep(0f, dp(70f), depth)
        amp = dp(6.5f) * (ampF + ampBoost) * depthScale

        // ---- tilt + slosh (spring-damped, clamped upstream) ----
        PondMath.springStep(tilt, tiltVel, tiltTarget, 16f, 0.30f, dt, tmp)
        tilt = tmp[0].coerceIn(-PondMath.MAX_TILT_RAD * 1.3f, PondMath.MAX_TILT_RAD * 1.3f); tiltVel = tmp[1]
        slope = tan(tilt)
        val w0 = 4.4f
        val sloshAcc = -w0 * w0 * slosh - 2f * 0.12f * w0 * sloshVel -
            lateral * dp(10f) - tiltVel * dp(26f)
        sloshVel += sloshAcc * dt
        slosh = (slosh + sloshVel * dt).coerceIn(-dp(18f), dp(18f))
        // A tilt lifts the high side by slope × half the width. On a wide window held at an angle
        // (a tablet on a stand) a nearly full pond reached the header and the duck rode up under the
        // settings cog: the high side (slosh included) now stops where a level full pond's surface would be.
        meanY = max(meanY, min(fullY + abs(slope) * width * 0.5f + abs(slosh), emptyY))

        stepDuck(dt)
        particles.stepDropsAndRipples(dt)
        if (mood != PondMood.RINGING && time >= nextAmbientBubble && floorY - meanY > dp(60f)) {
            nextAmbientBubble = time + if (mood == PondMood.PAUSED) rand(4f, 8f) else rand(1.2f, 3.4f)
            particles.spawnWaterBubble()
        }
        particles.stepBubblesAndZs(dt, time)
        visitors.step(dt, this)
        tick.longValue++
    }

    private fun wavelengths() {
        val base = (width * 0.95f).coerceIn(dp(260f), dp(560f))
        k1 = TWO_PI / base
        k2 = TWO_PI / (base * 0.43f)
        k1b = TWO_PI / (base * 1.21f)
        k2b = TWO_PI / (base * 0.57f)
    }

    private fun placeDuckAtRest(target: Float) {
        level = target
        meanY = PondMath.surfaceY(target, fullY, emptyY)
        if (duckX.isNaN()) duckX = width * 0.3f
        duckY = min(meanY, floorLimit)
        duckVy = 0f
        lastRest = Float.NaN
    }

    // =======================================================================================
    // The duck
    // =======================================================================================

    private var lo = 0f
    private var hi = 0f

    /** Where the duck's center may go ([lo]..[hi]) without leaving the pond. */
    private fun duckBounds() {
        val half = max(-DuckPainter.LEFT, DuckPainter.RIGHT) * duckScale
        lo = half + dp(8f)
        hi = width - half - dp(8f)
        if (hi <= lo) { lo = width / 2f; hi = lo }
    }

    /**
     * Room the duck keeps beside the readout while the water is at its height: a share of the
     * readout's height, so beside large digits it doesn't sit against the first or last one,
     * and never under 24 dp. Where the pond is too narrow for that, the slot is clamped to the
     * pond's edge as before.
     */
    internal fun sideGap(): Float =
        if (readoutTop.isNaN()) dp(24f) else max(dp(24f), (readoutBottom - readoutTop) * SIDE_GAP_SHARE)

    /**
     * How strongly the surface's highlight line steps aside for the readout this frame, 0 to 1:
     * full while the surface is within the readout's height (plus [CREST_GAP_PAD_DP]), easing
     * out over [CREST_GAP_FADE_DP] beyond it. Sampled at the readout's two edges and its middle,
     * so a tilted surface counts wherever it meets the text. The line would otherwise run
     * straight through the small hint under the time.
     */
    fun crestGap(): Float {
        if (readoutTop.isNaN() || duckX.isNaN()) return 0f
        val top = readoutTop - dp(CREST_GAP_PAD_DP)
        val bottom = readoutBottom + dp(CREST_GAP_PAD_DP)
        var dist = Float.MAX_VALUE
        for (x in floatArrayOf(readoutLeft, (readoutLeft + readoutRight) / 2f, readoutRight)) {
            val y = surfaceAt(x)
            dist = min(dist, if (y < top) top - y else if (y > bottom) y - bottom else 0f)
        }
        return 1f - PondMath.smoothstep(0f, dp(CREST_GAP_FADE_DP), dist)
    }

    private fun dodge(target: Float, lo: Float, hi: Float, restY: Float): Float {
        if (readoutTop.isNaN()) { dodgeSide = 0; return target }
        val margin = dp(24f)
        val anticipate = dp(48f)
        val top = restY + DuckPainter.TOP * duckScale
        val bottom = restY + DuckPainter.BOTTOM * duckScale
        val overlap = bottom + margin + anticipate > readoutTop && top - margin - anticipate < readoutBottom
        if (!overlap) { dodgeSide = 0; return target }
        // Sticky side: once the duck chose a side it stays there until the band is clear, so
        // its own swimming never crosses the digits. Only a tilt can carry it across (see the
        // walls in stepDuck); once it is past the middle, the far side is its side.
        val sideNow = if (duckX.isNaN() || duckX < (readoutLeft + readoutRight) / 2f) -1 else 1
        if (dodgeSide == 0 || (dodgeSide != sideNow && carried)) dodgeSide = sideNow
        val gap = sideGap()
        val leftSlot = readoutLeft - gap - DuckPainter.RIGHT * duckScale
        val rightSlot = readoutRight + gap - DuckPainter.LEFT * duckScale
        dodgeEdge = if (dodgeSide < 0) leftSlot.coerceIn(lo, hi) else rightSlot.coerceIn(lo, hi)
        return if (dodgeSide < 0) min(target, leftSlot).coerceAtLeast(lo)
        else max(target, rightSlot).coerceAtMost(hi)
    }

    private fun stepDuck(dt: Float) {
        duckBounds()
        if (duckX.isNaN()) duckX = (width * 0.3f).coerceIn(lo, hi)
        if (duckY.isNaN()) duckY = min(meanY, floorLimit)
        val floating = meanY < floorLimit

        // Where it would like to be: a slow wander plus wherever it last paddled (or glided) to.
        val wander = width * (0.5f + 0.26f * sin(time * 0.045f + 0.7f) + 0.07f * sin(time * 0.13f + 2f))
        homeOffset *= exp(-dt / 40f)
        // The tilted water carries it (past a small dead zone), and so does its own momentum
        // after a glide or a jolt. While either is in charge it stops swimming for home: home
        // becomes wherever it is, so when the phone levels it settles there instead of
        // swimming back, and the slow wander takes over from that spot.
        val slide = if (floating) PondMath.smoothstep(GLIDE_DEAD_RAD, GLIDE_FULL_RAD, abs(tilt)) else 0f
        val coasting = if (floating) PondMath.smoothstep(dp(70f), dp(140f), abs(duckVx)) else 0f
        val anchor = max(slide, coasting)
        carried = slide >= 0.5f || (coasting >= 0.5f && carried)
        if (anchor > 0f) homeOffset += (duckX - wander - homeOffset) * anchor
        var targetX = (wander + homeOffset).coerceIn(lo, hi)
        if (mood == PondMood.RINGING) targetX = duckX // stranded: it stays where it landed
        targetX = dodge(targetX, lo, hi, min(meanY, floorLimit))

        // Walls: the pond's edges, and the readout's side while the water is at its height and
        // the phone is about level. If the readout's band arrived with the duck already in it, it
        // swims out instead (no jump). A real tilt (or the glide it started) carries the duck
        // past the readout, behind the digits, which are drawn over it: on a phone the digits
        // leave no room beside them, and a wall there pinned the duck in place for the whole
        // middle of a timer while the water still tilted. It never rests
        // there: level again, it swims out to the nearer side.
        var wallLo = lo
        var wallHi = hi
        var escape = false
        if (!carried) {
            if (dodgeSide < 0) { if (duckX <= dodgeEdge + dp(1f)) wallHi = dodgeEdge else escape = true }
            if (dodgeSide > 0) { if (duckX >= dodgeEdge - dp(1f)) wallLo = dodgeEdge else escape = true }
        }
        // Toward the side of the phone that is lower in the world, where the water runs and
        // deepens: the surface stays level for the eye, so on screen it rises on that side
        // (positive tilt = rises on the left = the left is down). And against a sideways jolt,
        // as anything loose in a moving pond.
        val accel = if (floating) -dp(GLIDE_G_DP) * sin(tilt) * slide - lateral * dp(GLIDE_JOLT_DP) else 0f
        val stiffness = if (escape) ESCAPE_STIFFNESS else SWIM_STIFFNESS * (1f - anchor)
        val damping = when {
            escape -> ESCAPE_DAMPING
            floating -> GLIDE_DRAG + (SWIM_DAMPING - GLIDE_DRAG) * (1f - anchor)
            else -> SWIM_DAMPING
        }
        glide[DuckGlide.X] = duckX
        glide[DuckGlide.V] = duckVx
        DuckGlide.step(
            glide, dt, accel, targetX, stiffness, damping, wallLo, wallHi,
            maxSpeed = dp(GLIDE_MAX_SPEED_DP), restSpeed = dp(GLIDE_REST_SPEED_DP),
        )
        duckX = glide[DuckGlide.X]; duckVx = glide[DuckGlide.V]
        if (glide[DuckGlide.IMPACT] > 0f && floating) bump(glide[DuckGlide.IMPACT], glide[DuckGlide.SIDE])

        // It faces where it's going, once that's clearly a direction: a bounce's brief recoil
        // or jostling during a refill must not spin it back and forth.
        val dir = sign(duckVx)
        if (abs(duckVx) > dp(28f) && abs(duckVy) < dp(250f) && dir != pose.facing) {
            facingHold += dt
            if (facingHold > 0.25f) facingTarget = dir
        } else {
            facingHold = 0f
        }
        // Turn in one frame and sell it with a little squash: scaling the width through zero
        // reads as a paper-thin glitch, never as a turn.
        if (facingTarget != pose.facing) {
            pose.facing = facingTarget
            squashVel += 1.1f
            facingHold = 0f
        }

        // Buoyancy: a springy pull to the surface, so a rushing refill pops the duck up and
        // it bobs to rest. It can never sink through the floor.
        val surface = surfaceAt(duckX)
        val floor = floorLimit
        // Gliding fast, it bobs a little more.
        val bob = dp(1.6f) * PondMath.smoothstep(dp(40f), dp(180f), abs(duckVx)) * sin(time * TWO_PI * 1.7f)
        val rest = PondMath.duckWaterline(surface + bob, floor)
        // Buoyancy grows with depth: a submerged duck shoots up fast, so a rushing refill
        // carries it instead of swallowing it, and it still pops out at the overshoot.
        // When the water drops away under it (the phone swung the other way while the duck sat by
        // the wall, where the surface moves most), it rides down with the water instead of
        // hanging in the air for a moment. Rising water and a fling upward are left to the
        // spring, so a refill still lifts and pops it as before.
        if (!lastRest.isNaN() && rest > lastRest && duckVy > -dp(50f) && duckY < rest + dp(4f)) {
            duckY += (rest - lastRest).coerceAtMost(dp(40f))
        }
        lastRest = rest
        val under = ((duckY - rest) / dp(24f)).coerceIn(0f, 1f)
        PondMath.springStep(duckY, duckVy, rest, 85f + 340f * under, 0.36f, dt, tmp)
        duckY = tmp[0]; duckVy = tmp[1]
        // A refill can fling the duck up; it tops out below the header and falls back.
        val ceiling = min(headerBottom - DuckPainter.TOP * duckScale + dp(4f), rest)
        if (duckY < ceiling) {
            duckY = ceiling
            if (duckVy < 0f) duckVy = 0f
        }
        if (duckY > floor) {
            if (duckVy > dp(120f)) land(duckVy)
            duckY = floor
            duckVy = if (duckVy > 0f) -duckVy * 0.15f else duckVy
        }

        behave(dt, surface < floor)

        // Rotation: ride the local slope, lean into motion, plus whatever the act adds.
        val slopeDeg = Math.toDegrees(atan(slopeAt(duckX).toDouble())).toFloat() *
            (if (surface < floor) 0.85f else 0.15f)
        val lean = (duckVx / dp(80f) * 5f).coerceIn(-6f, 6f)
        PondMath.springStep(rot, rotVel, slopeDeg + lean + actRotation, 45f, 0.45f, dt, tmp)
        rot = tmp[0]; rotVel = tmp[1]
        pose.rotation = rot

        val stretch = (duckVy / dp(1300f)).coerceIn(-0.12f, 0.12f) + breathe
        PondMath.springStep(squash, squashVel, stretch, 160f, 0.30f, dt, tmp)
        squash = tmp[0]; squashVel = tmp[1]
        pose.squash = squash

        // Eyes: base openness by mood, blinks on top.
        val eyeT = when (mood) { PondMood.PAUSED -> 0.36f; else -> 1f }
        eyeBase = PondMath.lowPass(eyeBase, eyeT, PondMath.smoothing(dt, 0.5f))
        if (time >= nextBlink) {
            blinkT = 0f
            val paused = mood == PondMood.PAUSED
            nextBlink = time + if (paused) rand(4f, 8f) else rand(2.2f, 5.5f)
            if (!paused && rng.nextFloat() < 0.22f) doubleBlinkAt = time + 0.26f
        }
        if (doubleBlinkAt in 0f..time) { blinkT = 0f; doubleBlinkAt = -1f }
        var blink = 0f
        if (blinkT >= 0f) {
            blinkT += dt
            val dur = if (mood == PondMood.PAUSED) 0.34f else 0.16f
            blink = sin(PI.toFloat() * (blinkT / dur).coerceIn(0f, 1f))
            if (blinkT > dur) blinkT = -1f
        }
        pose.eyeOpen = eyeBase * (1f - blink)
        val ls = PondMath.smoothing(dt, 0.12f)
        pose.lookX = PondMath.lowPass(pose.lookX, lookX, ls)
        pose.lookY = PondMath.lowPass(pose.lookY, lookY, ls)
    }

    private var actRotation = 0f
    private var breathe = 0f

    /** The small charming things: looking around, paddling, ruffling, and the alarm dance. */
    private fun behave(dt: Float, floating: Boolean) {
        actRotation = 0f
        breathe = 0f
        var wing = 0f
        var beak = 0f
        lookX = 0.25f
        lookY = 0.15f

        when (mood) {
            PondMood.RINGING -> {
                act = Act.NONE
                val c = time - ringStart
                // Settle a beat after landing, then perk up and celebrate on a loop.
                if (c < 0.35f) {
                    lookY = 0.6f
                } else {
                    val cycleLen = 1.7f
                    val cc = (c - 0.35f) % cycleLen
                    val n = ((c - 0.35f) / cycleLen).toInt()
                    lookX = 0.35f; lookY = -1f
                    actRotation = -7f
                    if (cc < 1.1f) {
                        val env = sin(PI.toFloat() * (cc / 1.1f)).coerceAtLeast(0f)
                        wing = 0.25f + 0.85f * env * (0.5f - 0.5f * cos(TWO_PI * 7.5f * cc))
                        actRotation += 7f * env * sin(TWO_PI * 4.2f * cc)
                        if (cc < 0.3f) beak = sin(PI.toFloat() * cc / 0.3f)
                        if (time >= nextRingBubble) {
                            nextRingBubble = time + rand(0.08f, 0.16f)
                            particles.spawnAirBubble()
                        }
                    } else {
                        wing = 0.25f
                    }
                    if (n != lastCycle) {
                        lastCycle = n
                        if (n % 2 == 1) duckVy -= dp(300f) // a hop every other burst
                    }
                }
            }
            PondMood.PAUSED -> {
                act = Act.NONE
                breathe = 0.025f * sin(time * TWO_PI * 0.28f)
                lookY = 0.5f
                if (time >= nextZ) { nextZ = time + 2.3f; particles.spawnZ() }
            }
            else -> {
                lastCycle = -1
                if (act == Act.NONE && time >= nextAct) {
                    val roll = rng.nextFloat()
                    act = when {
                        roll < 0.30f -> Act.LOOK
                        roll < 0.58f -> Act.PADDLE
                        roll < 0.76f -> Act.RUFFLE
                        roll < 0.92f -> Act.NOD
                        else -> if (mood == PondMood.IDLE) Act.QUACK else Act.LOOK
                    }
                    actT = 0f
                    actDir = if (rng.nextBoolean()) 1f else -1f
                    if (act == Act.PADDLE) {
                        duckBounds()
                        var dest = duckX + actDir * rand(dp(50f), dp(110f))
                        if (dest < lo || dest > hi) { actDir = -actDir; dest = duckX + actDir * dp(80f) }
                        homeOffset += dest - duckX
                    }
                    if (act == Act.RUFFLE) squashVel += 1.4f
                }
                if (act != Act.NONE) {
                    actT += dt
                    when (act) {
                        Act.LOOK -> {
                            lookX = when {
                                actT < 1.0f -> -1f
                                actT < 1.9f -> 1f
                                else -> 0.25f
                            }
                            lookY = if (actT < 1.9f) -0.2f else 0.15f
                            actRotation = if (actT < 1.0f) -3f else if (actT < 1.9f) 2f else 0f
                            if (actT > 2.4f) endAct()
                        }
                        Act.PADDLE -> {
                            actRotation = 3f * sin(actT * TWO_PI * 3f)
                            if (time >= nextWakeRipple && floating) {
                                nextWakeRipple = time + 0.3f
                                particles.spawnRipple(duckX + DuckPainter.tailOffset(pose.facing) * duckScale, dp(26f))
                            }
                            if (actT > 1.8f) endAct()
                        }
                        Act.RUFFLE -> {
                            wing = 0.55f * (0.5f - 0.5f * cos(TWO_PI * 9f * actT))
                            if (actT > 0.9f) endAct()
                        }
                        Act.NOD -> {
                            val e = sin(PI.toFloat() * (actT / 1.2f).coerceIn(0f, 1f))
                            actRotation = 10f * e
                            lookY = 0.15f + 0.85f * e
                            if (actT > 1.2f) endAct()
                        }
                        Act.QUACK -> {
                            beak = if (actT < 0.16f) sin(PI.toFloat() * actT / 0.16f)
                            else if (actT in 0.26f..0.42f) sin(PI.toFloat() * (actT - 0.26f) / 0.16f) else 0f
                            if (actT > 0.7f) endAct()
                        }
                        Act.NONE -> Unit
                    }
                }
            }
        }
        if (pokeT >= 0f) {
            pokeT += dt
            if (mood != PondMood.RINGING) {
                lookX = if ((pokeX - duckX) * pose.facing >= 0f) 1f else -1f
                lookY = 0.4f
            }
            if (pokeT > 1.1f) pokeT = -1f
        }
        val ws = PondMath.smoothing(dt, 0.035f)
        pose.wing = PondMath.lowPass(pose.wing, wing, ws)
        pose.beak = PondMath.lowPass(pose.beak, beak, PondMath.smoothing(dt, 0.03f))
    }

    private fun endAct() {
        act = Act.NONE
        nextAct = time + if (mood == PondMood.IDLE) rand(3.5f, 7.5f) else rand(7f, 14f)
    }

    /**
     * The duck reached a wall at [impact] px/s ([side] -1 left, 1 right): the body pitches into
     * it and squeezes, a ripple spreads from the contact, and a harder bump throws a few drops
     * and gets a startled blink. A gentle arrival does nothing at all.
     */
    private fun bump(impact: Float, side: Float) {
        val s = PondMath.smoothstep(dp(25f), dp(240f), impact)
        if (s <= 0f) return
        squashVel -= 0.5f + 1.8f * s
        rotVel += side * (30f + 90f * s)
        val contact = duckX + side * max(-DuckPainter.LEFT, DuckPainter.RIGHT) * duckScale * 0.9f
        particles.spawnRipple(contact, dp(14f) + dp(30f) * s)
        ampBoost += 0.1f * s
        if (s > 0.4f) {
            for (i in 0 until (2 + 6 * s).toInt()) {
                particles.spawnDrop(
                    contact + rand(-dp(6f), dp(6f)), surfaceAt(contact),
                    -side * rand(dp(20f), dp(90f)), -rand(dp(120f), dp(120f) + dp(260f) * s), rand(dp(1.6f), dp(3.2f)),
                )
            }
        }
        if (s > 0.25f) blinkT = 0f
        if (s > 0.7f && time - lastHardBump > 0.6f) {
            lastHardBump = time
            hardBumps++
        }
    }

    private fun land(vy: Float) {
        squashVel += (vy / dp(700f)).coerceAtMost(3.2f)
        for (i in 0 until 8) {
            val side = if (i % 2 == 0) -1f else 1f
            particles.spawnDrop(
                duckX + side * rand(dp(20f), dp(40f)), floorY - dp(6f),
                side * rand(dp(40f), dp(130f)), -rand(dp(160f), dp(330f)), rand(dp(1.6f), dp(3f)),
            )
        }
        particles.spawnRipple(duckX, dp(46f))
    }

    // =======================================================================================
    // Splashes
    // =======================================================================================

    private fun splash(t: PondTransition) {
        when (t) {
            PondTransition.ADD -> {
                ampBoost += 0.7f
                duckVy -= dp(170f)
                particles.burstAtDuck(10, 230f, 470f)
                particles.spawnRipple(duckX, dp(40f))
            }
            PondTransition.CANCEL -> {
                ampBoost += 1.7f
                duckVy -= dp(140f)
                particles.burstAcross(22)
                particles.burstAtDuck(12, 300f, 620f)
                particles.spawnRipple(duckX, dp(60f))
            }
            PondTransition.EXTEND -> {
                ampBoost += 1.3f
                particles.burstAcross(12)
                particles.burstAtDuck(10, 260f, 540f)
                particles.spawnRipple(duckX, dp(50f))
            }
            PondTransition.STOP -> {
                ampBoost += 0.5f
                particles.burstAtDuck(5, 150f, 300f)
                particles.spawnRipple(duckX, dp(40f))
            }
            else -> Unit
        }
    }

    // =======================================================================================
    // Reduced motion: a still pond at the exact level, instant changes, nothing moving.
    // =======================================================================================
    private fun stepStill(s: PondSnapshot, nowMillis: Long) {
        pending = PondTransition.NONE
        deferredSplash = PondTransition.NONE
        lastRest = Float.NaN
        offset = 0f; offsetVel = 0f
        wavelengths()
        level = PondMath.targetLevel(s, nowMillis)
        meanY = PondMath.surfaceY(level, fullY, emptyY)
        phase = 1.1f
        ampBoost = 0f
        tilt = 0f; tiltVel = 0f; slope = 0f; slosh = 0f; sloshVel = 0f
        carried = false
        val depth = floorY - meanY
        amp = dp(6.5f) * (if (mood == PondMood.PAUSED) 0.38f else 0.7f) *
            (0.25f + 0.75f * PondMath.smoothstep(0f, dp(70f), depth))
        duckBounds()
        val floor = floorLimit
        dodgeSide = 0
        duckX = dodge((width * 0.28f).coerceIn(lo, hi), lo, hi, min(meanY, floor))
        duckVx = 0f; duckVy = 0f
        duckY = PondMath.duckWaterline(surfaceAt(duckX), floor)
        pose.facing = 1f
        pose.rotation = if (surfaceAt(duckX) < floor)
            Math.toDegrees(atan(slopeAt(duckX).toDouble())).toFloat() * 0.85f else 0f
        pose.squash = 0f
        when (mood) {
            PondMood.PAUSED -> { pose.eyeOpen = 0.36f; pose.lookX = 0.25f; pose.lookY = 0.5f; pose.wing = 0f; pose.beak = 0f }
            PondMood.RINGING -> { pose.eyeOpen = 1f; pose.lookX = 0.35f; pose.lookY = -1f; pose.wing = 0.45f; pose.beak = 0.35f; pose.rotation = -7f }
            else -> { pose.eyeOpen = 1f; pose.lookX = 0.25f; pose.lookY = 0.15f; pose.wing = 0f; pose.beak = 0f }
        }
        particles.clear()
    }
}
