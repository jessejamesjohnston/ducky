// Glance has no Compose UI LocalConfiguration or LocalResources: a widget renders in the
// launcher's process, so it reads the host context's resources on every render.
@file:android.annotation.SuppressLint("LocalContextConfigurationRead", "LocalContextResourcesRead")

package com.merrimentlabs.ducky.widget

import android.content.res.Configuration
import android.graphics.Paint
import android.graphics.Typeface
import android.os.SystemClock
import android.text.format.DateUtils
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.AndroidRemoteViews
import androidx.glance.appwidget.cornerRadius
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.merrimentlabs.ducky.MainActivity
import com.merrimentlabs.ducky.R
import com.merrimentlabs.ducky.TimerMath
import com.merrimentlabs.ducky.TimerSettings
import com.merrimentlabs.ducky.TimerState
import com.merrimentlabs.ducky.ui.AppStyle
import com.merrimentlabs.ducky.ui.Palette
import com.merrimentlabs.ducky.ui.pond.BubblePop
import com.merrimentlabs.ducky.ui.pond.EggHatch
import com.merrimentlabs.ducky.ui.pond.HopMood
import com.merrimentlabs.ducky.ui.pond.LilyHop
import com.merrimentlabs.ducky.ui.pond.PondColors
import com.merrimentlabs.ducky.ui.pond.StepFrame

/**
 * The time block every layout shares: a water tank. The vessel holds accent
 * water at remaining/total height with a waved surface and Ducky floating on
 * it; the display tick chain re-renders it as the timer drains. Taps open
 * the app. (Water level/wave are drawn as a bitmap: Glance can't shape-fill.)
 */
