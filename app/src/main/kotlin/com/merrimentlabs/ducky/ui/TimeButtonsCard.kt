package com.merrimentlabs.ducky.ui

import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.merrimentlabs.ducky.R
import com.merrimentlabs.ducky.TimerController
import com.merrimentlabs.ducky.TimerMath
import com.merrimentlabs.ducky.TimerStore

/** The three add-time buttons' amounts, each stepped through TimerStore.INCREMENT_CHOICES. */
@Composable
internal fun TimeButtonsCard(incrementsSeconds: List<Int>) {
    val context = LocalContext.current
    val haptic = rememberHaptic()
    Column {
        SectionLabel(stringResource(R.string.default_increments))
        SettingsCard {
            incrementsSeconds.forEachIndexed { index, seconds ->
                if (index > 0) SettingsDivider()
                IncrementRow(
                    label = stringResource(R.string.button_n, index + 1),
                    seconds = seconds,
                    onStep = { up ->
                        haptic()
                        TimerController.requestSettingsWrite(context, refreshWidgets = true) {
                            TimerStore.stepIncrement(it, index, up)
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun IncrementRow(label: String, seconds: Int, onStep: (up: Boolean) -> Unit) {
    val context = LocalContext.current
    val index = TimerStore.incrementSlot(seconds)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            // The steppers are 44dp: 6dp above and below makes the house 56dp row.
            .heightIn(min = SettingsMetrics.ROW_MIN_HEIGHT)
            .padding(horizontal = SettingsMetrics.ROW_SIDE, vertical = 6.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        StepperButton(
            text = "–",
            description = stringResource(R.string.a11y_decrease, label),
            emphasized = false,
            enabled = index > 0,
            onClick = { onStep(false) }
        )
        Text(
            text = TimerMath.formatIncrement(LocalContext.current, seconds),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            // One width for every value ("5s" to "1m 30s"), so the minus buttons stand in one
            // column whatever the values beside them.
            modifier = Modifier
                .widthIn(min = 72.dp)
                .padding(horizontal = 8.dp)
                .semantics {
                    contentDescription = TimerMath.speakDuration(context, seconds.toLong())
                }
        )
        StepperButton(
            text = "+",
            description = stringResource(R.string.a11y_increase, label),
            emphasized = true,
            enabled = index < TimerStore.INCREMENT_CHOICES.lastIndex,
            onClick = { onStep(true) }
        )
    }
}

@Composable
private fun StepperButton(
    text: String,
    description: String,
    emphasized: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val style = LocalAppStyle.current
    val bg = when {
        !enabled -> MaterialTheme.colorScheme.surfaceVariant
        emphasized -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.surfaceContainerHighest
    }
    val fg = when {
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant
        emphasized -> MaterialTheme.colorScheme.onPrimary
        else -> MaterialTheme.colorScheme.onSurface
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(44.dp) // a11y minimum-adjacent touch target
            .focusRing(style.controlShape, focusRingColor(bg))
            .clip(style.controlShape)
            .background(bg)
            .semantics { contentDescription = description }
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
    ) {
        Text(text = text, style = MaterialTheme.typography.titleLarge, color = fg)
    }
}
