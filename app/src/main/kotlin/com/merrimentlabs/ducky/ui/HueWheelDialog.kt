package com.merrimentlabs.ducky.ui

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import android.annotation.SuppressLint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.merrimentlabs.ducky.R
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** Minimal hue wheel: drag around the ring, preview in the middle. */
// The wheel is sized against the screen on purpose: inside a Dialog, LocalWindowInfo describes the
// dialog's own window, whose size follows its content (the wheel), so it can't size the wheel.
@SuppressLint("ConfigurationScreenWidthHeight")
@Composable
internal fun HueWheelDialog(
    initialArgb: Int,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var hue by remember { mutableFloatStateOf(Palette.hueOf(initialArgb)) }
    val preview = Palette.hsl(hue, 0.85f, 0.40f)

    // A Material dialog: the title, the wheel and its slider, then Cancel and Use color as text
    // buttons at the end, confirm last.
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.custom_color_title)) },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                // A landscape phone is shorter than the wheel plus its buttons.
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                val ringColors = (0..12).map { Palette.hsl(it * 30f, 0.8f, 0.5f) }
                // The title, slider and buttons need about 290 dp with the dialog margins; the wheel gets what's left, so Set
                // stays on screen on a landscape phone (the column still scrolls as a fallback).
                val wheel = (LocalConfiguration.current.screenHeightDp - 290).coerceIn(120, 240).dp
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(wheel)
                        .pointerInput(Unit) {
                            detectTapGestures { pos -> hue = hueAt(pos, size.width) }
                        }
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDragStart = { pos -> hue = hueAt(pos, size.width) },
                                onDrag = { change, _ -> hue = hueAt(change.position, size.width) }
                            )
                        }
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val stroke = size.minDimension * 30f / 240f // 30 dp at the full 240 dp wheel
                        val radius = (size.minDimension - stroke) / 2f
                        drawCircle(
                            brush = Brush.sweepGradient(ringColors),
                            radius = radius,
                            style = Stroke(width = stroke)
                        )
                        // Knob on the current hue.
                        val rad = Math.toRadians(hue.toDouble())
                        val knob = Offset(
                            center.x + radius * cos(rad).toFloat(),
                            center.y + radius * sin(rad).toFloat()
                        )
                        drawCircle(Color.White, radius = stroke / 2f + 4.dp.toPx(), center = knob)
                        drawCircle(
                            Palette.hsl(hue, 0.8f, 0.5f),
                            radius = stroke / 2f,
                            center = knob
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(wheel * 110 / 240)
                            .clip(CircleShape)
                            .background(preview)
                    )
                }
                // The wheel is a pointer-only control. The slider is the same value
                // for TalkBack / switch access / keyboard: focusable, adjustable,
                // and it reads the hue in degrees. Both move the same state.
                val hueLabel = stringResource(R.string.a11y_hue)
                val hueState = pluralStringResource(R.plurals.a11y_hue_degrees, hue.roundToInt(), hue.roundToInt())
                Slider(
                    value = hue,
                    onValueChange = { hue = it },
                    valueRange = 0f..359f,
                    colors = SliderDefaults.colors(
                        thumbColor = preview,
                        activeTrackColor = preview,
                        inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                        .focusRing(RoundedCornerShape(8.dp), focusRingColor())
                        .semantics {
                            contentDescription = hueLabel
                            stateDescription = hueState
                        }
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onPick(preview.toArgb()) },
                modifier = Modifier.focusRing(CircleShape, focusRingColor()),
            ) { Text(stringResource(R.string.set)) }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.focusRing(CircleShape, focusRingColor()),
            ) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

private fun hueAt(pos: Offset, sidePx: Int): Float {
    val c = sidePx / 2f
    val deg = Math.toDegrees(atan2((pos.y - c).toDouble(), (pos.x - c).toDouble())).toFloat()
    return (deg + 360f) % 360f
}
