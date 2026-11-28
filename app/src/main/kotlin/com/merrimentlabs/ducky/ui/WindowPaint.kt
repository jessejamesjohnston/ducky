package com.merrimentlabs.ducky.ui

import androidx.core.graphics.drawable.toDrawable
import android.app.Activity
import android.app.ActivityManager
import android.os.Build
import android.view.WindowInsetsController
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb

/**
 * Paints a window to match the screen it shows, so a laptop's caption bar (and the status and
 * navigation bars) sit on [color] with legible icons: the window background is [color], the bars
 * use dark icons on a light [color] (luminance above 0.5) and light ones otherwise, the caption
 * bar is transparent over it (Android 15+), and the task carries [label] and [color] for the
 * caption and Recents. Call it again whenever [color] changes (light and dark, high contrast,
 * another accent).
 */
fun Activity.paintWindow(color: Color, label: String) {
    window.setBackgroundDrawable(color.toArgb().toDrawable())
    val light = color.luminance() > 0.5f
    var lightBars = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
        WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
    var always = 0
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
        // The caption bar flags are Android 15's.
        lightBars = lightBars or WindowInsetsController.APPEARANCE_LIGHT_CAPTION_BARS
        always = WindowInsetsController.APPEARANCE_TRANSPARENT_CAPTION_BAR_BACKGROUND
    }
    val mask = lightBars or always
    val flags = (if (light) lightBars else 0) or always
    window.insetsController?.setSystemBarsAppearance(flags, mask)
    setTaskDescription(ActivityManager.TaskDescription.Builder().setLabel(label).setPrimaryColor(color.toArgb()).build())
}
