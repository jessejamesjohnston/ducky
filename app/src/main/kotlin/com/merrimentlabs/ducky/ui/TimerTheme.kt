package com.merrimentlabs.ducky.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import com.merrimentlabs.ducky.R
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.glance.color.ColorProviders as GlanceColorProviders
import androidx.glance.material3.ColorProviders
import com.merrimentlabs.ducky.TimerSettings
import com.merrimentlabs.ducky.TimerStore
import kotlin.math.abs

/**
 * The four looks the user can pick. A style governs shape, typography and density; the
 * user's seed color governs the palette
 * (wallpaper-dynamic color can't be read from a widget-first app, so the seed
 * is user-picked in settings).
 */
enum class AppStyle(
    val key: String,
    @param:StringRes val nameRes: Int,
    /** Corner radius for the big increment tiles (999 = pill). */
    val tileCorner: Dp,
    /** London draws buttons as thin outlines on monochrome surfaces. */
    val outlined: Boolean,
    /** Dot-matrix-adjacent clock face for London; default elsewhere. */
    val clockFont: FontFamily,
    /** London shouts its labels. */
    val upperCaseLabels: Boolean,
    /** Neutralize surface tint (London's black/white world). */
    val monochrome: Boolean,
    /** Settings cards / large containers. */
    val cardCorner: Dp,
    /** Widget outer container. */
    val widgetCorner: Dp,
    /** Clock numerals weight: light in Seoul, bold in Beijing. */
    val clockWeight: FontWeight,
    /** Saturated primary buttons instead of tonal containers (Beijing). */
    val vividButtons: Boolean,
    /** A large centered header, content pushed down for reach (Seoul). */
    val bigHeader: Boolean,
    /** Gradient fills on accent surfaces (Beijing). */
    val gradientAccents: Boolean,
) {
    MOUNTAIN_VIEW(
        key = "mountain_view", nameRes = R.string.style_mountain_view,
        tileCorner = 24.dp, outlined = false, clockFont = FontFamily.Default,
        upperCaseLabels = false, monochrome = false,
        cardCorner = 28.dp, widgetCorner = 28.dp,
        clockWeight = FontWeight.Normal, vividButtons = false, bigHeader = false,
        gradientAccents = false,
    ),
    SEOUL(
        key = "seoul", nameRes = R.string.style_seoul,
        tileCorner = 999.dp, outlined = false, clockFont = FontFamily.Default,
        upperCaseLabels = false, monochrome = false,
        cardCorner = 26.dp, widgetCorner = 22.dp,
        clockWeight = FontWeight.Light, vividButtons = false, bigHeader = true,
        gradientAccents = false,
    ),
    LONDON(
        key = "london", nameRes = R.string.style_london,
        tileCorner = 10.dp, outlined = true, clockFont = FontFamily.Monospace,
        upperCaseLabels = true, monochrome = true,
        cardCorner = 14.dp, widgetCorner = 12.dp,
        clockWeight = FontWeight.Normal, vividButtons = false, bigHeader = false,
        gradientAccents = false,
    ),
    BEIJING(
        key = "beijing", nameRes = R.string.style_beijing,
        tileCorner = 18.dp, outlined = false, clockFont = FontFamily.Default,
        upperCaseLabels = false, monochrome = false,
        cardCorner = 20.dp, widgetCorner = 20.dp,
        clockWeight = FontWeight.Bold, vividButtons = true, bigHeader = false,
        gradientAccents = true,
    );

    val tileShape: Shape get() = RoundedCornerShape(tileCorner)

    /**
     * The face this style gives its labels and titles, or null for the theme's interface face
     * (Figtree). Only London letters them in its monospace.
     */
    val labelFont: FontFamily? get() = clockFont.takeIf { it != FontFamily.Default }

    /** Small controls (steppers): circle when the style is pill-based. */
    val controlShape: Shape
        get() = if (tileCorner >= 100.dp) CircleShape
        else RoundedCornerShape(tileCorner / 2)

    companion object {
        fun fromKey(key: String?): AppStyle =
            entries.firstOrNull { it.key == key } ?: MOUNTAIN_VIEW
    }
}

