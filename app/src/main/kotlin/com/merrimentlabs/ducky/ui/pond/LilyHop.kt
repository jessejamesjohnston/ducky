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
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/** What the widget is showing; the picture follows the widget's own display mode. */
enum class HopMood { IDLE, RUNNING, PAUSED, DONE }

/**
 * The "Ducky Pond" widget's picture: a row of lily pads across a pond band at the bottom of the
 * time box, and the duck hopping one pad along each time another slice of the timer passes.
 * Pads the duck has left bloom. At 0:00 it stands on the shore by the finish flag and
 * celebrates; paused, it naps on its pad.
 *
 * Built for widgets, whose pictures can only change every few seconds (the tick floor is
 * AlarmManager's 5 s): a hop is a discrete event, so a stepped update reads as intended rather
 * than as a stuttering animation. Between hops the picture does not change at all.
 */
object LilyHop {
    const val MIN_PX = 32
    const val MAX_PX = 1200

    private const val DUCK_UNITS_TALL = DuckPainter.BOTTOM - DuckPainter.TOP

    /** Geometry for one box, in pixels. Pure, so tests can check it at every widget size. */
    class Layout(
        val width: Float,
        val height: Float,
        /** Top of the pond band; the clock and subtitle sit above it. */
        val bandTop: Float,
        /** A small box: a thin strip with small pads and no reeds. */
        val strip: Boolean,
        val padX: FloatArray,
        val padY: Float,
        val padW: Float,
        val padH: Float,
        /** Left edge of the shore at the right end (the finish). */
        val shoreX: Float,
        val shoreTop: Float,
        val duckScale: Float,
    ) {
        val padCount: Int get() = padX.size
    }

    /** Height of the pond band for a time box, in dp; the widget fits its text above it. */
    fun bandDp(boxHeightDp: Float): Float {
        val band = if (boxHeightDp >= 96f) (boxHeightDp * 0.42f).coerceIn(40f, 84f)
        else (boxHeightDp * 0.30f).coerceIn(14f, 22f)
        // The clock always keeps at least 30 dp.
        return band.coerceAtMost((boxHeightDp - 30f).coerceAtLeast(0f))
    }

    /**
     * How many pads a timer of [totalMillis] gets in a box: as many as fit (3 to 8), but no more
     * than one per [StepFrame.MIN_SLICE_MS], the same cap as the counted looks: renders can be
     * 5.8 s apart, so a shorter slice would let the duck hop two pads at once (a 30 s timer gets
     * 4 pads, a minute or longer every pad that fits, under 14 s one). Counted at the size
     * [render] draws, so the widget's frame and the picture agree.
     */
    fun padCountFor(widthPx: Int, heightPx: Int, density: Float, totalMillis: Long): Int {
        val fit = layout(
            widthPx.coerceIn(MIN_PX, MAX_PX).toFloat(), heightPx.coerceIn(MIN_PX, MAX_PX).toFloat(), density,
        ).padCount
        return StepFrame.itemsFor(fit, totalMillis)
    }

    /** [maxPads] lowers the count that fits (see [padCountFor]); the pads spread to fill the row. */
    fun layout(widthPx: Float, heightPx: Float, density: Float, maxPads: Int = 8): Layout {
        val band = bandDp(heightPx / density) * density
        val strip = heightPx / density < 96f
        val bandTop = heightPx - band
        val margin = (if (strip) 5f else 10f) * density
        val shoreW = band * (if (strip) 1.1f else 1.0f)
        val usable = (widthPx - 2f * margin - shoreW).coerceAtLeast(1f)
        val count = (usable / (band * (if (strip) 1.3f else 0.58f))).toInt().coerceIn(3, 8)
            .coerceAtMost(maxPads.coerceAtLeast(1))
        val spacing = usable / count
        val padX = FloatArray(count) { margin + spacing * (it + 0.5f) }
        val padW = min(spacing * 0.86f, band * (if (strip) 1.0f else 0.92f))
        val padY = bandTop + band * 0.62f
        // Band mode: the duck's head stays inside the band, clear of the text above it.
        // Strip mode: a band that thin can't hold a readable duck, so it may peek above.
        val duckH = band * (if (strip) 0.8f else 0.62f)
        return Layout(
            width = widthPx, height = heightPx, bandTop = bandTop, strip = strip,
            padX = padX, padY = padY, padW = padW, padH = padW * 0.36f,
            shoreX = widthPx - margin * 0.5f - shoreW, shoreTop = bandTop + band * 0.38f,
            duckScale = duckH / DUCK_UNITS_TALL,
        )
    }

