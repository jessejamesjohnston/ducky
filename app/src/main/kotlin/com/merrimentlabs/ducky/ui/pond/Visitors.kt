package com.merrimentlabs.ducky.ui.pond

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/** Who swims by under the duck now and then. */
enum class VisitorKind(
    /** Drawing box in painter units (the creature faces right, centered on its box). */
    val widthUnits: Float,
    val heightUnits: Float,
    /** On-screen height in dp, before the pond-width cap. */
    val heightDp: Float,
) {
    DIVER(120f, 44f, 40f),
    SUBMARINE(124f, 60f, 52f),
    NESSIE(150f, 64f, 58f),
}

/**
 * The occasional underwater visitor on a long timer: a scuba diver, a little submarine or the
 * Loch Ness monster drifts across the pond below the duck, then the pond is quiet again.
 *
 * DECORATION ONLY, like the rest of the pond: it reads the sim, never the timer store, and has
 * no semantics. Only while a timer longer than [MIN_TOTAL_MILLIS] is running, never under
 * reduced motion, never on the widgets.
 *
 * Scheduled in ON-SCREEN time (the sim clock only runs while the pond is drawn). Ducky doesn't
 * keep the screen on, so a phone shows the pond in short glances: each time the pond comes back
 * on screen ([onShown]) there is a [SESSION_CHANCE] of a visitor within seconds, and a pond left
 * open gets one every [GAP_MIN]..[GAP_MAX] seconds. A visitor keeps clear of the readout band
 * and stays under the surface (the water clip hides nothing of it unless the pond runs dry).
 */
class PondVisitors(private val rng: Random = Random.Default) {

    companion object {
        /** Strictly longer than five minutes: a plain +5m timer doesn't qualify. */
        const val MIN_TOTAL_MILLIS = 5 * 60_000L
        /** On coming (back) on screen: the chance of a visitor soon, and how soon. */
        const val SESSION_CHANCE = 0.5f
        const val SOON_MIN = 6f
        const val SOON_MAX = 20f
        /** Between visitors while the pond stays on screen, in seconds. */
        const val GAP_MIN = 70f
        const val GAP_MAX = 150f
        /** No room this time (shallow water, a tall readout): look again after this. */
        const val RETRY = 12f
        /** Swimming speed in dp per second. */
        const val SPEED_MIN_DP = 34f
        const val SPEED_MAX_DP = 52f
        const val FADE = 0.6f
    }

    var kind: VisitorKind? = null
        private set
    /** Center of the visitor's box, pond-local pixels. */
    var x = 0f
        private set
    var y = 0f
        private set
    /** 1 = swimming right (and facing right), -1 = left. */
    var dir = 1f
        private set
    /** Seconds since it appeared: drives kicks, propellers and the tail. */
    var age = 0f
        private set
    /** Pixels per painter unit. */
    var scale = 1f
        private set
    /** 0..1: fades in on arrival, out when the timer stops under it. */
    var alpha = 0f
        private set

    private var baseY = 0f
    private var speed = 0f
    private var leaving = false
    private var last: VisitorKind? = null
    /** Sim time of the next visit; NaN = not scheduled (not eligible, or one is swimming). */
    var nextAt = Float.NaN
        private set
    private var eligibleBefore = false
    private var shown = true

    /** The pond came (back) on screen: a fresh chance of a visitor soon. */
    fun onShown() { shown = true }

    fun clear() {
        kind = null
        alpha = 0f
        leaving = false
    }