@Composable
internal fun TimeContainer(
    state: TimerState,
    settings: TimerSettings,
    style: AppStyle,
    timeSp: Float,
    subtitleSp: Int,
    corner: Dp,
    boxWidth: Dp,
    boxHeight: Dp,
    modifier: GlanceModifier,
) {
    val mode = modeOf(state)
    val context = LocalContext.current
    val night = (context.resources.configuration.uiMode and
        Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    val seedArgb = settings.seedColorArgb
    val scheme = if (night) Palette.dark(seedArgb, style.monochrome, settings.highContrast)
    else Palette.light(seedArgb, style.monochrome, settings.highContrast)
    val now = System.currentTimeMillis()
    val fraction = when (mode) {
        DisplayMode.RUNNING ->
            if (state.totalMillis > 0)
                (state.endTimeMillis - now).coerceAtLeast(0).toFloat() / state.totalMillis
            else 0f
        DisplayMode.PAUSED ->
            if (state.totalMillis > 0)
                state.pausedRemainingMillis.toFloat() / state.totalMillis
            else 0f
        DisplayMode.RINGING, DisplayMode.IDLE -> 0f
    }
    val effCorner = minOf(style.tileCorner, corner)
    val metrics = context.resources.displayMetrics
    val density = metrics.density
    // Size for the longest clock in this timer, so ticks and pause never resize it. The live
    // remaining time counts too: after the wall clock is set back, it can exceed the total.
    val runningLeft = if (mode == DisplayMode.RUNNING) (state.endTimeMillis - now).coerceAtLeast(0) else 0L
    val sample = DateUtils.formatElapsedTime(
        maxOf(state.totalMillis, state.pausedRemainingMillis, runningLeft) / 1000L
    )
        .map { if (it.isDigit()) '8' else it }.joinToString("")
    val clockPaint = Paint().apply {
        typeface = Typeface.create(if (style.monochrome) "monospace" else "sans-serif-medium", Typeface.NORMAL)
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, timeSp, metrics)
    }
    val subtitleHeight = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, subtitleSp.toFloat(), metrics) * 1.3f
    val look = LocalLook.current
    val pond = look != WidgetLook.TANK
    // The pond looks keep a band at the bottom for their picture; the text fits above it.
    val bandDp = if (pond) LilyHop.bandDp(boxHeight.value) else 0f
    val fittedPx = clockPaint.textSize * minOf(
        1f,
        ((boxWidth.value - 12f) * density) / clockPaint.measureText(sample),
        ((boxHeight.value - bandDp - 8f) * density - subtitleHeight).coerceAtLeast(8f * density) /
            (clockPaint.fontMetrics.descent - clockPaint.fontMetrics.ascent),
    )
    val textWidth = clockPaint.measureText(sample) * fittedPx / clockPaint.textSize
    val duckWidth = minOf(boxHeight.value * 0.30f, boxWidth.value * 0.18f) * density
    val duckFits = boxWidth.value * density * 0.40f - duckWidth / 2f - textWidth / 2f >= 8f * density
    // London (monochrome) and high contrast get no water play: a flat neutral level instead of blue waves + duck.
    val flat = settings.highContrast || style.monochrome
    val pondColors = if (pond) remember(seedArgb, style.key, night, settings.highContrast) {
        PondColors.from(seedArgb, style, night, settings.highContrast, scheme)
    } else null
    // The text sits on the sky above the pond band.
    val ink = pondColors?.inkAbove
    val softInk = pondColors?.inkAboveSoft
    // The mood follows this widget's own display mode, so picture and text always agree.
    val hopMood = when (mode) {
        DisplayMode.RUNNING -> HopMood.RUNNING
        DisplayMode.PAUSED -> HopMood.PAUSED
        DisplayMode.RINGING -> HopMood.DONE
        DisplayMode.IDLE -> HopMood.IDLE
    }
    val progress = if (mode == DisplayMode.RUNNING || mode == DisplayMode.PAUSED) 1f - fraction else 0f
    // Lily pads: a short timer gets fewer pads, so the duck never hops two at once.
    val pads = if (look == WidgetLook.LILY_PADS) LilyHop.padCountFor(
        (boxWidth.value * density).toInt(), (boxHeight.value * density).toInt(), density, state.totalMillis,
    ) else 0
    val hop = if (look == WidgetLook.LILY_PADS) LilyHop.hopIndex(progress, pads) else 0
    // The counted looks: which still to show. Keyed on the frame, never the fraction, so the
    // picture is redrawn only when a bubble pops or an egg hatches.
    val stepFrame = when (look) {
        WidgetLook.BUBBLES, WidgetLook.EGGS -> {
            val remainingMillis = when (mode) {
                DisplayMode.RUNNING -> (state.endTimeMillis - now).coerceAtLeast(0)
                DisplayMode.PAUSED -> state.pausedRemainingMillis
                DisplayMode.RINGING, DisplayMode.IDLE -> 0L
            }
            // Just past the deadline, before the ring is recorded: keep the last item.
            val frameMood = if (mode == DisplayMode.IDLE &&
                StepFrame.ringPending(state.endTimeMillis, now, state.isPaused, state.isRinging)
            ) HopMood.RUNNING else hopMood
            // Counted at the size the renderer draws (it clamps big boxes), so the frame and the
            // picture agree on how many items there are.
            val w = (boxWidth.value * density).toInt()
            val h = (boxHeight.value * density).toInt()
            val widthCount = if (look == WidgetLook.BUBBLES) BubblePop.widthCount(w, h, density)
            else EggHatch.widthCount(w, h, density)
            // A short timer gets fewer items, so each one can be shown going on its own.
            val count = StepFrame.itemsFor(widthCount, state.totalMillis)
            StepFrame.of(frameMood, remainingMillis, state.totalMillis, count)
        }
        else -> null
    }
    // Off unless enabled: `adb shell setprop log.tag.DuckyFrames DEBUG`. One line per
    // composition, so a run can be traced frame by frame on any build, R8 included.
    if (Log.isLoggable(FRAME_TAG, Log.DEBUG)) {
        val picture = when {
            stepFrame != null -> "left=${stepFrame.left}/${stepFrame.count} last=${stepFrame.lastMinute}"
            look == WidgetLook.LILY_PADS -> "pad=$hop/$pads"
            else -> "fraction=$fraction"
        }
        Log.d(FRAME_TAG, "look=$look box=${boxWidth.value.toInt()}x${boxHeight.value.toInt()} mode=$mode " +
            "remaining=${if (mode == DisplayMode.RUNNING) state.endTimeMillis - now else state.pausedRemainingMillis} " +
            "total=${state.totalMillis} $picture")
    }
    val water = if (pondColors != null && stepFrame != null) remember(
        look, stepFrame, pondColors, style.monochrome, boxWidth, boxHeight, effCorner
    ) {
        val render = if (look == WidgetLook.BUBBLES) BubblePop::render else EggHatch::render
        render(
            stepFrame, pondColors, style.monochrome,
            (boxWidth.value * density).toInt(), (boxHeight.value * density).toInt(),
            density, effCorner.value * density,
        )
    } else if (pondColors != null) remember(
        // Keyed on the pad, not the fraction: the picture is redrawn only when the duck hops.
        hopMood, hop, pads, pondColors, style.monochrome, boxWidth, boxHeight, effCorner
    ) {
        LilyHop.render(
            hopMood, progress, pondColors, style.monochrome,
            widthPx = (boxWidth.value * density).toInt(),
            heightPx = (boxHeight.value * density).toInt(),
            density = density,
            cornerPx = effCorner.value * density,
            maxPads = pads,
        )
    } else remember(
        fraction, seedArgb, style.key, night, settings.highContrast, boxWidth, boxHeight, duckFits
    ) {
        WaterRenderer.render(
            context,
            widthPx = (boxWidth.value * density).toInt(),
            heightPx = (boxHeight.value * density).toInt(),
            cornerPx = effCorner.value * density,
            fraction = fraction,
            vesselColor = scheme.surfaceVariant.toArgb(),
            waterColor = if (flat) scheme.onSurface.copy(alpha = 0.18f).toArgb()
            else lerp(scheme.surfaceVariant, scheme.primaryContainer, 0.58f).toArgb(),
            waveHighlight = scheme.primary.toArgb(),
            duckColor = scheme.onPrimaryContainer.toArgb(),
            flat = flat,
            // Small tanks have no space beside the digits for a decorative duck.
            showDuck = duckFits,
        )
    }
    val spokenState = when (mode) {
        DisplayMode.RUNNING -> context.getString(
            R.string.a11y_widget_running,
            // Approximate: read up to one display tick after this render.
            TimerMath.speakApproxLeft(context, state.endTimeMillis - now)
        )
        DisplayMode.PAUSED -> context.getString(
            R.string.a11y_widget_paused,
            TimerMath.speakClock(context, state.pausedRemainingMillis)
        )
        DisplayMode.RINGING -> context.getString(R.string.a11y_widget_ringing)
        DisplayMode.IDLE -> context.getString(R.string.a11y_widget_idle)
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .cornerRadius(effCorner)
            .semantics { contentDescription = spokenState }
            .clickable(actionStartActivity<MainActivity>())
    ) {
        Image(
            provider = ImageProvider(water),
            contentDescription = null,
            contentScale = ContentScale.FillBounds,
            modifier = GlanceModifier.fillMaxSize()
        )
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = GlanceModifier.padding(bottom = bandDp.dp),
        ) {
            TimeDisplay(mode, state, settings, style, sizePx = fittedPx, ink = ink)
            if (subtitleSp > 0) {
                Text(
                    text = subtitleText(mode, style),
                    style = TextStyle(
                        color = softInk?.let { ColorProvider(it) } ?: GlanceTheme.colors.onSurfaceVariant,
                        fontSize = subtitleSp.sp,
                        fontWeight = FontWeight.Medium,
                        fontFamily = style.glanceFont()
                    )
                )
            }
        }
    }
}


