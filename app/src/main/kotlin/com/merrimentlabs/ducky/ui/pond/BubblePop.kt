package com.merrimentlabs.ducky.ui.pond

import android.graphics.Bitmap
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
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
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.core.graphics.createBitmap
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * The "Bubbles" widget's picture: the duck floats at the left of a pond band with a row of
 * soap bubbles beside it, and one bubble pops each time another slice of the timer passes.
 * The row shortens from the far end toward the duck, so the bubbles left are the time left.
 * In the last minute the remaining bubbles turn golden; at 0:00 the last one pops and the duck
 * celebrates; paused, it naps.
 *
 * A pop is a discrete event, so a widget that can only change its picture every 5 s or so
 * shows it as intended. Between pops the picture does not change at all ([StepFrame]).
 */
object BubblePop {
    const val MIN_PX = 32

    /** Geometry for one box, in pixels. Pure, so tests can check it at every widget size. */
    class Layout(
        val width: Float,
        val height: Float,
        /** Top of the pond band; the clock and subtitle sit above it. */
        val bandTop: Float,
        /** A small box: a thin strip with small bubbles. */
        val strip: Boolean,
        val waterTop: Float,
        val duckX: Float,
        val duckWaterline: Float,
        val duckScale: Float,
        val bubbleX: FloatArray,
        val bubbleY: Float,
        val bubbleR: Float,
    ) {
        val count: Int get() = bubbleX.size
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
        val waterTop = bandTop + band * (if (strip) 0.50f else 0.55f)
        // The duck floats inside the band: its head never reaches the clock above.
        val duckH = band * (if (strip) 0.68f else 0.62f)
        val duckScale = duckH / (DuckPainter.BOTTOM - DuckPainter.TOP)
        val duckWaterline = waterTop + band * (if (strip) 0.12f else 0.04f)
        val duckX = margin - DuckPainter.LEFT * duckScale
        val start = duckX + DuckPainter.RIGHT * duckScale + (if (strip) 3f else 6f) * density
        val usable = (widthPx - margin - start).coerceAtLeast(1f)
        val count = (usable / (band * (if (strip) 0.95f else 0.55f))).toInt().coerceIn(3, 8)
            .coerceAtMost(maxCount).coerceAtLeast(1)
        val spacing = usable / count
        val r = min(spacing * 0.42f, band * (if (strip) 0.26f else 0.24f)).coerceAtLeast(0.5f)
        return Layout(
            width = widthPx, height = heightPx, bandTop = bandTop, strip = strip,
            waterTop = waterTop, duckX = duckX, duckWaterline = duckWaterline, duckScale = duckScale,
            bubbleX = FloatArray(count) { start + spacing * (it + 0.5f) },
            bubbleY = waterTop - r * 0.4f, bubbleR = r,
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
            clipPath(clip) { drawScene(l, frame, colors, BubbleColors.of(colors, mono)) }
        }
        return bitmap
    }

