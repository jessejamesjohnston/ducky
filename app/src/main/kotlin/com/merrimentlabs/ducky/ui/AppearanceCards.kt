package com.merrimentlabs.ducky.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.merrimentlabs.ducky.R
import com.merrimentlabs.ducky.TimerController
import com.merrimentlabs.ducky.TimerStore

/** The style tiles, two to a row; each tile previews the style it applies. */
@Composable
internal fun StyleCard(selectedKey: String) {
    val context = LocalContext.current
    val haptic = rememberHaptic()
    // Theme changes must reach live widgets too. Writes run on the controller's app-lived scope
    // (a tap just before leaving still lands); widgets re-render through its delivery path.
    fun applyStyle(style: AppStyle) =
        TimerController.requestSettingsWrite(context, refreshWidgets = true) {
            TimerStore.setStyle(it, style.key)
        }
    Column {
        SectionLabel(stringResource(R.string.section_style))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            AppStyle.entries.chunked(2).forEach { rowStyles ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    rowStyles.forEach { style ->
                        StylePreviewTile(
                            style = style,
                            selected = style.key == selectedKey,
                            modifier = Modifier.weight(1f),
                            onClick = { haptic(); applyStyle(style) }
                        )
                    }
                }
            }
        }
    }
}

/** The twelve preset colors and the custom-color row that opens the hue wheel. */
@Composable
internal fun ColorCard(seedArgb: Int) {
    val context = LocalContext.current
    val haptic = rememberHaptic()
    val presetNames = stringArrayResource(R.array.preset_color_names)

    fun applySeed(argb: Int) = TimerController.requestSettingsWrite(context, refreshWidgets = true) {
        TimerStore.setSeedColor(it, argb)
        // The launcher icon (and the circle Android 17 paints on notifications)
        // follows when the user leaves the app: MainActivity.syncLauncherIcon.
        // Swapping here closed the app, since its launching alias went away.
    }

    var showWheel by remember { mutableStateOf(false) }
    if (showWheel) {
        HueWheelDialog(
            initialArgb = seedArgb,
            onPick = { argb -> applySeed(argb); showWheel = false },
            onDismiss = { showWheel = false }
        )
    }

    Column(modifier = Modifier.padding(bottom = 24.dp)) {
        SectionLabel(stringResource(R.string.section_color))
        SettingsCard(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = 16.dp,
        ) {
            val isCustom = seedArgb !in PRESET_SEEDS
            // Six to a row, or 4 / 3 / 2 when the window (or a large display size)
            // leaves less than six swatches' width: always even rows, never a clipped one.
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val perRow = listOf(6, 4, 3, 2).firstOrNull { n ->
                    maxWidth >= 46.dp * n + 6.dp * (n - 1)
                } ?: 2
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    PRESET_SEEDS.withIndex().chunked(perRow).forEach { rowSeeds ->
                        Row(
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            rowSeeds.forEach { (index, seed) ->
                                ColorSwatch(
                                    color = Color(seed),
                                    name = presetNames[index],
                                    selected = !isCustom && seed == seedArgb,
                                    onClick = { haptic(); applySeed(seed) }
                                )
                            }
                        }
                    }
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(CircleShape)
                    .clickable { haptic(); showWheel = true }
                    .padding(vertical = 6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.sweepGradient(
                                (0..12).map { Palette.hsl(it * 30f, 0.8f, 0.5f) }
                            )
                        )
                        .then(
                            if (isCustom) Modifier.border(
                                3.dp, MaterialTheme.colorScheme.onSurface, CircleShape
                            ) else Modifier
                        )
                )
                Text(
                    text = stringResource(
                        if (isCustom) R.string.custom_color_change
                        else R.string.custom_color
                    ),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(start = 14.dp)
                )
            }
        }
    }
}

/**
 * A settings tile drawn IN the style it switches to: filled rounded square
 * (Mountain View), filled capsule (Seoul), outlined monospace (London), or a gradient-filled
 * tile (Beijing).
 */
@Composable
private fun StylePreviewTile(
    style: AppStyle,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val shape = style.tileShape
    val gradient =
        if (style.gradientAccents)
            Brush.verticalGradient(
                listOf(
                    lerp(MaterialTheme.colorScheme.primary, Color.White, 0.22f),
                    lerp(MaterialTheme.colorScheme.primary, Color.Black, 0.08f),
                )
            )
        else null
    val bg = when {
        style.outlined -> MaterialTheme.colorScheme.surface
        style.vividButtons -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.primaryContainer
    }
    val fg = when {
        style.outlined -> MaterialTheme.colorScheme.onSurface
        style.vividButtons -> MaterialTheme.colorScheme.onPrimary
        else -> MaterialTheme.colorScheme.onPrimaryContainer
    }
    val border = when {
        selected -> MaterialTheme.colorScheme.primary
        style.outlined -> MaterialTheme.colorScheme.outline
        else -> Color.Transparent
    }
    val name = stringResource(style.nameRes)
    val styleLabel = stringResource(R.string.a11y_style_option, name)
    val description =
        if (selected) stringResource(R.string.a11y_option_selected, styleLabel) else styleLabel
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .height(76.dp)
            .clip(shape)
            .then(
                if (gradient != null) Modifier.background(gradient)
                else Modifier.background(bg)
            )
            .border(if (selected) 2.5.dp else 1.5.dp, border, shape)
            .semantics { contentDescription = description }
            .clickable(role = Role.Button, onClick = onClick)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "1:00",
                fontSize = 17.sp,
                fontWeight = if (style.clockWeight == FontWeight.Normal) FontWeight.SemiBold
                else style.clockWeight,
                fontFamily = style.clockFont,
                color = fg
            )
            Text(
                text = if (style.upperCaseLabels) name.uppercase() else name,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                fontFamily = style.clockFont,
                color = fg,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun ColorSwatch(color: Color, name: String, selected: Boolean, onClick: () -> Unit) {
    val description =
        if (selected) stringResource(R.string.a11y_option_selected, name) else name
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(46.dp)
            .clip(CircleShape)
            .background(color)
            .then(
                if (selected) Modifier.border(
                    3.dp, MaterialTheme.colorScheme.onSurface, CircleShape
                ) else Modifier
            )
            .semantics { contentDescription = description }
            .clickable(role = Role.Button, onClick = onClick)
    ) {
        if (selected) {
            Text(text = "✓", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
    }
}