    fun step(dt: Float, sim: PondSim) {
        val now = sim.clock
        val mood = sim.mood
        val eligible = !sim.reduced && mood == PondMood.RUNNING &&
            sim.totalMillis > MIN_TOTAL_MILLIS && sim.width > 0f

        if (sim.reduced) { clear(); nextAt = Float.NaN; eligibleBefore = false; return }
        // A stopped timer (idle, ringing) sends a swimming visitor off with a fade; a paused one
        // lets it finish its crossing.
        if (kind != null && (mood == PondMood.IDLE || mood == PondMood.RINGING)) leaving = true

        if (!eligible) {
            nextAt = Float.NaN
            eligibleBefore = false
        } else if (!eligibleBefore || shown) {
            if (kind == null) {
                nextAt = now + if (rng.nextFloat() < SESSION_CHANCE) rand(SOON_MIN, SOON_MAX) else rand(GAP_MIN, GAP_MAX)
            }
            eligibleBefore = true
        }
        shown = false

        if (kind == null) {
            if (eligible && !nextAt.isNaN() && now >= nextAt) {
                nextAt = if (spawn(sim)) Float.NaN else now + RETRY
            }
            return
        }
        swim(dt, sim)
    }

    private fun rand(a: Float, b: Float) = a + (b - a) * rng.nextFloat()

    private fun spawn(sim: PondSim): Boolean {
        val d = sim.density
        val choices = VisitorKind.entries.filter { it != last }
        val k = choices[rng.nextInt(choices.size)]
        val s = min(k.heightDp * d / k.heightUnits, sim.width * 0.45f / k.widthUnits)
        val halfW = k.widthUnits * s / 2f
        val halfH = k.heightUnits * s / 2f
        val top = sim.meanY + surfaceMargin(sim) + halfH
        val bottom = sim.floorY - 12f * d - halfH
        if (bottom <= top) return false
        val gap = 10f * d
        var lo = top
        var hi = bottom
        if (!sim.readoutTop.isNaN() && !sim.readoutBottom.isNaN()) {
            // Keep clear of the digits: the larger band below or above the readout.
            val belowLo = max(top, sim.readoutBottom + gap + halfH)
            val aboveHi = min(bottom, sim.readoutTop - gap - halfH)
            val below = bottom - belowLo
            val above = aboveHi - top
            when {
                below >= 0f && below >= above -> { lo = belowLo; hi = bottom }
                above >= 0f -> { lo = top; hi = aboveHi }
                else -> return false
            }
        }
        kind = k
        last = k
        scale = s
        dir = if (rng.nextBoolean()) 1f else -1f
        x = if (dir > 0f) -halfW else sim.width + halfW
        baseY = lo + (hi - lo) * rand(0.3f, 0.7f)
        y = baseY
        speed = rand(SPEED_MIN_DP, SPEED_MAX_DP) * d
        age = 0f
        alpha = 0f
        leaving = false
        return true
    }

    /** Room left between the surface and a visitor's top: waves, slosh and a tilt's slope. */
    private fun surfaceMargin(sim: PondSim): Float =
        sim.waveReach + abs(sim.slopeNow) * sim.width * 0.5f + 14f * sim.density

    private fun swim(dt: Float, sim: PondSim) {
        val k = kind ?: return
        age += dt
        x += dir * speed * dt
        alpha = if (leaving) alpha - dt / FADE else min(1f, alpha + dt / FADE)
        val halfW = k.widthUnits * scale / 2f
        val halfH = k.heightUnits * scale / 2f
        // The water drains under it: sink with the surface, never poke out of it.
        val ceiling = sim.meanY + surfaceMargin(sim) + halfH
        y = max(baseY, ceiling) + sin(age * 1.1f) * 3f * sim.density
        val gone = if (dir > 0f) x - halfW > sim.width else x + halfW < 0f
        if (gone || alpha <= 0f && leaving) {
            clear()
            if (sim.mood == PondMood.RUNNING && !sim.reduced) nextAt = sim.clock + rand(GAP_MIN, GAP_MAX)
        }
    }
}

/** Visitor colors: their own, softened toward the water they swim in. */
object VisitorColors {
    val SUIT = Color(0xFF263238)
    val TANK = Color(0xFFFFC107)
    val FIN = Color(0xFFFFB300)
    val GLASS = Color(0xFFB2EBF2)
    val SKIN = Color(0xFFFFCCBC)
    val HULL = Color(0xFFFFCA28)
    val HULL_SHADE = Color(0xFFF9A825)
    val PORT_RIM = Color(0xFF5D4037)
    val NESSIE = Color(0xFF66BB6A)
    val NESSIE_BELLY = Color(0xFFA5D6A7)
    val INK = Color(0xFF1B1B1B)
    val BUBBLE = Color(0xFFFFFFFF)
}