    private fun DrawScope.drawScene(l: Layout, f: StepFrame, c: PondColors, k: BubbleColors) {
        val d = density
        val band = l.height - l.bandTop
        drawRect(c.sky)

        // Water: a calm band with a fixed gentle swell (the same picture every render).
        val amp = min(2f * d, band * 0.05f)
        fun swell(x: Float) = l.waterTop + amp * sin(2f * PI.toFloat() * x / (90f * d) + 0.6f)
        val surface = Path().apply {
            moveTo(0f, swell(0f))
            var x = 0f
            while (x <= l.width) { lineTo(x, swell(x)); x += 3f * d }
            lineTo(l.width, l.height); lineTo(0f, l.height); close()
        }
        drawPath(surface, Brush.verticalGradient(0f to c.waterTop, 1f to c.waterDeep, startY = l.waterTop, endY = l.height))
        val crest = Path().apply {
            moveTo(0f, swell(0f))
            var x = 0f
            while (x <= l.width) { lineTo(x, swell(x)); x += 3f * d }
        }
        drawPath(crest, c.surfaceLine ?: c.crest, alpha = if (c.surfaceLine != null) 1f else 0.7f,
            style = Stroke(width = (if (c.surfaceLine != null) 2f else 1.4f) * d, cap = StrokeCap.Round))

        val n = l.count
        val left = f.left.coerceIn(0, n)
        // Where bubbles have popped, a flat ring stays on the water: the row keeps its length,
        // so the share left reads at a glance.
        for (i in left until n) {
            val r = l.bubbleR
            drawOval(k.ripple, topLeft = Offset(l.bubbleX[i] - r, l.waterTop + r * 0.12f),
                size = Size(2f * r, r * 0.36f), alpha = 0.8f, style = Stroke(1.2f * d))
        }
        // The bubble that popped last gets a little burst until the next one goes.
        if ((f.mood == HopMood.RUNNING || f.mood == HopMood.PAUSED) && f.gone > 0 && left < n) {
            drawPop(l.bubbleX[left], l.bubbleY, l.bubbleR, k)
        }
        for (i in 0 until left) drawBubble(l.bubbleX[i], l.bubbleY, l.bubbleR, f.lastMinute, k)

        val pose = DuckPose().apply {
            facing = 1f
            when {
                f.mood == HopMood.IDLE -> { eyeOpen = 1f; lookX = -0.2f; lookY = 0.25f }
                f.mood == HopMood.PAUSED -> { eyeOpen = 0f; lookX = 0f; lookY = 0.5f }
                f.mood == HopMood.DONE -> { eyeOpen = 1f; lookX = 0.4f; lookY = -1f; wing = 1f; beak = 0.7f; rotation = -8f }
                f.lastMinute -> { eyeOpen = 1f; lookX = 1f; lookY = -0.4f; wing = 0.45f; beak = 0.25f }
                else -> { eyeOpen = 1f; lookX = 0.8f; lookY = 0f; wing = 0.1f }
            }
        }
        if (f.mood == HopMood.DONE) drawCelebration(l, k)
        with(DuckPainter()) { drawDuck(pose, l.duckX, l.duckWaterline, l.duckScale, c.duckOutline) }
        if (f.mood == HopMood.PAUSED) drawZs(l, c.inkAboveSoft)
    }

    private fun DrawScope.drawBubble(cx: Float, cy: Float, r: Float, golden: Boolean, k: BubbleColors) {
        val d = density
        drawCircle(if (golden) k.goldFill else k.fill, radius = r, center = Offset(cx, cy))
        drawCircle(if (golden) k.goldRim else k.rim, radius = r, center = Offset(cx, cy),
            style = Stroke(width = (if (k.outline != null) 2f else 1.4f) * d))
        // The shine: an arc at the top left and a small dot, as on a soap bubble.
        drawArc(k.shine, startAngle = 200f, sweepAngle = 60f, useCenter = false,
            topLeft = Offset(cx - r * 0.68f, cy - r * 0.68f), size = Size(r * 1.36f, r * 1.36f),
            style = Stroke(width = (r * 0.14f).coerceAtLeast(0.8f * d), cap = StrokeCap.Round))
        drawCircle(k.shine, radius = (r * 0.09f).coerceAtLeast(0.6f * d), center = Offset(cx + r * 0.38f, cy + r * 0.34f))
    }

    private fun DrawScope.drawPop(cx: Float, cy: Float, r: Float, k: BubbleColors) {
        val d = density
        // Short rays where the bubble was: a still picture of the pop.
        for (i in 0 until 6) {
            val a = (i * 60f + 15f) * PI.toFloat() / 180f
            val inner = r * 0.55f
            val outer = r * 1.0f
            drawLine(k.rim, Offset(cx + inner * cos(a), cy + inner * sin(a)), Offset(cx + outer * cos(a), cy + outer * sin(a)),
                strokeWidth = 1.3f * d, cap = StrokeCap.Round, alpha = 0.85f)
        }
    }

