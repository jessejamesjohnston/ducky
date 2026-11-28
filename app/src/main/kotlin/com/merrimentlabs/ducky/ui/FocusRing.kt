package com.merrimentlabs.ducky.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusEventModifierNode
import androidx.compose.ui.focus.FocusState
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.SemanticsModifierNode
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.node.invalidateSemantics
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.unit.dp
import com.merrimentlabs.ducky.ui.pond.PondMath

/** Whether a control's focus ring is showing. Every focusable control sets it; tests read it. */
val FocusRingShown = SemanticsPropertyKey<Boolean>("FocusRingShown")
internal var SemanticsPropertyReceiver.focusRingShown by FocusRingShown

/** The ring's width: 3dp, as in Merriment Labs' other apps. */
internal val FOCUS_RING_WIDTH = 3.dp

/**
 * The ring color for [scheme]: the accent where it stands out 3:1 (WCAG's non-text contrast)
 * from both the page and the card color a control can sit on, else the text color, which always
 * does. Users pick the accent, so a pale one must not leave keyboard focus invisible.
 */
internal fun focusRingColor(scheme: ColorScheme): Color {
    val accent = scheme.primary
    val standsOut = PondMath.contrast(accent, scheme.background) >= 3f &&
        PondMath.contrast(accent, scheme.surface) >= 3f
    return if (standsOut) accent else scheme.onSurface
}

/** [focusRingColor] for the current theme. */
@Composable
@ReadOnlyComposable
internal fun focusRingColor(): Color = focusRingColor(MaterialTheme.colorScheme)

/**
 * The ring color for a control filled with [fill] (the ring sits on the fill's outer edge): the
 * usual ring where it stands out 3:1 from the fill, else whichever of the text and page colors
 * stands out more (an accent-filled button, a swatch in the accent's color).
 */
internal fun focusRingColor(scheme: ColorScheme, fill: Color): Color {
    val ring = focusRingColor(scheme)
    if (PondMath.contrast(ring, fill) >= 3f) return ring
    return PondMath.bestInk(fill, scheme.onSurface, scheme.background)
}

/** [focusRingColor] for a control filled with [fill], in the current theme. */
@Composable
@ReadOnlyComposable
internal fun focusRingColor(fill: Color): Color = focusRingColor(MaterialTheme.colorScheme, fill)

/**
 * A 3dp ring around this control while it, or a control inside it, has keyboard focus. The ring
 * follows the control's [shape] and sits just inside its bounds, its outer edge on the control's
 * edge, so neither a clipping parent nor the edge of a scrolling list can cut it off. Put it
 * before the control's `clip` and `clickable` (or on the control's modifier). For a control with
 * its own fill, pass `focusRingColor(fill)` so the ring stands out from that fill.
 */
internal fun Modifier.focusRing(shape: Shape, color: Color): Modifier =
    this then FocusRingElement(shape, color)

/**
 * Where the ring's stroke runs for a control of [size], with a stroke [width] px wide: half a width
 * in from every edge, so the stroke's outer edge is the control's edge and nothing of it falls
 * outside the bounds. Null when the control is too small to hold a ring.
 */
internal fun focusRingOutline(size: Size, width: Float): Rect? {
    val inset = width / 2
    val rect = Rect(inset, inset, size.width - inset, size.height - inset)
    return rect.takeIf { it.width > 0f && it.height > 0f }
}

private data class FocusRingElement(val shape: Shape, val color: Color) :
    ModifierNodeElement<FocusRingNode>() {
    override fun create() = FocusRingNode(shape, color)

    override fun update(node: FocusRingNode) {
        node.shape = shape
        node.color = color
        node.invalidateDraw()
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "focusRing"
        properties["shape"] = shape
        properties["color"] = color
    }
}

private class FocusRingNode(var shape: Shape, var color: Color) :
    Modifier.Node(), FocusEventModifierNode, DrawModifierNode, SemanticsModifierNode {

    private var focused = false

    override fun onFocusEvent(focusState: FocusState) {
        if (focusState.hasFocus != focused) {
            focused = focusState.hasFocus
            invalidateDraw()
            invalidateSemantics()
        }
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        if (!focused) return
        val width = FOCUS_RING_WIDTH.toPx()
        val ring = focusRingOutline(size, width) ?: return
        translate(ring.left, ring.top) {
            drawOutline(shape.createOutline(ring.size, layoutDirection, this), color, style = Stroke(width))
        }
    }

    override fun SemanticsPropertyReceiver.applySemantics() {
        focusRingShown = focused
    }
}