    /** Which pad the duck is on at [progress] (0 = just started, 1 = done). */
    fun hopIndex(progress: Float, padCount: Int): Int =
        (progress.coerceIn(0f, 1f) * padCount).toInt().coerceIn(0, padCount - 1)

    fun render(
        mood: HopMood,
        progress: Float,
        colors: PondColors,
        mono: Boolean,
        widthPx: Int,
        heightPx: Int,
        density: Float,
        cornerPx: Float,
        /** The pad count from [padCountFor]; the default draws every pad that fits. */
        maxPads: Int = 8,
    ): Bitmap {
        val w = widthPx.coerceIn(MIN_PX, MAX_PX)
        val h = heightPx.coerceIn(MIN_PX, MAX_PX)
        val l = layout(w.toFloat(), h.toFloat(), density, maxPads)
        val bitmap = createBitmap(w, h)
        val size = Size(w.toFloat(), h.toFloat())
        val clip = Path().apply { addRoundRect(RoundRect(0f, 0f, size.width, size.height, CornerRadius(cornerPx))) }
        CanvasDrawScope().draw(Density(density), LayoutDirection.Ltr, Canvas(bitmap.asImageBitmap()), size) {
            clipPath(clip) { drawScene(l, mood, progress, colors, HopColors.of(colors, mono)) }
        }
        return bitmap
    }

    private fun DrawScope.drawScene(l: Layout, mood: HopMood, progress: Float, c: PondColors, k: HopColors) {
        val d = density
        val band = l.height - l.bandTop
        drawRect(c.sky)

        // Water: a calm band with a fixed gentle swell along its top edge.
        val waterTop = l.bandTop + band * 0.2f
        val amp = min(2f * d, band * 0.05f)
        val surface = Path().apply {
            moveTo(0f, waterTop)
            var x = 0f
            while (x <= l.width) {
                lineTo(x, waterTop + amp * sin(2f * PI.toFloat() * x / (90f * d) + 0.6f))
                x += 3f * d
            }
            lineTo(l.width, l.height); lineTo(0f, l.height); close()
        }
        drawPath(surface, Brush.verticalGradient(0f to c.waterTop, 1f to c.waterDeep, startY = waterTop, endY = l.height))
        val crest = Path().apply {
            moveTo(0f, waterTop)
            var x = 0f
            while (x <= l.width) {
                lineTo(x, waterTop + amp * sin(2f * PI.toFloat() * x / (90f * d) + 0.6f))
                x += 3f * d
            }
        }
        drawPath(crest, c.surfaceLine ?: c.crest, alpha = if (c.surfaceLine != null) 1f else 0.7f,
            style = Stroke(width = (if (c.surfaceLine != null) 2f else 1.4f) * d, cap = StrokeCap.Round))

        drawShore(l, k, band)

        val here = when (mood) {
            HopMood.IDLE -> 0
            HopMood.DONE -> l.padCount // on the shore
            else -> hopIndex(progress, l.padCount)
        }
        for (i in 0 until l.padCount) {
            drawPad(l.padX[i], l.padY, l.padW, l.padH, k)
            // The pads it has left bloom: progress you can read at a glance.
            if (i < here) drawFlower(l.padX[i] - l.padW * 0.12f, l.padY - l.padH * 0.12f, l.padH * 0.55f, k)
        }
        // A ripple ring around the pad it just landed on.
        if (mood != HopMood.DONE) {
            val r = l.padW * 0.66f
            drawOval(c.crest, topLeft = Offset(l.padX[here] - r, l.padY - r * 0.36f),
                size = Size(2f * r, r * 0.72f), alpha = 0.55f, style = Stroke(1.2f * d))
        }

        val pose = DuckPose().apply {
            facing = 1f
            when (mood) {
                HopMood.IDLE -> { eyeOpen = 1f; lookX = -0.2f; lookY = 0.25f }
                HopMood.RUNNING -> { eyeOpen = 1f; lookX = 0.8f; lookY = 0f; wing = 0.1f }
                HopMood.PAUSED -> { eyeOpen = 0f; lookX = 0f; lookY = 0.5f }
                HopMood.DONE -> { eyeOpen = 1f; lookX = 0.4f; lookY = -1f; wing = 1f; beak = 0.7f; rotation = -8f }
            }
        }
        val s = l.duckScale
        val (dx, bottom) = if (mood == HopMood.DONE) {
            (l.shoreX + (l.width - l.shoreX) * 0.42f) to (l.shoreTop + 2f * d)
        } else {
            l.padX[here] to (l.padY + l.padH * 0.12f)
        }
        val waterline = bottom - DuckPainter.BOTTOM * s
        if (mood == HopMood.DONE) drawConfetti(dx, waterline, s, l, k)
        with(DuckPainter()) { drawDuck(pose, dx, waterline, s, c.duckOutline) }
        if (mood == HopMood.PAUSED) drawZs(dx, waterline, s, l, c.inkAboveSoft)
    }