    private fun DrawScope.drawCelebration(l: Layout, k: BubbleColors) {
        val d = density
        // Fixed spots (a picture, not an animation): small bubbles rising from the empty row.
        val spots = floatArrayOf(0.10f, 0.55f, 0.24f, 0.20f, 0.38f, 0.62f, 0.52f, 0.28f, 0.66f, 0.50f, 0.80f, 0.16f, 0.92f, 0.46f)
        val startX = l.bubbleX.first() - l.bubbleR
        val spanX = (l.width - startX).coerceAtLeast(1f)
        val top = l.bandTop + 2f * d
        val spanY = (l.waterTop - top).coerceAtLeast(1f)
        for (i in 0 until spots.size / 2) {
            val r = l.bubbleR * (if (i % 2 == 0) 0.45f else 0.32f)
            val center = Offset(startX + spanX * spots[2 * i], top + spanY * spots[2 * i + 1])
            drawCircle(k.fill, radius = r, center = center)
            drawCircle(k.rim, radius = r, center = center, style = Stroke(1.1f * d))
        }
    }

    private fun DrawScope.drawZs(l: Layout, ink: Color) {
        val d = density
        val zPath = Path()
        // Beside the head and inside the band: above it is the clock's space.
        for ((i, size) in listOf(4.5f, 6f).withIndex()) {
            val z = size * d
            val zx = l.duckX + (DuckPainter.RIGHT - 6f) * l.duckScale + i * 7f * d
            val zy = (l.duckWaterline + (DuckPainter.TOP + 2f) * l.duckScale - i * 6f * d).coerceAtLeast(l.bandTop + 1f * d)
            zPath.rewind()
            zPath.moveTo(zx, zy); zPath.lineTo(zx + z, zy); zPath.lineTo(zx, zy + z); zPath.lineTo(zx + z, zy + z)
            drawPath(zPath, ink, alpha = 0.8f - i * 0.2f, style = Stroke(width = 1.4f * d, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

/** Bubble colors: tinted by the accent's water, golden in the last minute. */
internal class BubbleColors(
    val fill: Color,
    val rim: Color,
    val shine: Color,
    val ripple: Color,
    val goldFill: Color,
    val goldRim: Color,
    val outline: Color?,
) {
    companion object {
        /** The marigold of merrimentlabs.com and its deeper edge, the same in both themes. */
        private val MARIGOLD = Color(0xFFF6B23C)
        private val MARIGOLD_EDGE = Color(0xFFC9761A)

        /**
         * A bubble is its rim: push it toward black (light theme) or white (dark theme) until it
         * clears 3:1 against the sky, the bar for graphics you need to see (pale accents fell
         * short in light mode).
         */
        private fun standOut(color: Color, sky: Color, dark: Boolean): Color {
            var c = color
            var t = 0f
            while (PondMath.contrast(c, sky) < 3.2f && t < 1f) {
                t += 0.05f
                c = lerp(color, if (dark) Color.White else Color.Black, t)
            }
            return c
        }

        fun of(c: PondColors, mono: Boolean): BubbleColors {
            val dark = c.dark
            // High contrast: a crisp rim in the theme's ink (the duck's outline stays dark).
            val ink = if (c.highContrast) (if (dark) Color.White else DuckColors.INK) else null
            val rim = standOut(when {
                ink != null -> ink
                mono -> if (dark) Color(0xFFE0E0E0) else Color(0xFF616161)
                dark -> lerp(c.waterTop, Color.White, 0.55f)
                else -> lerp(c.waterTop, c.waterDeep, 0.85f)
            }, c.sky, dark)
            return BubbleColors(
                fill = if (mono) Color.White.copy(alpha = if (dark) 0.14f else 0.45f)
                else lerp(c.waterTop, Color.White, if (dark) 0.25f else 0.55f).copy(alpha = if (dark) 0.45f else 0.6f),
                rim = rim,
                shine = Color.White.copy(alpha = if (dark) 0.85f else 0.95f),
                ripple = c.crest,
                goldFill = if (mono) (if (dark) Color(0xFFBDBDBD) else Color(0xFF9E9E9E)).copy(alpha = 0.75f)
                else MARIGOLD.copy(alpha = 0.8f),
                goldRim = standOut(if (ink != null && !dark) ink else if (mono) (if (dark) Color.White else Color(0xFF212121)) else MARIGOLD_EDGE, c.sky, dark),
                outline = ink,
            )
        }
    }
}
