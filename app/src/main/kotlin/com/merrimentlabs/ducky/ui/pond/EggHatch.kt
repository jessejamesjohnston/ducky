package com.merrimentlabs.ducky.ui.pond

import android.graphics.Bitmap
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.core.graphics.createBitmap
import kotlin.math.min

/**
 * The "Egg timer" widget's picture: a row of eggs in a nest on the bank, the duck beside them,
 * and one egg hatching into a duckling each time another slice of the timer passes. Eggs hatch
 * from the duck's side outward, so the eggs left are the time left and the yellow row of
 * ducklings is the time gone. In the last minute the eggs left glow and wobble with a crack;
 * at 0:00 the last one hatches and everyone cheers; paused, they all nap.
 *
 * A hatch is a discrete event, so a widget that can only change its picture every 5 s or so
 * shows it as intended. Between hatches the picture does not change at all ([StepFrame]).
 */
object EggHatch {
    const val MIN_PX = 32

    private const val DUCK_UNITS_TALL = DuckPainter.BOTTOM - DuckPainter.TOP

    /** Geometry for one box, in pixels. Pure, so tests can check it at every widget size. */
    class Layout(
        val width: Float,
        val height: Float,
        /** Top of the pond band; the clock and subtitle sit above it. */
        val bandTop: Float,
        /** A small box: a thin strip with eggs only (no room for the grown duck). */
        val strip: Boolean,
        val waterTop: Float,
        val bankTop: Float,
        /** The grown duck's waterline point and scale; null in a strip. */
        val mamaX: Float?,
        val mamaWaterline: Float,
        val mamaScale: Float,
        val eggX: FloatArray,
        /** Bottom of every egg (and of every duckling's shell). */
        val eggBase: Float,
        val eggW: Float,
        val eggH: Float,
        val nestLeft: Float,
        val nestRight: Float,
        val nestTop: Float,
        val nestBottom: Float,
    ) {
        val count: Int get() = eggX.size
        val ducklingScale: Float get() = eggH * 0.98f / DUCK_UNITS_TALL
        val ducklingWaterline: Float get() = eggBase - eggH * 0.05f - DuckPainter.BOTTOM * ducklingScale
    }

    /** The largest bitmap side [render] draws; bigger boxes are scaled up by the launcher. */
    private const val MAX_PX = 1200

    /**
     * How many items fit a box of this size, counted at the size [render] actually draws
     * (clamped to [MIN_PX]..[MAX_PX]), so a frame and its picture always agree.
     */
    fun widthCount(widthPx: Int, heightPx: Int, density: Float): Int =
        layout(widthPx.coerceIn(MIN_PX, MAX_PX).toFloat(), heightPx.coerceIn(MIN_PX, MAX_PX).toFloat(), density).count

    /** [maxCount]: the timer's own cap ([StepFrame.itemsFor]); the width decides the rest. */
    fun layout(widthPx: Float, heightPx: Float, density: Float, maxCount: Int = 8): Layout {
        val band = LilyHop.bandDp(heightPx / density) * density
        val strip = heightPx / density < 96f
        val bandTop = heightPx - band
        val margin = (if (strip) 5f else 10f) * density
        val bankTop = bandTop + band * (if (strip) 0.55f else 0.50f)
        val mamaScale = band * 0.62f / DUCK_UNITS_TALL
        val mamaBottom = bandTop + band * 0.80f
        val mamaX = if (strip) null else margin - DuckPainter.LEFT * mamaScale
        val start = if (mamaX == null) margin else mamaX + DuckPainter.RIGHT * mamaScale + 6f * density
        val usable = (widthPx - margin - start).coerceAtLeast(1f)
        val count = (usable / (band * (if (strip) 0.9f else 0.5f))).toInt().coerceIn(3, 8)
            .coerceAtMost(maxCount).coerceAtLeast(1)
        val spacing = usable / count
        val eggW = min(spacing * 0.72f, band * (if (strip) 0.48f else 0.34f)).coerceAtLeast(0.5f)
        val eggH = eggW * 1.28f
        val eggBase = bandTop + band * (if (strip) 0.86f else 0.80f)
        return Layout(
            width = widthPx, height = heightPx, bandTop = bandTop, strip = strip,
            waterTop = bandTop + band * 0.18f, bankTop = bankTop,
            mamaX = mamaX, mamaWaterline = mamaBottom - DuckPainter.BOTTOM * mamaScale, mamaScale = mamaScale,
            eggX = FloatArray(count) { start + spacing * (it + 0.5f) },
            eggBase = eggBase, eggW = eggW, eggH = eggH,
            nestLeft = start + spacing * 0.08f, nestRight = start + usable - spacing * 0.08f,
            nestTop = eggBase - eggH * 0.22f, nestBottom = (eggBase + eggH * 0.16f).coerceAtMost(heightPx - 1f),
        )
    }