    private fun DrawScope.drawPad(cx: Float, cy: Float, w: Float, h: Float, k: HopColors) {
        // An ellipse with the classic lily-pad notch, seen from a low angle.
        val pad = Path().apply {
            moveTo(cx, cy)
            arcTo(Rect(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f), 18f, 324f, false)
            close()
        }
        drawOval(k.padShadow, topLeft = Offset(cx - w / 2f, cy - h / 2f + h * 0.18f), size = Size(w, h))
        drawPath(pad, k.pad)
        drawLine(k.padVein, Offset(cx, cy), Offset(cx - w * 0.36f, cy - h * 0.18f), strokeWidth = h * 0.07f, cap = StrokeCap.Round)
        drawLine(k.padVein, Offset(cx, cy), Offset(cx - w * 0.2f, cy + h * 0.3f), strokeWidth = h * 0.07f, cap = StrokeCap.Round)
        drawLine(k.padVein, Offset(cx, cy), Offset(cx + w * 0.12f, cy - h * 0.34f), strokeWidth = h * 0.07f, cap = StrokeCap.Round)
        k.outline?.let { drawPath(pad, it, style = Stroke(width = 1.2f * density, join = StrokeJoin.Round)) }
    }

    private fun DrawScope.drawFlower(cx: Float, cy: Float, r: Float, k: HopColors) {
        for (i in 0 until 5) {
            rotate(degrees = i * 72f, pivot = Offset(cx, cy)) {
                drawOval(k.petal, topLeft = Offset(cx - r * 0.28f, cy - r), size = Size(r * 0.56f, r))
            }
        }
        drawCircle(k.flowerCenter, radius = r * 0.3f, center = Offset(cx, cy))
    }

    private fun DrawScope.drawShore(l: Layout, k: HopColors, band: Float) {
        val d = density
        val left = l.shoreX
        val w = l.width - left
        // A grassy bank rising out of the water at the right edge.
        drawOval(k.shore, topLeft = Offset(left, l.shoreTop), size = Size(w * 1.6f, band * 1.4f))
        k.outline?.let { drawOval(it, topLeft = Offset(left, l.shoreTop), size = Size(w * 1.6f, band * 1.4f), style = Stroke(1.2f * d)) }
        if (!l.strip) {
            // Two reeds at the back of the bank.
            for ((fx, fh) in listOf(0.72f to 0.62f, 0.86f to 0.48f)) {
                val x = left + w * fx
                drawLine(k.reed, Offset(x, l.shoreTop + band * 0.1f), Offset(x, l.shoreTop - band * fh), strokeWidth = 1.6f * d, cap = StrokeCap.Round)
                drawRoundRect(k.cattail, topLeft = Offset(x - 2.2f * d, l.shoreTop - band * fh - band * 0.02f),
                    size = Size(4.4f * d, band * 0.2f), cornerRadius = CornerRadius(2.2f * d))
            }
        }
        // The finish flag.
        val poleX = left + w * 0.28f
        val poleTop = l.shoreTop - band * (if (l.strip) 0.55f else 0.62f)
        drawLine(k.pole, Offset(poleX, l.shoreTop + band * 0.08f), Offset(poleX, poleTop), strokeWidth = 1.4f * d, cap = StrokeCap.Round)
        val flag = Path().apply {
            moveTo(poleX, poleTop)
            lineTo(poleX + band * 0.34f, poleTop + band * 0.1f)
            lineTo(poleX, poleTop + band * 0.2f)
            close()
        }
        drawPath(flag, k.flag)
    }