val LocalAppStyle = staticCompositionLocalOf { AppStyle.MOUNTAIN_VIEW }

/** The 12 preset accent seeds offered in settings (custom wheel adds the rest). */
val PRESET_SEEDS = listOf(
    0xFFD32F2F.toInt(), // red
    0xFFF4511E.toInt(), // orange
    0xFFF9A825.toInt(), // amber
    0xFF9E9D24.toInt(), // olive
    0xFF43A047.toInt(), // green
    0xFF007D5D.toInt(), // teal
    0xFF0097A7.toInt(), // cyan
    TimerStore.DEFAULT_SEED, // water blue (default: Ducky's element)
    0xFF3949AB.toInt(), // indigo
    0xFF8E24AA.toInt(), // purple
    0xFFD81B60.toInt(), // pink
    0xFF6D4C41.toInt(), // brown
)

/**
 * Hand-rolled Material 3 style tonal schemes from a seed hue. The lightness and saturation
 * recipe was derived from a hand-tuned teal design, so a teal seed reproduces that design
 * closely.
 */
object Palette {

    fun light(
        seedArgb: Int,
        monochrome: Boolean,
        highContrast: Boolean = false,
    ): ColorScheme {
        val h = hueOf(seedArgb)
        val m = if (monochrome) 0.06f else 1f // surface saturation multiplier
        if (highContrast) {
            // Accessibility variant: pure-white surfaces, near-black text,
            // darker primary, hard outlines: WCAG-comfortable everywhere.
            return lightColorScheme(
                primary = hsl(h, 1.00f, 0.22f),
                onPrimary = Color.White,
                primaryContainer = hsl(h, 0.55f, 0.85f),
                onPrimaryContainer = hsl(h, 1.00f, 0.05f),
                background = Color.White,
                onBackground = Color.Black,
                surface = Color.White,
                onSurface = Color.Black,
                surfaceVariant = hsl(h, 0.20f * m, 0.90f),
                onSurfaceVariant = hsl(h, 0.15f * m, 0.18f),
                surfaceContainerHigh = hsl(h, 0.20f * m, 0.90f),
                surfaceContainerHighest = hsl(h, 0.17f * m, 0.83f),
                outline = hsl(h, 0.10f * m, 0.30f),
            )
        }
        val onPrimary = hsl(h, 0.50f * m, 0.98f)
        return lightColorScheme(
            // Yellow-green hues are light at a fixed lightness (olive was 3.8:1 against its
            // label): darken just enough to keep button and widget labels at 4.5:1.
            primary = darkenToContrast(h, 0.95f, 0.27f, onPrimary, 4.5f),
            onPrimary = onPrimary,
            primaryContainer = hsl(h, 0.55f, 0.80f),
            onPrimaryContainer = hsl(h, 1.00f, 0.10f),
            background = hsl(h, 0.37f * m, 0.96f),
            onBackground = hsl(h, 0.23f * m, 0.12f),
            surface = hsl(h, 0.60f * m, 0.99f),
            onSurface = hsl(h, 0.23f * m, 0.12f),
            surfaceVariant = hsl(h, 0.23f * m, 0.91f),
            onSurfaceVariant = hsl(h, 0.08f * m, 0.35f),
            surfaceContainerHigh = hsl(h, 0.23f * m, 0.91f),
            surfaceContainerHighest = hsl(h, 0.17f * m, 0.86f),
            outline = hsl(h, 0.07f * m, 0.66f),
        )
    }

