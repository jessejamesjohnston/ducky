package com.merrimentlabs.ducky.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.core.graphics.createBitmap
import com.merrimentlabs.ducky.R
import kotlin.math.sin

/**
 * Draws the widget's water-tank countdown: a rounded vessel filling the box,
 * water at [fraction] height with a two-layer sine-wave surface, and Ducky
 * floating on the waterline. Re-rendered by the display tick chain while the
 * timer runs. CALM by design (chosen after watching it on a phone): between two
 * renders the ONLY thing that changes is the water level: the wave shape
 * is fixed and the duck sits at a fixed spot on the surface. Deriving the
 * phase from the fraction made every tick a snapshot of a different wave,
 * with the duck riding it up and down: that was the widget "jitter".
 */
object WaterRenderer {

    const val MIN_WIDTH_PX = 32
    const val MIN_DUCK_PX = 12

    fun render(
        context: Context,
        widthPx: Int,
        heightPx: Int,
        cornerPx: Float,
        fraction: Float,        // 0..1 water remaining
        vesselColor: Int,
        waterColor: Int,
        waveHighlight: Int,
        duckColor: Int,
        showDuck: Boolean = true,
        flat: Boolean = false,  // high-contrast / monochrome: a level, no waves, no duck
    ): Bitmap {
        val w = widthPx.coerceIn(MIN_WIDTH_PX, 1200)
        val h = heightPx.coerceIn(MIN_WIDTH_PX, 1200)
        val bitmap = createBitmap(w, h)
        val canvas = Canvas(bitmap)

        val vessel = Path().apply {
            addRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), cornerPx, cornerPx, Path.Direction.CW)
        }
        canvas.clipPath(vessel)
        canvas.drawColor(vesselColor)

        val f = fraction.coerceIn(0f, 1f)
        if (f <= 0f) return bitmap

        if (flat) {
            // London and high contrast get no water play: a flat level, so
            // the level stays readable and nothing moves.
            val top = ((1f - f) * (h - 4f)) + 2f
            canvas.drawRect(0f, top, w.toFloat(), h.toFloat(), Paint().apply { color = waterColor })
            return bitmap
        }
        val amp = (h * 0.018f).coerceIn(1f, 5f)
        // Keep even a nearly-drained timer visibly wet.
        val surfaceY = ((1f - f) * (h - amp * 2f - 4f)) + amp + 2f
        // Fixed phase: a stepped re-render cannot animate a wave, it can only
        // jump it. The level is the one thing that moves between ticks.
        val phase = 0.6f

        fun wavePath(phaseShift: Float, yOffset: Float): Path = Path().apply {
            moveTo(0f, surfaceY + yOffset + amp * sin(phaseShift))
            var x = 0f
            while (x <= w) {
                lineTo(x, surfaceY + yOffset + amp * sin(phase + phaseShift + (x / w) * 2f * Math.PI.toFloat()))
                x += w / 48f
            }
            lineTo(w.toFloat(), h.toFloat())
            lineTo(0f, h.toFloat())
            close()
        }

        // Back wave (highlight) peeks above the main body of water.
        canvas.drawPath(wavePath(1.9f, -amp * 0.9f), Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = waveHighlight
            alpha = 48
        })
        canvas.drawPath(wavePath(0f, 0f), Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = waterColor
        })

        // Ducky floats near the RIGHT EDGE so it never sits behind the centered
        // time text (round-2 bug: black duck over the numerals blocked reading).
        // Duck scaled to the vessel AND capped by the width: a 20 px floor
        // against a 24 %-of-width ceiling inverted below 84 px wide and
        // coerceIn threw (compact boxes at mdpi/hdpi: every running tick).
        // Too small to read as a duck → leave the water plain.
        val size = minOf(h * 0.30f, w * 0.18f).toInt()
        if (!showDuck || size < MIN_DUCK_PX) return bitmap
        context.getDrawable(R.drawable.ic_duck)?.mutate()?.let { duck ->
            duck.setTint(duckColor)
            val cx = (w * 0.90f).toInt()
            val duckWaterY = surfaceY + amp * sin(phase + 0.90f * 2f * Math.PI.toFloat())
            // Clamp inside the canvas: at high water the raw position floated
            // the duck above the top edge (round-1 bug: cropped duck).
            val top = (duckWaterY - size * 0.68f)
                .coerceIn(2f, (h - size - 2).toFloat().coerceAtLeast(2f))
                .toInt()
            duck.setBounds(cx - size / 2, top, cx + size / 2, top + size)
            duck.draw(canvas)
        }
        return bitmap
    }
}
