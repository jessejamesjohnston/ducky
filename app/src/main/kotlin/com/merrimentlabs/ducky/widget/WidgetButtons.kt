// Glance has no Compose UI LocalResources: a widget renders in the launcher's process, so it
// reads the host context's resources on every render.
@file:android.annotation.SuppressLint("LocalContextResourcesRead")

package com.merrimentlabs.ducky.widget

import android.graphics.Paint
import android.graphics.Typeface
import android.util.TypedValue
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalContext
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.merrimentlabs.ducky.R
import com.merrimentlabs.ducky.TimerMath
import com.merrimentlabs.ducky.TimerState
import com.merrimentlabs.ducky.ui.AppStyle

/**
 * The control row: quick-add increments normally; while ringing, the same
 * two outcomes every other surface offers ("+1 min", "Stop"), bound to the
 * ring session the row was drawn for.
 */
@Composable
internal fun ButtonRow(
    state: TimerState,
    increments: List<Int>,
    style: AppStyle,
    widthDp: Float,
    height: Int,
    fontSp: Int,
    gap: Dp,
    modifier: GlanceModifier,
) {
    val context = LocalContext.current
    val count = if (state.isRinging) 2 else increments.size.coerceAtLeast(1)
    val buttonWidth = (widthDp - gap.value * (count - 1)) / count
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        if (state.isRinging) {
            val session = state.ringingSinceMillis
            Box(modifier = GlanceModifier.defaultWeight()) {
                WidgetButton(
                    label = context.getString(R.string.action_extend),
                    spokenLabel = context.getString(R.string.a11y_extend_alarm),
                    style = style, height = height, fill = true, fontSp = fontSp, widthDp = buttonWidth,
                    action = actionRunCallback<RingAction>(
                        actionParametersOf(
                            RingAction.KIND to RingAction.EXTEND, RingAction.SESSION to session
                        )
                    )
                )
            }
            Spacer(modifier = GlanceModifier.width(gap))
            Box(modifier = GlanceModifier.defaultWeight()) {
                WidgetButton(
                    label = context.getString(R.string.action_stop),
                    emphasized = true,
                    spokenLabel = context.getString(R.string.a11y_stop_alarm),
                    style = style, height = height, fill = true, fontSp = fontSp, widthDp = buttonWidth,
                    action = actionRunCallback<RingAction>(
                        actionParametersOf(
                            RingAction.KIND to RingAction.STOP, RingAction.SESSION to session
                        )
                    )
                )
            }
        } else {
            increments.forEachIndexed { i, seconds ->
                if (i > 0) Spacer(modifier = GlanceModifier.width(gap))
                Box(modifier = GlanceModifier.defaultWeight()) {
                    AddButton(seconds, style, height = height, fill = true, fontSp = fontSp, widthDp = buttonWidth)
                }
            }
        }
    }
}

/** Quick-add button: "+30s" etc., bound to [AddTimeAction]. */
@Composable
internal fun AddButton(
    seconds: Int,
    style: AppStyle,
    widthDp: Float,
    height: Int,
    fill: Boolean = false,
    fontSp: Int = 13,
) {
    val context = LocalContext.current
    WidgetButton(
        label = TimerMath.formatAddIncrement(context, seconds),
        spokenLabel = context.getString(
            R.string.a11y_add_time, TimerMath.speakDuration(context, seconds.toLong())
        ),
        style = style, height = height, fill = fill, fontSp = fontSp, widthDp = widthDp,
        action = actionRunCallback<AddTimeAction>(
            actionParametersOf(AddTimeAction.SECONDS to seconds)
        )
    )
}

/**
 * Uniform widget button. Beijing fills with saturated primary; London draws
 * a thin outline via nested boxes: Glance has no border modifier.
 */
@Composable
internal fun WidgetButton(
    label: String,
    spokenLabel: String,
    style: AppStyle,
    widthDp: Float,
    height: Int,
    fill: Boolean,
    fontSp: Int,
    action: androidx.glance.action.Action,
    emphasized: Boolean = false,
) {
    val corner = minOf(style.tileCorner, (height / 2).dp)
    val styledLabel = if (style.upperCaseLabels) label.uppercase() else label
    val metrics = LocalContext.current.resources.displayMetrics
    val paint = Paint().apply {
        typeface = Typeface.create(if (style.monochrome) "monospace" else "sans-serif", Typeface.BOLD)
    }
    // Android 14+ font scaling is nonlinear: measure each candidate in actual px.
    var low = 1f
    var high = fontSp.toFloat()
    repeat(12) {
        val mid = (low + high) / 2f
        paint.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, mid, metrics)
        if (paint.measureText(styledLabel) <= (widthDp - 8f) * metrics.density) low = mid else high = mid
    }
    val labelSp = low
    val sizeModifier =
        (if (fill) GlanceModifier.fillMaxWidth() else GlanceModifier.width(62.dp))
            .height(height.dp)

    if (style.outlined) {
        Box(
            modifier = sizeModifier
                .semantics { contentDescription = spokenLabel }
                .background(if (emphasized) GlanceTheme.colors.primary else GlanceTheme.colors.outline)
                .cornerRadius(corner)
                .padding(1.dp)
                .clickable(action)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = GlanceModifier
                    .fillMaxSize()
                    .background(GlanceTheme.colors.surface)
                    .cornerRadius(corner - 1.dp)
            ) {
                ButtonLabel(styledLabel, if (emphasized) GlanceTheme.colors.primary else GlanceTheme.colors.onSurface, labelSp, style)
            }
        }
    } else {
        val bg = if (style.vividButtons || emphasized) GlanceTheme.colors.primary
        else GlanceTheme.colors.primaryContainer
        val fg = if (style.vividButtons || emphasized) GlanceTheme.colors.onPrimary
        else GlanceTheme.colors.onPrimaryContainer
        Box(
            contentAlignment = Alignment.Center,
            modifier = sizeModifier
                .semantics { contentDescription = spokenLabel }
                .background(bg)
                .cornerRadius(corner)
                .clickable(action)
        ) {
            ButtonLabel(styledLabel, fg, labelSp, style)
        }
    }
}

@Composable
internal fun ButtonLabel(label: String, color: ColorProvider, fontSp: Float, style: AppStyle) {
    Text(
        text = label,
        style = TextStyle(
            color = color,
            fontSize = fontSp.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = style.glanceFont()
        ),
        maxLines = 1
    )
}

