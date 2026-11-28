package com.merrimentlabs.ducky

import com.merrimentlabs.ducky.ui.Figtree
import com.merrimentlabs.ducky.ui.focusRingColor
import com.merrimentlabs.ducky.ui.focusRing
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.merrimentlabs.ducky.ui.LocalAppStyle
import kotlin.math.roundToInt

/**
 * The button block under the pond. Idle/running/paused: quick-add row over
 * Cancel. Ringing: the same two outcomes every other surface offers (equal
 * "+1 min" and Stop tiles), so the foreground app is never the one
 * place the alarm can't be silenced without opening the shade.
 */
@Composable
internal fun Controls(
    increments: List<Int>,
    running: Boolean,
    paused: Boolean,
    state: TimerState,
    context: android.content.Context,
    onIncrement: (Int) -> Unit,
    onCancel: () -> Unit,
    onStop: () -> Unit,
    onExtend: () -> Unit,
    /** 0 = the phone size, 1 = the largest (see [controlsGrowth]). The alarm actions keep their
     *  size: they match the alarm screen's. */
    grow: Float = 0f,
) {
    if (state.isRinging) {
        AlarmActions(
            enabled = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            onExtend = onExtend,
            onStop = onStop,
        )
        return
    }
    fun grown(from: Int, to: Int) = (from + (to - from) * grow).roundToInt()
    Row(
        horizontalArrangement = Arrangement.spacedBy(grown(10, 16).dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
    ) {
        increments.forEach { seconds ->
            ChunkyButton(
                label = TimerMath.formatAddIncrement(context, seconds),
                spokenLabel = stringResource(
                    R.string.a11y_add_time, TimerMath.speakDuration(context, seconds.toLong())
                ),
                emphasized = true,
                enabled = true,
                heightDp = grown(96, 136),
                fontSize = grown(22, 30),
                modifier = Modifier.weight(1f),
                onClick = { onIncrement(seconds) }
            )
        }
    }
    ChunkyButton(
        label = stringResource(R.string.action_cancel),
        spokenLabel = stringResource(R.string.a11y_cancel_timer),
        emphasized = false,
        enabled = running || paused,
        heightDp = grown(64, 88),
        fontSize = grown(17, 22),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = grown(12, 16).dp),
        onClick = onCancel
    )
}

/**
 * How much the two-pane controls grow with the pane's height: 0 up to [GROW_FROM] (every phone in
 * landscape), 1 from [GROW_TO] (a desktop window, a big tablet), linear between.
 */
internal fun controlsGrowth(paneHeight: Dp): Float =
    ((paneHeight - GROW_FROM) / (GROW_TO - GROW_FROM)).coerceIn(0f, 1f)

internal val GROW_FROM = 480.dp
internal val GROW_TO = 900.dp

/**
 * The stacked layout's growth with the window's width: 0 below [STACKED_GROW_FROM] (every phone
 * held upright), 1 from [STACKED_GROW_TO] (a tablet in portrait, an unfolded foldable).
 */
internal fun stackedGrowth(width: Dp): Float =
    ((width - STACKED_GROW_FROM) / (STACKED_GROW_TO - STACKED_GROW_FROM)).coerceIn(0f, 1f)

internal val STACKED_GROW_FROM = 600.dp
internal val STACKED_GROW_TO = 760.dp
/** The stacked controls' widest, fully grown (520 dp on phones). */
internal val STACKED_MAX_WIDTH = 720.dp

/** Shared alarm actions: identical geometry and order on both app surfaces. */
@Composable
internal fun AlarmActions(
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onExtend: () -> Unit,
    onStop: () -> Unit,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ChunkyButton(
            label = stringResource(R.string.action_extend),
            spokenLabel = stringResource(R.string.a11y_extend_alarm),
            emphasized = false,
            enabled = enabled,
            heightDp = 72,
            fontSize = 20,
            modifier = Modifier.fillMaxWidth(),
            onClick = onExtend,
        )
        ChunkyButton(
            label = stringResource(R.string.action_stop),
            spokenLabel = stringResource(R.string.a11y_stop_alarm),
            emphasized = true,
            enabled = enabled,
            heightDp = 72,
            fontSize = 20,
            modifier = Modifier.fillMaxWidth(),
            onClick = onStop,
        )
    }
}

/** [text] in capitals when the style shouts its labels. */
internal fun stringResourceStyled(text: String, caps: Boolean) =
    if (caps) text.uppercase() else text

/**
 * The one button of this app: a big tile that follows the active style: a filled tonal
 * rounded square (Mountain View), a capsule (Seoul), a thin outline (London) or a saturated,
 * gradient-filled tile (Beijing).
 */
@Composable
fun ChunkyButton(
    label: String,
    emphasized: Boolean,
    enabled: Boolean,
    heightDp: Int,
    fontSize: Int,
    modifier: Modifier = Modifier,
    spokenLabel: String? = null,
    onClick: () -> Unit,
) {
    val style = LocalAppStyle.current
    val shape = style.tileShape
    // Beijing: accent surfaces get a soft top-to-bottom gradient.
    val gradient =
        if (style.gradientAccents && emphasized && enabled && !style.outlined)
            Brush.verticalGradient(
                listOf(
                    lerp(MaterialTheme.colorScheme.primary, Color.White, 0.22f),
                    lerp(MaterialTheme.colorScheme.primary, Color.Black, 0.08f),
                )
            )
        else null
    val bg = when {
        style.outlined -> Color.Transparent
        !enabled -> MaterialTheme.colorScheme.surface
        emphasized && style.vividButtons -> MaterialTheme.colorScheme.primary
        emphasized -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceContainerHighest
    }
    val fg = when {
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        style.outlined -> if (emphasized) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurface
        emphasized && style.vividButtons -> MaterialTheme.colorScheme.onPrimary
        emphasized -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurface
    }
    val borderColor = when {
        !style.outlined -> null
        !enabled -> MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
        emphasized -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.outline
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .height(heightDp.dp)
            .focusRing(shape, if (bg.alpha == 0f) focusRingColor() else focusRingColor(bg))
            .clip(shape)
            .let { m -> if (gradient != null) m.background(gradient) else m.background(bg) }
            .let { m ->
                if (borderColor != null) m.border(BorderStroke(1.5.dp, borderColor), shape) else m
            }
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
    ) {
        // One line that shrinks to fit: wrapped at a large font scale, a long preset ("+1m 30s")
        // would break at the space and show only "+1m" on a button that adds 90 seconds.
        BasicText(
            text = if (style.upperCaseLabels) label.uppercase() else label,
            style = TextStyle(
                fontWeight = FontWeight.SemiBold,
                fontFamily = style.labelFont ?: Figtree,
                textAlign = TextAlign.Center,
                color = fg,
            ),
            maxLines = 1,
            softWrap = false,
            autoSize = TextAutoSize.StepBased(minFontSize = 10.sp, maxFontSize = fontSize.sp),
            modifier = Modifier.padding(horizontal = 6.dp).then(
                if (spokenLabel != null) Modifier.semantics { contentDescription = spokenLabel }
                else Modifier
            )
        )
    }
}