@Composable
internal fun subtitleText(mode: DisplayMode, style: AppStyle): String {
    val text = LocalContext.current.getString(mode.subtitleRes)
    return if (style.upperCaseLabels) text.uppercase() else text
}

/**
 * The clock, in every state, in ONE slot: running, a Chronometer ticks in the
 * launcher's process; paused / ringing / idle, a TextView with the same text
 * settings shows the stopped value (a stopped Chronometer would describe itself
 * to accessibility as a climbing duration). Both views live in the same layout
 * with the same font and size, so pause/resume and the ring can't shift the
 * digits (the old paused/idle Glance Text had different padding, and the "2:30"
 * vs "02:30" formatting flipped on pause). Format policy in the widget is
 * therefore the platform's zero-padded elapsed-time style. A stopped value rounds a
 * part second up ([TimerMath.wholeSecondsLeft]), as the app, the notification and the
 * spoken text do; only the ticking Chronometer floors, which is the platform's own rule.
 */
@Composable
internal fun TimeDisplay(
    mode: DisplayMode,
    state: TimerState,
    settings: TimerSettings,
    style: AppStyle,
    sizePx: Float,
    ink: androidx.compose.ui.graphics.Color? = null,
) {
    val context = LocalContext.current
    // RemoteViews wants a resolved color int, and the palette is
    // user-configured: resolve day/night here instead of via resources.
    val night = (context.resources.configuration.uiMode and
        Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    val scheme =
        if (night) Palette.dark(settings.seedColorArgb, style.monochrome, settings.highContrast)
        else Palette.light(settings.seedColorArgb, style.monochrome, settings.highContrast)
    val layout =
        if (style.glanceFont() != null) R.layout.widget_chrono_center_mono
        else R.layout.widget_chrono_center
    val color = ink ?: if (mode == DisplayMode.RUNNING) scheme.onSurface else scheme.onSurfaceVariant
    val rv = RemoteViews(context.packageName, layout).apply {
        setChronometerCountDown(R.id.widget_chrono, true)
        // Text floats over the water tank, so it reads on the vessel (top)
        // and on the water (bottom) alike.
        for (id in intArrayOf(R.id.widget_chrono, R.id.widget_clock_frozen)) {
            setTextViewTextSize(id, TypedValue.COMPLEX_UNIT_PX, sizePx)
            setTextColor(id, color.toArgb())
        }
        if (mode == DisplayMode.RUNNING) {
            setChronometer(
                R.id.widget_chrono,
                SystemClock.elapsedRealtime() +
                    (state.endTimeMillis - System.currentTimeMillis()),
                null,
                true
            )
            setViewVisibility(R.id.widget_chrono, View.VISIBLE)
            setViewVisibility(R.id.widget_clock_frozen, View.INVISIBLE)
        } else {
            // Stop the Chronometer (a reused view may still be ticking from the running state)
            // and hide it; the plain TextView shows the stopped time, so what accessibility
            // reports is the text on screen. The Chronometer keeps the same text, so the slot
            // never changes size. Actions apply in order.
            setChronometer(R.id.widget_chrono, SystemClock.elapsedRealtime(), null, false)
            val seconds =
                if (mode == DisplayMode.PAUSED) TimerMath.wholeSecondsLeft(state.pausedRemainingMillis) else 0L
            val frozen = DateUtils.formatElapsedTime(seconds)
            setTextViewText(R.id.widget_chrono, frozen)
            setTextViewText(R.id.widget_clock_frozen, frozen)
            setViewVisibility(R.id.widget_chrono, View.INVISIBLE)
            setViewVisibility(R.id.widget_clock_frozen, View.VISIBLE)
        }
    }
    AndroidRemoteViews(
        remoteViews = rv,
        modifier = GlanceModifier.fillMaxWidth()
    )
}