/**
 * Draws the visitors in painter units (each faces right, centered on its box), mirrored for a
 * left-swimming one. Shapes are built once; animation is transforms only, no allocation.
 */
class VisitorPainter {
    private val ink = Stroke(width = 2.2f, cap = StrokeCap.Round)
    private val thin = Stroke(width = 1.6f, cap = StrokeCap.Round)
    private val neck = Stroke(width = 11f, cap = StrokeCap.Round)

    private val diverBody = Path().apply {
        addRoundRect(RoundRect(Rect(-30f, -8f, 28f, 8f), CornerRadius(8f)))
    }
    private val diverTank = Path().apply {
        addRoundRect(RoundRect(Rect(-20f, -17f, 12f, -8f), CornerRadius(4.5f)))
    }
    private val diverLeg = Path().apply {
        addRoundRect(RoundRect(Rect(-22f, -3f, 0f, 3f), CornerRadius(3f)))
    }
    private val diverFin = Path().apply {
        moveTo(-20f, -2f); lineTo(-36f, -8f); lineTo(-34f, 6f); close()
    }
    private val subHull = Path().apply {
        addRoundRect(RoundRect(Rect(-50f, -14f, 46f, 16f), CornerRadius(15f)))
    }
    private val subTower = Path().apply {
        addRoundRect(RoundRect(Rect(-12f, -27f, 14f, -12f), CornerRadius(5f)))
    }
    private val subFin = Path().apply {
        moveTo(-46f, 0f); lineTo(-58f, -12f); lineTo(-58f, 12f); close()
    }
    private val periscope = Path().apply {
        moveTo(6f, -27f); lineTo(6f, -38f); lineTo(14f, -38f)
    }
    private val nessieBody = Path().apply { addOval(Rect(-34f, -10f, 30f, 16f)) }
    private val nessieBelly = Path().apply { addOval(Rect(-24f, 4f, 22f, 15f)) }
    private val nessieNeck = Path().apply {
        moveTo(20f, 0f); cubicTo(36f, -4f, 40f, -20f, 52f, -24f)
    }
    private val nessieTail = Path().apply {
        moveTo(-30f, -4f); quadraticTo(-56f, 0f, -72f, 10f); quadraticTo(-54f, 10f, -30f, 12f); close()
    }
    private val flipper = Path().apply { addOval(Rect(-6f, -3f, 6f, 3f)) }

    fun DrawScope.drawVisitor(v: PondVisitors) {
        val k = v.kind ?: return
        val a = v.alpha.coerceIn(0f, 1f) * 0.92f
        if (a <= 0f) return
        withTransform({
            translate(v.x, v.y)
            scale(v.scale * v.dir, v.scale, Offset.Zero)
        }) {
            when (k) {
                VisitorKind.DIVER -> diver(v.age, a)
                VisitorKind.SUBMARINE -> submarine(v.age, a)
                VisitorKind.NESSIE -> nessie(v.age, a)
            }
        }
    }

    private fun DrawScope.trail(age: Float, fromX: Float, fromY: Float, a: Float) {
        // Three bubbles rising from the visitor, each recycling every 2.5 s.
        for (i in 0 until 3) {
            val p = (age / 2.5f + i / 3f) % 1f
            val bx = fromX + sin(p * 9f + i) * 3f
            val by = fromY - p * 70f
            drawCircle(VisitorColors.BUBBLE, radius = 2f + p * 2.5f, center = Offset(bx, by), alpha = a * (1f - p), style = thin)
        }
    }

