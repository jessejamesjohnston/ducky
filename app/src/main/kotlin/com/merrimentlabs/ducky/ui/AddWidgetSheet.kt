package com.merrimentlabs.ducky.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.unit.dp
import com.merrimentlabs.ducky.R
import com.merrimentlabs.ducky.widget.BubblesWidgetReceiver
import com.merrimentlabs.ducky.widget.EggTimerWidgetReceiver
import com.merrimentlabs.ducky.widget.PondWidgetReceiver
import com.merrimentlabs.ducky.widget.TimerWidgetReceiver

/** The four widget looks, in the order the sheet shows them. */
private class WidgetChoice(val receiver: Class<*>, val label: Int, val description: Int, val preview: Int)

private val WIDGET_CHOICES = listOf(
    WidgetChoice(TimerWidgetReceiver::class.java, R.string.tank_widget_label, R.string.add_tank_widget_subtitle, R.drawable.widget_preview),
    WidgetChoice(PondWidgetReceiver::class.java, R.string.pond_widget_label, R.string.add_pond_widget_subtitle, R.drawable.pond_widget_preview),
    WidgetChoice(BubblesWidgetReceiver::class.java, R.string.bubbles_widget_label, R.string.add_bubbles_widget_subtitle, R.drawable.bubbles_widget_preview),
    WidgetChoice(EggTimerWidgetReceiver::class.java, R.string.eggs_widget_label, R.string.add_eggs_widget_subtitle, R.drawable.eggs_widget_preview),
)

/**
 * "Add widget": the four looks with their picker previews. A tap hands the pick to the
 * launcher's own pin dialog. Android offers apps no way to open the home screen's widget
 * picker on their own widgets, so this sheet stands in for it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AddWidgetSheet(onPick: (Class<*>) -> Unit, onDismiss: () -> Unit) {
    val style = LocalAppStyle.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
        ) {
            Text(
                text = stringResource(R.string.add_widget_sheet_title).let { if (style.upperCaseLabels) it.uppercase() else it },
                style = MaterialTheme.typography.titleLarge,
                fontFamily = style.labelFont,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.add_widget_sheet_body),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 16.dp)
            )
            // Two per row on a phone; the previews are 200 x 140 gallery crops.
            WIDGET_CHOICES.chunked(2).forEach { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(bottom = 12.dp)) {
                    pair.forEach { choice ->
                        WidgetChoiceTile(choice, onClick = { onPick(choice.receiver) }, modifier = Modifier.weight(1f))
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun WidgetChoiceTile(choice: WidgetChoice, onClick: () -> Unit, modifier: Modifier) {
    val style = LocalAppStyle.current
    val shape = RoundedCornerShape(minOf(style.cardCorner, 20.dp))
    val name = stringResource(choice.label)
    val spoken = stringResource(R.string.a11y_add_widget_choice, name, stringResource(choice.description))
    Column(
        modifier = modifier
            .focusRing(shape, focusRingColor(MaterialTheme.colorScheme.background))
            .clip(shape)
            .background(MaterialTheme.colorScheme.background)
            .then(if (style.outlined) Modifier.border(1.dp, MaterialTheme.colorScheme.outline, shape) else Modifier)
            .clickable(role = Role.Button, onClick = onClick)
            .clearAndSetSemantics { contentDescription = spoken; role = Role.Button }
            .padding(10.dp)
    ) {
        Image(
            painter = painterResource(choice.preview),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxWidth().aspectRatio(200f / 140f)
        )
        Text(
            text = name,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 8.dp)
        )
        Text(
            text = stringResource(choice.description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