    fun render(
        frame: StepFrame,
        colors: PondColors,
        mono: Boolean,
        widthPx: Int,
        heightPx: Int,
        density: Float,
        cornerPx: Float,
    ): Bitmap {
        val w = widthPx.coerceIn(MIN_PX, MAX_PX)
        val h = heightPx.coerceIn(MIN_PX, MAX_PX)
        val l = layout(w.toFloat(), h.toFloat(), density, frame.count)
        val bitmap = createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val size = Size(w.toFloat(), h.toFloat())
        val clip = Path().apply { addRoundRect(RoundRect(0f, 0f, size.width, size.height, CornerRadius(cornerPx))) }
        CanvasDrawScope().draw(Density(density), LayoutDirection.Ltr, Canvas(bitmap.asImageBitmap()), size) {
            clipPath(clip) { drawScene(l, frame, colors, EggColors.of(colors, mono)) }
        }
        return bitmap
    }

    private fun DrawScope.drawScene(l: Layout, f: StepFrame, c: PondColors, k: EggColors) {
        val d = density
        val band = l.height - l.bandTop
        drawRect(c.sky)

        // The pond behind the bank, then the bank itself.
        drawRect(Brush.verticalGradient(0f to c.waterTop, 1f to c.waterDeep, startY = l.waterTop, endY = l.bankTop),
            topLeft = Offset(0f, l.waterTop), size = Size(l.width, l.bankTop - l.waterTop + band * 0.1f))
        c.surfaceLine?.let {
            drawLine(it, Offset(0f, l.waterTop), Offset(l.width, l.waterTop), strokeWidth = 2f * d)
        }
        val bank = Path().apply {
            moveTo(0f, l.bankTop + band * 0.06f)
            quadraticTo(l.width * 0.5f, l.bankTop - band * 0.08f, l.width, l.bankTop + band * 0.06f)
            lineTo(l.width, l.height); lineTo(0f, l.height); close()
        }
        drawPath(bank, k.bank)
        k.outline?.let { drawPath(bank, it, style = Stroke(1.2f * d)) }

        // The nest: a low straw bowl under the whole row.
        val nest = RoundRect(l.nestLeft, l.nestTop, l.nestRight, l.nestBottom, CornerRadius((l.nestBottom - l.nestTop) / 2f))
        drawRoundRect(k.nest, topLeft = Offset(nest.left, nest.top), size = Size(nest.width, nest.height),
            cornerRadius = CornerRadius(nest.height / 2f))

        val n = l.count
        val gone = f.gone.coerceIn(0, n)
        val asleep = f.mood == HopMood.PAUSED
        val cheering = f.mood == HopMood.DONE
        // Ducklings first (the duck's side), then the eggs still to hatch.
        for (i in 0 until gone) {
            val newest = i == gone - 1 && (f.mood == HopMood.RUNNING || f.mood == HopMood.PAUSED)
            drawDuckling(l, l.eggX[i], asleep, cheering || (newest && !asleep), c, k)
            if (newest) drawShards(l, l.eggX[i], k)
        }
        for (i in gone until n) {
            if (f.lastMinute) drawGlow(l, l.eggX[i], k)
            val tilt = if (f.lastMinute) (if ((i - gone) % 2 == 0) -12f else 12f) else 0f
            drawEgg(l, l.eggX[i], tilt, f.lastMinute, k)
        }
        // Straw strands over the front of the bowl, so eggs and ducklings sit in it.
        drawStraw(l, k)

        l.mamaX?.let { x ->
            val pose = DuckPose().apply {
                facing = 1f
                when {
                    f.mood == HopMood.IDLE -> { eyeOpen = 1f; lookX = -0.2f; lookY = 0.25f }
                    asleep -> { eyeOpen = 0f; lookX = 0f; lookY = 0.5f }
                    cheering -> { eyeOpen = 1f; lookX = 0.4f; lookY = -1f; wing = 1f; beak = 0.7f; rotation = -8f }
                    f.lastMinute -> { eyeOpen = 1f; lookX = 1f; lookY = 0.3f; wing = 0.45f; beak = 0.2f }
                    else -> { eyeOpen = 1f; lookX = 1f; lookY = 0.5f; wing = 0.1f }
                }
            }
            with(DuckPainter()) { drawDuck(pose, x, l.mamaWaterline, l.mamaScale, c.duckOutline) }
        }
        if (cheering) drawConfetti(l, k)
        if (asleep) drawZs(l, c.inkAboveSoft)
    }

