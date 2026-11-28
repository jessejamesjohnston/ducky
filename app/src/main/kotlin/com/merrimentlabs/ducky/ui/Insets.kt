package com.merrimentlabs.ducky.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed

/**
 * Keeps a screen's top clear of what the system draws over an edge-to-edge window: the status
 * bar on a phone, the caption bar (title and window buttons) of a desktop window on a laptop, and
 * a display cutout. Every screen's top uses this one modifier, so a caption that takes the page
 * color still never covers the app's own top bar.
 */
fun Modifier.windowTopInset(): Modifier = composed {
    windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout).only(WindowInsetsSides.Top))
}