    private fun DrawScope.diver(age: Float, a: Float) {
        val kick = sin(age * 6f) * 14f
        // Back leg, body, tank, front leg: a flutter kick from the hips.
        withTransform({ rotate(kick, Offset(-28f, 2f)) }) {
            withTransform({ translate(-28f, 2f) }) {
                drawPath(diverLeg, VisitorColors.SUIT, alpha = a)
                drawPath(diverFin, VisitorColors.FIN, alpha = a)
            }
        }
        drawPath(diverTank, VisitorColors.TANK, alpha = a)
        drawPath(diverBody, VisitorColors.SUIT, alpha = a)
        withTransform({ rotate(-kick, Offset(-28f, -2f)) }) {
            withTransform({ translate(-28f, -2f) }) {
                drawPath(diverLeg, VisitorColors.SUIT, alpha = a)
                drawPath(diverFin, VisitorColors.FIN, alpha = a)
            }
        }
        drawCircle(VisitorColors.SUIT, radius = 9f, center = Offset(34f, -2f), alpha = a)
        drawRoundRect(VisitorColors.GLASS, topLeft = Offset(35f, -8f), size = Size(10f, 8f), cornerRadius = CornerRadius(3f), alpha = a)
        drawRoundRect(VisitorColors.INK, topLeft = Offset(35f, -8f), size = Size(10f, 8f), cornerRadius = CornerRadius(3f), alpha = a, style = thin)
        drawCircle(VisitorColors.SKIN, radius = 2.4f, center = Offset(41f, 3f), alpha = a)
        trail(age, 44f, -8f, a)
    }

    private fun DrawScope.submarine(age: Float, a: Float) {
        drawPath(subFin, VisitorColors.HULL_SHADE, alpha = a)
        drawPath(subHull, VisitorColors.HULL, alpha = a)
        drawRect(VisitorColors.HULL_SHADE, topLeft = Offset(-44f, 8f), size = Size(84f, 4f), alpha = a * 0.6f)
        drawPath(subTower, VisitorColors.HULL, alpha = a)
        drawPath(periscope, VisitorColors.PORT_RIM, alpha = a, style = ink)
        for (px in floatArrayOf(-26f, -4f, 18f)) {
            drawCircle(VisitorColors.GLASS, radius = 5.5f, center = Offset(px, 1f), alpha = a)
            drawCircle(VisitorColors.PORT_RIM, radius = 5.5f, center = Offset(px, 1f), alpha = a, style = ink)
        }
        // The propeller turns: a blade seen edge-on, widening and narrowing.
        val blade = 2f + 9f * abs(cos(age * 12f))
        drawOval(VisitorColors.PORT_RIM, topLeft = Offset(-62f, -blade), size = Size(5f, 2f * blade), alpha = a)
        trail(age, -60f, -6f, a)
    }

    private fun DrawScope.nessie(age: Float, a: Float) {
        val sway = sin(age * 2f) * 5f
        withTransform({ rotate(sway * 0.6f, Offset(-30f, 4f)) }) {
            drawPath(nessieTail, VisitorColors.NESSIE, alpha = a)
        }
        // Flippers paddle in turn.
        for ((i, fx) in floatArrayOf(-16f, 14f).withIndex()) {
            withTransform({
                translate(fx, 15f)
                rotate(sin(age * 3f + i * 3.1f) * 25f, Offset.Zero)
            }) { drawPath(flipper, VisitorColors.NESSIE, alpha = a) }
        }
        drawPath(nessieBody, VisitorColors.NESSIE, alpha = a)
        drawPath(nessieBelly, VisitorColors.NESSIE_BELLY, alpha = a)
        withTransform({ translate(0f, sway * 0.3f) }) {
            drawPath(nessieNeck, VisitorColors.NESSIE, alpha = a, style = neck)
            drawOval(VisitorColors.NESSIE, topLeft = Offset(46f, -32f), size = Size(20f, 13f), alpha = a)
            drawCircle(VisitorColors.INK, radius = 1.8f, center = Offset(58f, -28f), alpha = a)
            drawArc(
                VisitorColors.INK, startAngle = 20f, sweepAngle = 110f, useCenter = false,
                topLeft = Offset(56f, -29f), size = Size(8f, 6f), alpha = a, style = thin,
            )
        }
        trail(age, 64f, -32f, a)
    }
}
