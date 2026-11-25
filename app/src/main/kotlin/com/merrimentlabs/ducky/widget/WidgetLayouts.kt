package com.merrimentlabs.ducky.widget

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import com.merrimentlabs.ducky.R
import com.merrimentlabs.ducky.TimerSettings
import com.merrimentlabs.ducky.TimerState
import com.merrimentlabs.ducky.ui.AppStyle

/**
 * Single row sized to the actual host. Reserve at least 48 dp per preset,
 * then give the tank 42% of the usable width. Clock and buttons share a height.
 */
@Composable
internal fun BarWidget(
    state: TimerState,
    settings: TimerSettings,
    style: AppStyle,
) {
    val increments = settings.incrementsSeconds
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = GlanceModifier
            .fillMaxSize()
            .background(GlanceTheme.colors.surface)
            .cornerRadius(style.widgetCorner)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        val size = LocalSize.current
        val controlHeight = (size.height.value - 12f).coerceIn(48f, 72f).toInt()
        val clockWidth = maxOf(BAR_CLOCK_DP, (size.width.value - 28f) * 0.42f)
            .coerceAtMost(size.width.value - 184f)
        TimeContainer(
            state, settings, style,
            // Monospace digits are ~0.6 em wide: 22 sp fills the 66 dp slot
            // edge to edge (seen on the emulator), 20 sp leaves a margin.
            timeSp = if (clockWidth >= 120f) 28f else if (style.glanceFont() != null) 20f else 22f,
            subtitleSp = if (clockWidth >= 90f) 10 else 0, corner = (controlHeight / 2).dp,
            boxWidth = clockWidth.dp,
            boxHeight = controlHeight.dp,
            modifier = GlanceModifier.width(clockWidth.dp).height(controlHeight.dp)
        )
        Spacer(modifier = GlanceModifier.width(8.dp))
        ButtonRow(
            state, increments, style, widthDp = size.width.value - 28f - clockWidth, height = controlHeight, fontSp = 12, gap = 6.dp,
            modifier = GlanceModifier.defaultWeight()
        )
    }
}

/**
 * Tall shapes: big time box over a button row. TALL (200x140) holds all
 * three presets at 56x48; SQUARE (130x130, the 2x2) holds two at 52x48:
 * three 48 dp targets need 156 dp of its 110. Time box height is what's
 * left above a 48 dp row (Square: 54 dp, enough for the 30 sp clock plus
 * its subtitle).
 */
@Composable
internal fun StackedWidget(
    state: TimerState,
    settings: TimerSettings,
    style: AppStyle,
    maxIncrements: Int,
    fontSp: Int,
) {
    val increments = settings.incrementsSeconds.take(maxIncrements)
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(GlanceTheme.colors.surface)
            .cornerRadius(style.widgetCorner)
            .padding(10.dp)
    ) {
        val size = LocalSize.current
        TimeContainer(
            state, settings, style,
            timeSp = 30f, subtitleSp = 10, corner = 24.dp,
            boxWidth = (size.width.value - 20f).dp,
            boxHeight = (size.height.value - 28f - BUTTON_DP).dp,
            modifier = GlanceModifier.fillMaxWidth().defaultWeight()
        )
        Spacer(modifier = GlanceModifier.height(8.dp))
        ButtonRow(
            state, increments, style, widthDp = size.width.value - 20f, height = BUTTON_DP, fontSp = fontSp, gap = 6.dp,
            modifier = GlanceModifier.fillMaxWidth()
        )
    }
}

/**
 * Narrow AND short (the 130x60 bucket). Inner width is 114 dp.
 * Normal: the clock (60 dp at 18 sp: fits "00:30" in both fonts) beside
 * ONE 48 dp control, the first configured preset: three buttons would be
 * under 48 dp here, so three presets need Bar or larger.
 * Ringing: the two actions fill the row, "+1 min" and "Stop" at 55x48,
 * and there is no tank: two buttons plus a clock would leave the clock a
 * negative width budget, so it would collapse and the buttons overflow. The
 * ringing state is spoken by the row's description.
 */
@Composable
internal fun CompactWidget(
    state: TimerState,
    settings: TimerSettings,
    style: AppStyle,
) {
    val context = LocalContext.current
    val increments = settings.incrementsSeconds
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = GlanceModifier
            .fillMaxSize()
            .background(GlanceTheme.colors.surface)
            .cornerRadius(style.widgetCorner)
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        if (state.isRinging) {
            ButtonRow(
                state, emptyList(), style, widthDp = LocalSize.current.width.value - 16f, height = BUTTON_DP, fontSp = 12, gap = 4.dp,
                modifier = GlanceModifier
                    .fillMaxWidth()
                    .semantics { contentDescription = context.getString(R.string.a11y_widget_ringing_row) }
            )
            return@Row
        }
        val size = LocalSize.current
        TimeContainer(
            state, settings, style,
            timeSp = 18f, subtitleSp = 0, corner = 24.dp,
            boxWidth = (size.width.value - 16f - 6f - COMPACT_BUTTON_DP).dp,
            boxHeight = (size.height.value - 12f).dp,
            modifier = GlanceModifier.defaultWeight().fillMaxHeight()
        )
        Spacer(modifier = GlanceModifier.width(6.dp))
        ButtonRow(
            state, increments.take(1), style, widthDp = COMPACT_BUTTON_DP, height = BUTTON_DP, fontSp = 12, gap = 4.dp,
            modifier = GlanceModifier.width(COMPACT_BUTTON_DP.dp)
        )
    }
}

