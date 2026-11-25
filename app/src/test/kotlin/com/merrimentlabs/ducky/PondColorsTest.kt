package com.merrimentlabs.ducky

import com.merrimentlabs.ducky.ui.AppStyle
import com.merrimentlabs.ducky.ui.PRESET_SEEDS
import com.merrimentlabs.ducky.ui.Palette
import com.merrimentlabs.ducky.ui.pond.PondColors
import com.merrimentlabs.ducky.ui.pond.PondMath
import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The readout crosses the water at every level, so its under-water ink must hold contrast
 * against the whole depth gradient for every preset x theme x contrast x style. The 14 sp
 * hint is normal-size text: 4.5:1 (7:1 in high contrast), not the 3:1 large-text allowance.
 */
class PondColorsTest {

    private fun sweep(check: (String, PondColors) -> Unit) {
        for (seed in PRESET_SEEDS) for (dark in listOf(false, true)) for (hc in listOf(false, true))
            for (style in AppStyle.entries) {
                val scheme = if (dark) Palette.dark(seed, style.monochrome, hc)
                else Palette.light(seed, style.monochrome, hc)
                val c = PondColors.from(seed, style, dark, hc, scheme)
                check("seed=%08x dark=$dark hc=$hc style=${style.key}".format(seed), c)
            }
    }

    @Test fun `under-water ink clears the bar across the whole depth`() = sweep { name, c ->
        val worst = PondMath.minContrastOver(c.inkUnder, c.waterTop, c.waterDeep)
        val bar = PondColors.minInkContrast(c.highContrast)
        assertTrue("$name: under-water ink $worst < $bar", worst >= bar)
    }

    @Test fun `above-water ink clears the bar on the sky`() = sweep { name, c ->
        assertTrue("$name: ink", PondMath.contrast(c.inkAbove, c.sky) >= 4.5f)
        assertTrue("$name: soft ink", PondMath.contrast(c.inkAboveSoft, c.sky) >= 4.5f)
    }

    @Test fun `water reads as water, not as sky`() = sweep { name, c ->
        // The body of water must separate from the background it rises against.
        assertTrue("$name: water vs sky", PondMath.contrast(c.waterTop, c.sky) >= 1.25f)
    }

    @Test fun `high contrast always outlines the duck and draws the surface line`() = sweep { name, c ->
        if (c.highContrast) {
            assertTrue("$name: outline", c.duckOutline != null)
            assertTrue("$name: surface line", c.surfaceLine != null)
        }
    }

    @Test fun `best ink picks the stronger of two`() {
        val white = androidx.compose.ui.graphics.Color.White
        val black = androidx.compose.ui.graphics.Color.Black
        assertTrue(PondMath.bestInk(androidx.compose.ui.graphics.Color(0xFF0D47A1), white, black) == white)
        assertTrue(PondMath.bestInk(androidx.compose.ui.graphics.Color(0xFFFFE082), white, black) == black)
    }

    @Test fun `accent buttons keep 4_5 to 1 for their labels at every hue`() {
        // Primary carries 12-22 sp labels on the vivid buttons and the widgets. Swept over the
        // whole custom-color wheel, not only the presets.
        for (hue in 0 until 360) for (mono in listOf(false, true)) {
            val seed = Palette.hsl(hue.toFloat(), 0.8f, 0.5f).toArgb()
            val light = Palette.light(seed, mono, false)
            val dark = Palette.dark(seed, mono, false)
            val l = PondMath.contrast(light.primary, light.onPrimary)
            val d = PondMath.contrast(dark.primary, dark.onPrimary)
            assertTrue("hue=$hue mono=$mono light $l", l >= 4.5f)
            assertTrue("hue=$hue mono=$mono dark $d", d >= 4.5f)
        }
    }
}
