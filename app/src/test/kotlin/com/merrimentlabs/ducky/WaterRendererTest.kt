package com.merrimentlabs.ducky

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.merrimentlabs.ducky.widget.WaterRenderer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Regression for the narrow-box render exception:
 * the duck size clamp inverted below 84 px wide and coerceIn threw, which
 * took the whole responsive widget down on mdpi/hdpi devices.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WaterRendererTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val vessel = 0xFFEEEEEE.toInt()
    private val water = 0xFF1E88E5.toInt()
    private val duck = 0xFFFF0000.toInt()

    private fun render(w: Int, h: Int, fraction: Float, flat: Boolean = false, showDuck: Boolean = true) = WaterRenderer.render(
        context, widthPx = w, heightPx = h, cornerPx = 8f, fraction = fraction,
        vesselColor = vessel, waterColor = water,
        waveHighlight = 0xFF0D47A1.toInt(), duckColor = duck,
        showDuck = showDuck, flat = flat,
    )

    private fun android.graphics.Bitmap.pixels(): IntArray =
        IntArray(width * height).also { getPixels(it, 0, width, 0, 0, width, height) }

    @Test
    fun `narrow boxes render at every width around the old clamp boundary`() {
        for (w in listOf(32, 52, 78, 83, 84, 100, 200)) {
            for (h in listOf(48, 96, 400)) {
                for (f in listOf(0.01f, 0.5f, 1f)) {
                    val bitmap = render(w, h, f)
                    assertEquals("width $w", w, bitmap.width)
                    assertEquals("height $h", h, bitmap.height)
                }
            }
        }
    }

    @Test
    fun `sizes below the floor are clamped up, not rejected`() {
        val bitmap = render(1, 1, 0.5f)
        assertEquals(WaterRenderer.MIN_WIDTH_PX, bitmap.width)
        assertEquals(WaterRenderer.MIN_WIDTH_PX, bitmap.height)
    }

    @Test
    fun `empty water renders without the duck path`() {
        val bitmap = render(40, 40, 0f)
        assertEquals(40, bitmap.width)
    }

    @Test
    fun `flat water is a plain level at the fraction, no waves and no duck`() {
        val bitmap = render(100, 100, 0.5f, flat = true)
        // Level top = (1 - 0.5) * (100 - 4) + 2 = 50.
        assertEquals(vessel, bitmap.getPixel(50, 45))
        assertEquals(water, bitmap.getPixel(50, 55))
        assertEquals(water, bitmap.getPixel(90, 52)) // flat across: no wave crest above it
        assertEquals(vessel, bitmap.getPixel(90, 48))
        assertFalse(bitmap.pixels().any { it == duck })
    }

    @Test
    fun `the duck floats near the right edge when there is room and is left out when not`() {
        val withDuck = render(200, 96, 0.5f)
        val px = withDuck.pixels()
        val duckXs = px.indices.filter { px[it] == duck }.map { it % 200 }
        assertTrue("duck drawn", duckXs.isNotEmpty())
        assertTrue("duck at the right: ${duckXs.min()}", duckXs.min() >= 150)
        assertFalse(render(200, 96, 0.5f, showDuck = false).pixels().any { it == duck })
        // 40x40: the duck would be 7 px, below MIN_DUCK_PX.
        assertFalse(render(40, 40, 0.5f).pixels().any { it == duck })
    }

    @Test
    fun `by default the water has waves and the duck`() {
        val bitmap = WaterRenderer.render(
            context, widthPx = 200, heightPx = 96, cornerPx = 8f, fraction = 0.5f,
            vesselColor = vessel, waterColor = water, waveHighlight = 0xFF0D47A1.toInt(), duckColor = duck,
        )
        assertTrue(bitmap.pixels().any { it == duck })
        // A wave, not a flat level: the water's top edge differs across the width.
        fun waterTop(x: Int) = (0 until 96).first { bitmap.getPixel(x, it) == water }
        assertTrue((0 until 140 step 10).map(::waterTop).distinct().size > 1)
    }

    @Test
    fun `empty water leaves only the vessel`() {
        val bitmap = render(100, 100, 0f)
        assertEquals(vessel, bitmap.getPixel(50, 98))
        assertEquals(vessel, render(100, 100, 0f, flat = true).getPixel(50, 98))
    }
}