    private fun eggPath(cx: Float, base: Float, w: Float, h: Float): Path = Path().apply {
        // Narrower at the top, as eggs are.
        val top = base - h
        moveTo(cx, top)
        cubicTo(cx + w * 0.42f, top, cx + w * 0.52f, base - h * 0.42f, cx + w * 0.5f, base - h * 0.32f)
        cubicTo(cx + w * 0.48f, base - h * 0.08f, cx + w * 0.26f, base, cx, base)
        cubicTo(cx - w * 0.26f, base, cx - w * 0.48f, base - h * 0.08f, cx - w * 0.5f, base - h * 0.32f)
        cubicTo(cx - w * 0.52f, base - h * 0.42f, cx - w * 0.42f, top, cx, top)
        close()
    }

    private fun DrawScope.drawEgg(l: Layout, cx: Float, tilt: Float, cracked: Boolean, k: EggColors) {
        val d = density
        val w = l.eggW
        val h = l.eggH
        val base = l.eggBase
        rotate(degrees = tilt, pivot = Offset(cx, base)) {
            val egg = eggPath(cx, base, w, h)
            drawPath(egg, k.shell)
            drawPath(egg, k.outline ?: k.shellEdge, style = Stroke(width = (if (k.outline != null) 1.6f else 1f) * d))
            // Fixed speckles and a shine.
            for ((sx, sy, sr) in listOf(Triple(-0.18f, 0.55f, 0.07f), Triple(0.16f, 0.40f, 0.055f), Triple(0.05f, 0.72f, 0.05f))) {
                drawCircle(k.speckle, radius = (w * sr).coerceAtLeast(0.6f * d), center = Offset(cx + w * sx, base - h * sy))
            }
            drawOval(Color.White.copy(alpha = 0.7f), topLeft = Offset(cx - w * 0.30f, base - h * 0.86f), size = Size(w * 0.16f, h * 0.22f))
            if (cracked) {
                // A zigzag crack across the middle: it is about to hatch.
                val y = base - h * 0.58f
                val crack = Path().apply {
                    moveTo(cx - w * 0.42f, y)
                    lineTo(cx - w * 0.22f, y - h * 0.08f)
                    lineTo(cx - w * 0.06f, y + h * 0.05f)
                    lineTo(cx + w * 0.12f, y - h * 0.09f)
                    lineTo(cx + w * 0.28f, y + h * 0.03f)
                }
                drawPath(crack, k.crack, style = Stroke(width = (w * 0.07f).coerceAtLeast(1f * d), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
        if (cracked) {
            // Wobble marks either side of the egg.
            val y = base - h * 0.62f
            for (side in listOf(-1f, 1f)) {
                val x = cx + side * w * 0.70f
                drawArc(k.wobble, startAngle = if (side < 0) 150f else -30f, sweepAngle = 60f, useCenter = false,
                    topLeft = Offset(x - w * 0.14f, y - h * 0.12f), size = Size(w * 0.28f, h * 0.24f),
                    style = Stroke(width = 1.2f * d, cap = StrokeCap.Round))
            }
        }
    }

    private fun DrawScope.drawGlow(l: Layout, cx: Float, k: EggColors) {
        val r = l.eggH * 0.95f
        val center = Offset(cx, l.eggBase - l.eggH * 0.5f)
        drawCircle(Brush.radialGradient(0f to k.glow, 0.55f to k.glow.copy(alpha = k.glow.alpha * 0.45f), 1f to Color.Transparent,
            center = center, radius = r), radius = r, center = center)
    }

    private fun DrawScope.drawDuckling(l: Layout, cx: Float, asleep: Boolean, cheep: Boolean, c: PondColors, k: EggColors) {
        val pose = DuckPose().apply {
            facing = -1f // toward the grown duck
            eyeOpen = if (asleep) 0f else 1f
            lookX = 0.3f
            lookY = if (asleep) 0.5f else -0.2f
            if (cheep) { beak = 0.75f; wing = 0.5f }
        }
        with(DuckPainter()) { drawDuck(pose, cx, l.ducklingWaterline, l.ducklingScale, c.duckOutline) }
        // The bottom half of its shell, with a zigzag rim.
        val w = l.eggW * 1.06f
        val base = l.eggBase
        val rim = base - l.eggH * 0.40f
        val cup = Path().apply {
            moveTo(cx - w * 0.5f, rim)
            val teeth = 5
            for (t in 1..teeth) {
                val x = cx - w * 0.5f + w * t / teeth
                lineTo(x - w / teeth / 2f, rim - l.eggH * 0.10f)
                lineTo(x, rim)
            }
            cubicTo(cx + w * 0.5f, base - l.eggH * 0.1f, cx + w * 0.26f, base, cx, base)
            cubicTo(cx - w * 0.26f, base, cx - w * 0.5f, base - l.eggH * 0.1f, cx - w * 0.5f, rim)
            close()
        }
        drawPath(cup, k.shell)
        drawPath(cup, k.outline ?: k.shellEdge, style = Stroke(width = (if (k.outline != null) 1.6f else 1f) * density, join = StrokeJoin.Round))
    }

    private fun DrawScope.drawShards(l: Layout, cx: Float, k: EggColors) {
        // Two pieces of the top of the shell, dropped beside the newest duckling.
        val s = l.eggW * 0.22f
        for ((dx, angle) in listOf(0.62f to 20f, -0.6f to -30f)) {
            val x = cx + l.eggW * dx
            val y = l.nestTop + s * 0.2f
            withTransform({ rotate(angle, Offset(x, y)) }) {
                val shard = Path().apply {
                    moveTo(x - s, y); lineTo(x - s * 0.4f, y - s * 0.7f); lineTo(x, y - s * 0.2f)
                    lineTo(x + s * 0.5f, y - s * 0.8f); lineTo(x + s, y); close()
                }
                drawPath(shard, k.shell)
                drawPath(shard, k.outline ?: k.shellEdge, style = Stroke(0.9f * density, join = StrokeJoin.Round))
            }
        }
    }

    private fun DrawScope.drawStraw(l: Layout, k: EggColors) {
        val d = density
        val top = l.nestTop + (l.nestBottom - l.nestTop) * 0.35f
        val bottom = l.nestBottom
        val front = Rect(l.nestLeft, top, l.nestRight, bottom)
        drawRoundRect(k.nest, topLeft = front.topLeft, size = front.size, cornerRadius = CornerRadius(front.height / 2f))
        // A few crossing strands: fixed, so every render is the same picture.
        val step = 7f * d
        var x = l.nestLeft + step * 0.5f
        var i = 0
        while (x < l.nestRight - step * 0.5f) {
            val slope = if (i % 2 == 0) 1f else -1f
            drawLine(k.straw, Offset(x - step * 0.6f * slope, top + front.height * 0.15f), Offset(x + step * 0.6f * slope, bottom - front.height * 0.2f),
                strokeWidth = 1.1f * d, cap = StrokeCap.Round)
            x += step
            i++
        }
        k.outline?.let { drawRoundRect(it, topLeft = front.topLeft, size = front.size, cornerRadius = CornerRadius(front.height / 2f), style = Stroke(1.2f * d)) }
    }

    private fun DrawScope.drawZs(l: Layout, ink: Color) {
        val d = density
        val zPath = Path()
        // Over the duck's head (or the first egg in a strip), inside the band.
        val baseX = l.mamaX?.let { it + (DuckPainter.RIGHT - 4f) * l.mamaScale } ?: (l.eggX.first() + l.eggW * 0.5f)
        val baseY = l.mamaX?.let { l.mamaWaterline + (DuckPainter.TOP + 4f) * l.mamaScale } ?: (l.eggBase - l.eggH)
        for ((i, size) in listOf(4.5f, 6f).withIndex()) {
            val z = size * d
            val zx = baseX + i * 7f * d
            val zy = (baseY - i * 6f * d).coerceAtLeast(l.bandTop + 1f * d)
            zPath.rewind()
            zPath.moveTo(zx, zy); zPath.lineTo(zx + z, zy); zPath.lineTo(zx, zy + z); zPath.lineTo(zx + z, zy + z)
            drawPath(zPath, ink, alpha = 0.8f - i * 0.2f, style = Stroke(width = 1.4f * d, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }

    private fun DrawScope.drawConfetti(l: Layout, k: EggColors) {
        val d = density
        // Fixed spots above the row, inside the band: above it is the "Time's up" text.
        val left = l.eggX.first() - l.eggW
        val spanX = (l.width - left).coerceAtLeast(1f)
        val top = l.bandTop + 2f * d
        val spanY = ((l.eggBase - l.eggH * 1.25f) - top).coerceAtLeast(1f)
        val spots = floatArrayOf(0.06f, 0.5f, 0.2f, 0.1f, 0.33f, 0.62f, 0.47f, 0.2f, 0.6f, 0.7f, 0.74f, 0.05f, 0.88f, 0.45f, 0.97f, 0.15f)
        for (i in 0 until spots.size / 2) {
            val p = Offset(left + spanX * spots[2 * i], top + spanY * spots[2 * i + 1])
            val color = k.confetti[i % k.confetti.size]
            if (i % 3 == 0) drawCircle(color, radius = 2.2f * d, center = p)
            else withTransform({ rotate(i * 37f, p) }) {
                drawRect(color, topLeft = Offset(p.x - 3f * d, p.y - 1.3f * d), size = Size(6f * d, 2.6f * d))
            }
        }
    }
}

/** Egg-widget colors: cream shells, a straw nest, the bank's green. Fixed, not accent. */
internal class EggColors(
    val shell: Color,
    val shellEdge: Color,
    val speckle: Color,
    val crack: Color,
    val wobble: Color,
    val glow: Color,
    val nest: Color,
    val straw: Color,
    val bank: Color,
    val confetti: List<Color>,
    val outline: Color?,
) {
    companion object {
        fun of(c: PondColors, mono: Boolean): EggColors {
            val dark = c.dark
            val hop = HopColors.of(c, mono)
            return EggColors(
                shell = when {
                    mono -> if (dark) Color(0xFFE0E0E0) else Color(0xFFF7F7F7)
                    dark -> Color(0xFFF1E6D2)
                    else -> Color(0xFFFFF8EA)
                },
                shellEdge = if (mono) Color(0xFF8A8A8A) else if (dark) Color(0xFFB9A27C) else Color(0xFFCDB48C),
                speckle = (if (mono) Color(0xFF9E9E9E) else Color(0xFFBFA27A)).copy(alpha = 0.7f),
                crack = DuckColors.INK.copy(alpha = 0.8f),
                wobble = c.inkAboveSoft,
                // The sun color of merrimentlabs.com: the eggs about to hatch sit in its light.
                glow = when {
                    mono -> (if (dark) Color.White else Color(0xFF616161)).copy(alpha = 0.45f)
                    dark -> Color(0xFFFFC766).copy(alpha = 0.75f)
                    else -> Color(0xFFFFD27A).copy(alpha = 0.9f)
                },
                nest = when {
                    mono -> if (dark) Color(0xFF6A6A6A) else Color(0xFFA6A6A6)
                    dark -> Color(0xFF8A643A)
                    else -> Color(0xFFC8955A)
                },
                straw = when {
                    mono -> if (dark) Color(0xFF4A4A4A) else Color(0xFF7A7A7A)
                    dark -> Color(0xFF5E4326)
                    else -> Color(0xFF9A6A35)
                },
                bank = hop.shore,
                confetti = listOf(hop.flag, hop.petal, DuckColors.BODY, c.waterTop),
                outline = c.duckOutline,
            )
        }
    }
}