    private fun DrawScope.drawZs(x: Float, waterline: Float, s: Float, l: Layout, ink: Color) {
        val d = density
        val zPath = Path()
        // Beside the head and inside the band: above it is the clock's space.
        for ((i, size) in listOf(4.5f, 6f).withIndex()) {
            val z = size * d
            val zx = x + (DuckPainter.RIGHT + 2f) * s + i * 7f * d
            val zy = (waterline + (DuckPainter.TOP + 6f) * s - i * 6f * d).coerceAtLeast(l.bandTop + 1f * d)
            zPath.rewind()
            zPath.moveTo(zx, zy); zPath.lineTo(zx + z, zy); zPath.lineTo(zx, zy + z); zPath.lineTo(zx + z, zy + z)
            drawPath(zPath, ink, alpha = 0.8f - i * 0.2f, style = Stroke(width = 1.4f * d, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }

    private fun DrawScope.drawConfetti(x: Float, waterline: Float, s: Float, l: Layout, k: HopColors) {
        val d = density
        // Inside the band: above it is the "Time's up" text.
        val top = l.bandTop + 2f * density
        val left = (l.shoreX - (l.width - l.shoreX) * 1.4f).coerceAtLeast(0f)
        // Fixed spots (a picture, not an animation): the same burst every render.
        val spots = floatArrayOf(
            0.08f, 0.30f, 0.22f, 0.05f, 0.36f, 0.42f, 0.50f, 0.12f, 0.63f, 0.36f,
            0.74f, 0.02f, 0.86f, 0.26f, 0.95f, 0.10f, 0.15f, 0.62f, 0.44f, 0.70f,
        )
        val palette = listOf(k.flag, k.petal, DuckColors.BODY, k.confettiAccent)
        for (i in 0 until spots.size / 2) {
            val px = left + (l.width - left) * spots[2 * i]
            val py = top + (waterline - top) * spots[2 * i + 1]
            val color = palette[i % palette.size]
            if (i % 3 == 0) drawCircle(color, radius = 2.2f * d, center = Offset(px, py))
            else withTransform({ rotate(i * 37f, Offset(px, py)) }) {
                drawRect(color, topLeft = Offset(px - 3f * d, py - 1.3f * d), size = Size(6f * d, 2.6f * d))
            }
        }
        // A little splash where it jumped out of the water.
        val sx = l.shoreX - (l.width - l.shoreX) * 0.1f
        for (i in 0 until 4) {
            drawCircle(k.droplet, radius = (1.6f + i % 2) * d, center = Offset(sx - i * 4f * d, l.padY - (6f + i * 5f) * d))
        }
    }
}

/** Pond-widget colors beyond the pond's own: pads, blooms, the bank. Fixed greens, not accent. */
internal class HopColors(
    val pad: Color,
    val padShadow: Color,
    val padVein: Color,
    val petal: Color,
    val flowerCenter: Color,
    val shore: Color,
    val reed: Color,
    val cattail: Color,
    val pole: Color,
    val flag: Color,
    val droplet: Color,
    val confettiAccent: Color,
    val outline: Color?,
) {
    companion object {
        fun of(c: PondColors, mono: Boolean): HopColors {
            val dark = c.dark
            val pad = when {
                mono -> if (dark) Color(0xFF6E6E6E) else Color(0xFF9A9A9A)
                dark -> Color(0xFF3F8F52)
                else -> Color(0xFF5DB06C)
            }
            return HopColors(
                pad = pad,
                padShadow = Color.Black.copy(alpha = if (dark) 0.35f else 0.14f),
                padVein = if (mono) Color.White.copy(alpha = 0.35f) else Color(0xFF2F7A40).copy(alpha = 0.7f),
                petal = if (mono) Color(0xFFEDEDED) else Color(0xFFF48FB1),
                flowerCenter = if (mono) Color(0xFFBDBDBD) else DuckColors.BODY,
                shore = when {
                    mono -> if (dark) Color(0xFF5A5A5A) else Color(0xFFB0B0B0)
                    dark -> Color(0xFF4E7F45)
                    else -> Color(0xFF8CC57A)
                },
                reed = if (mono) Color(0xFF7A7A7A) else Color(0xFF6B8E4E),
                cattail = if (mono) Color(0xFF555555) else Color(0xFF8D6E63),
                pole = if (dark) Color(0xFFCFCFCF) else Color(0xFF6D5A4E),
                flag = if (mono) (if (dark) Color.White else Color(0xFF424242)) else DuckColors.BEAK,
                droplet = c.droplet,
                confettiAccent = c.waterTop,
                outline = c.duckOutline,
            )
        }
    }
}