    fun dark(
        seedArgb: Int,
        monochrome: Boolean,
        highContrast: Boolean = false,
    ): ColorScheme {
        val h = hueOf(seedArgb)
        val m = if (monochrome) 0.06f else 1f
        if (highContrast) {
            return darkColorScheme(
                primary = hsl(h, 0.65f, 0.72f),
                onPrimary = Color.Black,
                primaryContainer = hsl(h, 0.50f, 0.28f),
                onPrimaryContainer = hsl(h, 0.60f, 0.94f),
                background = Color.Black,
                onBackground = Color.White,
                surface = hsl(h, 0.10f * m, 0.04f),
                onSurface = Color.White,
                surfaceVariant = hsl(h, 0.12f * m, 0.16f),
                onSurfaceVariant = hsl(h, 0.08f * m, 0.86f),
                surfaceContainerHigh = hsl(h, 0.12f * m, 0.16f),
                surfaceContainerHighest = hsl(h, 0.11f * m, 0.22f),
                outline = hsl(h, 0.08f * m, 0.72f),
            )
        }
        return darkColorScheme(
            primary = hsl(h, 0.55f, 0.64f),
            onPrimary = hsl(h, 0.74f, 0.10f),
            primaryContainer = hsl(h, 0.59f, 0.22f),
            onPrimaryContainer = hsl(h, 0.58f, 0.82f),
            background = hsl(h, 0.17f * m, 0.06f),
            onBackground = hsl(h, 0.15f * m, 0.90f),
            surface = hsl(h, 0.12f * m, 0.09f),
            onSurface = hsl(h, 0.15f * m, 0.90f),
            surfaceVariant = hsl(h, 0.12f * m, 0.13f),
            onSurfaceVariant = hsl(h, 0.05f * m, 0.64f),
            surfaceContainerHigh = hsl(h, 0.12f * m, 0.13f),
            surfaceContainerHighest = hsl(h, 0.11f * m, 0.18f),
            outline = hsl(h, 0.05f * m, 0.34f),
        )
    }

    fun glance(settings: TimerSettings): GlanceColorProviders {
        val style = AppStyle.fromKey(settings.styleKey)
        return ColorProviders(
            light = light(settings.seedColorArgb, style.monochrome, settings.highContrast),
            dark = dark(settings.seedColorArgb, style.monochrome, settings.highContrast),
        )
    }

    /** [hsl] at [startLightness], darkened in small steps until it reaches [target] against [ink]. */
    private fun darkenToContrast(hue: Float, sat: Float, startLightness: Float, ink: Color, target: Float): Color {
        var l = startLightness
        var c = hsl(hue, sat, l)
        while (l > 0.05f && com.merrimentlabs.ducky.ui.pond.PondMath.contrast(c, ink) < target) {
            l -= 0.005f
            c = hsl(hue, sat, l)
        }
        return c
    }

    fun hsl(hue: Float, sat: Float, lightness: Float): Color {
        val h = ((hue % 360f) + 360f) % 360f
        val c = (1f - abs(2f * lightness - 1f)) * sat.coerceIn(0f, 1f)
        val x = c * (1f - abs((h / 60f) % 2f - 1f))
        val m = lightness - c / 2f
        val (r, g, b) = when {
            h < 60f -> Triple(c, x, 0f)
            h < 120f -> Triple(x, c, 0f)
            h < 180f -> Triple(0f, c, x)
            h < 240f -> Triple(0f, x, c)
            h < 300f -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        return Color(
            (r + m).coerceIn(0f, 1f),
            (g + m).coerceIn(0f, 1f),
            (b + m).coerceIn(0f, 1f),
        )
    }

    fun hueOf(argb: Int): Float {
        val r = ((argb shr 16) and 0xFF) / 255f
        val g = ((argb shr 8) and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val d = max - min
        if (d == 0f) return 0f
        val h = when (max) {
            r -> ((g - b) / d) % 6f
            g -> (b - r) / d + 2f
            else -> (r - g) / d + 4f
        }
        return ((h * 60f) + 360f) % 360f
    }
}

@Composable
fun TimerTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val settings by TimerStore.settingsFlow(context).collectAsState(initial = null)
    val style = AppStyle.fromKey(settings?.styleKey)
    val seed = settings?.seedColorArgb ?: TimerStore.DEFAULT_SEED
    val hc = settings?.highContrast ?: false
    val dark = isSystemInDarkTheme()
    val scheme = remember(seed, style, dark, hc) {
        if (dark) Palette.dark(seed, style.monochrome, hc)
        else Palette.light(seed, style.monochrome, hc)
    }
    CompositionLocalProvider(LocalAppStyle provides style) {
        MaterialTheme(colorScheme = scheme, typography = DuckyTypography, content = content)
    }
}
