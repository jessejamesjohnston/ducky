package com.merrimentlabs.ducky.ui.pond

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import com.merrimentlabs.ducky.ui.AppStyle
import com.merrimentlabs.ducky.ui.Palette

/**
 * Every color the pond draws, derived from the user's accent and style. The water takes the
 * accent; the duck never does (it is the same yellow character everywhere, see [DuckColors]).
 *
 * Legibility is built in, not checked after: the readout's under-water ink is chosen first
 * (dark ink on light-theme water, light ink on dark-theme water, as on merrimentlabs.com),
 * then both water stops are pushed in lightness until that ink clears 4.5:1 (7:1 in high
 * contrast) across the whole depth gradient. PondColorsTest sweeps every preset.
 */
data class PondColors(
    val sky: Color,
    val haze: Color,
    val waterTop: Color,
    val waterDeep: Color,
    val backWave: Color,
    val crest: Color,
    val shaft: Color,
    val bubble: Color,
    val droplet: Color,
    val floorShade: Color,
    val inkAbove: Color,
    val inkAboveSoft: Color,
    val inkUnder: Color,
    /** High contrast / London: a crisp line on the surface. */
    val surfaceLine: Color?,
    /** London: dot-matrix texture on the water. */
    val dots: Color?,
    /** High contrast: a crisp outline around the duck. */
    val duckOutline: Color?,
    val dark: Boolean,
    val highContrast: Boolean,
) {
    companion object {
        /** Minimum contrast of the under-water ink against every water color. */
        fun minInkContrast(highContrast: Boolean) = if (highContrast) 7f else 4.5f

        fun from(
            seedArgb: Int,
            style: AppStyle,
            dark: Boolean,
            highContrast: Boolean,
            scheme: ColorScheme,
        ): PondColors {
            val h = Palette.hueOf(seedArgb)
            val mono = style.monochrome
            val satMul = if (mono) 0f else 1f
            val baseSat = when (style) {
                AppStyle.SEOUL -> 0.50f
                AppStyle.BEIJING -> 0.82f
                else -> 0.62f
            } * satMul

            // Ink first, then fit the water to it.
            val inkUnder = when {
                highContrast && dark -> Color.White
                highContrast -> Color.Black
                dark -> Palette.hsl(h, 0.30f * satMul, 0.97f)
                else -> Palette.hsl(h, 0.45f * satMul, 0.08f)
            }
            val target = minInkContrast(highContrast) + 0.15f // small margin for rounding

            // Starting lightness per theme/style (top of the water, deep water).
            val (topL, deepL) = when {
                highContrast && dark -> 0.26f to 0.10f
                highContrast -> 0.80f to 0.64f
                dark && mono -> 0.30f to 0.12f
                dark -> 0.36f to 0.14f
                mono -> 0.82f to 0.62f
                style == AppStyle.SEOUL -> 0.78f to 0.58f
                style == AppStyle.BEIJING -> 0.68f to 0.46f
                else -> 0.72f to 0.50f
            }
            val sat = if (highContrast) 0.70f * satMul else baseSat
            var waterTop = fit(h, sat, topL, inkUnder, target)
            // Pale hues (amber, olive) can end up nearly the sky's color in light themes:
            // deepen the top stop until the water separates from the background, as long as
            // the ink still clears its bar.
            if (!dark) {
                var l = topL
                while (PondMath.contrast(waterTop, scheme.background) < 1.3f && l > 0.3f) {
                    l -= 0.01f
                    val next = Palette.hsl(h, sat, l)
                    if (PondMath.contrast(next, inkUnder) < target) break
                    waterTop = next
                }
            }
            val waterDeep = fit(h, (sat + 0.06f).coerceAtMost(1f), deepL, inkUnder, target)

            val white = Color.White
            return PondColors(
                sky = scheme.background,
                haze = lerp(scheme.background, waterTop, if (dark) 0.22f else 0.30f),
                waterTop = waterTop,
                waterDeep = waterDeep,
                backWave = lerp(waterTop, white, if (dark) 0.14f else 0.30f),
                crest = if (dark) lerp(waterTop, white, 0.50f) else white,
                shaft = white.copy(alpha = if (dark) 0.07f else 0.16f),
                bubble = white.copy(alpha = if (dark) 0.50f else 0.75f),
                droplet = lerp(waterTop, white, if (dark) 0.35f else 0.45f),
                floorShade = lerp(waterDeep, Color.Black, if (dark) 0.35f else 0.18f),
                inkAbove = scheme.onSurface,
                inkAboveSoft = scheme.onSurfaceVariant,
                inkUnder = inkUnder,
                surfaceLine = when {
                    highContrast -> scheme.primary
                    mono -> scheme.primary
                    else -> null
                },
                dots = if (mono && !highContrast) {
                    if (dark) white.copy(alpha = 0.10f) else Color.Black.copy(alpha = 0.10f)
                } else null,
                duckOutline = if (highContrast) DuckColors.INK else null,
                dark = dark,
                highContrast = highContrast,
            )
        }

        /**
         * Nudge lightness from [startL] until [ink] clears [minContrast] against the color.
         * Dark ink pushes the water lighter; light ink pushes it darker.
         */
        internal fun fit(h: Float, s: Float, startL: Float, ink: Color, minContrast: Float): Color {
            val inkIsLight = ink.luminance() > 0.5f
            var l = startL
            var c = Palette.hsl(h, s, l)
            var guard = 0
            while (PondMath.contrast(c, ink) < minContrast && guard < 100) {
                l = if (inkIsLight) (l - 0.01f).coerceAtLeast(0f) else (l + 0.01f).coerceAtMost(1f)
                c = Palette.hsl(h, s, l)
                guard++
            }
            return c
        }
    }
}

/** The duck is always the merrimentlabs.com duck: these never follow the accent. */
object DuckColors {
    val BODY = Color(0xFFFFD34D)
    val SHADE = Color(0xFFF6B23C)
    val WING = Color(0xFFF7BF45)
    val WING_LINE = Color(0xFFE0952B)
    val BEAK = Color(0xFFF0694A)
    val BEAK_DARK = Color(0xFFC94E34)
    val INK = Color(0xFF221A14)
    val CHEEK = Color(0xFFFF8A65)
    val EDGE = Color(0xFFD9892A)
}
