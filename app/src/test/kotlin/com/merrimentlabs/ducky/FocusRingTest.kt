package com.merrimentlabs.ducky

import org.junit.Assert.assertNull
import com.merrimentlabs.ducky.ui.focusRingOutline
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toArgb
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import com.merrimentlabs.ducky.ui.FocusRingShown
import com.merrimentlabs.ducky.ui.PRESET_SEEDS
import com.merrimentlabs.ducky.ui.Palette
import com.merrimentlabs.ducky.ui.TimerTheme
import com.merrimentlabs.ducky.ui.focusRingColor
import com.merrimentlabs.ducky.ui.pond.PondMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The keyboard focus ring: visible against what it sits on, and shown on the focused control. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FocusRingTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun theRingStandsOutThreeToOneOnThePageAndTheCardForEveryColorAndTheme() {
        // The presets, plus custom picks a person can make with the hue wheel: pale and dark.
        val seeds = PRESET_SEEDS + listOf(0xFFFFF59D.toInt(), 0xFFB3E5FC.toInt(), 0xFF212121.toInt(), 0xFFFFFFFF.toInt())
        for (seed in seeds) for (mono in listOf(false, true)) for (hc in listOf(false, true)) {
            for ((theme, scheme) in listOf("light" to Palette.light(seed, mono, hc), "dark" to Palette.dark(seed, mono, hc))) {
                val ring = focusRingColor(scheme)
                val tag = "seed %08X mono=$mono hc=$hc $theme".format(seed)
                assertTrue("$tag on the page", PondMath.contrast(ring, scheme.background) >= 3f)
                assertTrue("$tag on a card", PondMath.contrast(ring, scheme.surface) >= 3f)
            }
        }
    }

    @Test fun onAFilledControlTheRingStandsOutFromTheFill() {
        val seeds = PRESET_SEEDS + listOf(0xFFFFF59D.toInt(), 0xFF212121.toInt())
        for (seed in seeds) for (hc in listOf(false, true)) {
            for ((theme, scheme) in listOf("light" to Palette.light(seed, false, hc), "dark" to Palette.dark(seed, false, hc))) {
                val fills = listOf(scheme.primary, scheme.primaryContainer, scheme.surfaceContainerHighest,
                    scheme.surfaceContainerHigh, scheme.background) + PRESET_SEEDS.map { androidx.compose.ui.graphics.Color(it) }
                for (fill in fills) {
                    val ring = focusRingColor(scheme, fill)
                    assertTrue("seed %08X hc=$hc $theme on %08X".format(seed, fill.toArgb()), PondMath.contrast(ring, fill) >= 3f)
                }
            }
        }
    }

    @Test fun theRingIsDrawnInsideTheControlSoAClippingParentOrAScrollEdgeKeepsIt() {
        val width = 3f * 2.625f // 3dp at 420 dpi
        for (size in listOf(Size(120f, 64f), Size(48f, 48f), Size(1080f, 147f), Size(8f, 8f))) {
            val ring = focusRingOutline(size, width)!!
            // The stroke spans the outline plus or minus half a width.
            assertTrue("$size: left edge inside", ring.left - width / 2 >= 0f)
            assertTrue("$size: top edge inside", ring.top - width / 2 >= 0f)
            assertTrue("$size: right edge inside", ring.right + width / 2 <= size.width)
            assertTrue("$size: bottom edge inside", ring.bottom + width / 2 <= size.height)
            // And on the edge, not floating inside it: the outer edge is the control's edge.
            assertEquals(0f, ring.left - width / 2, 0.01f)
            assertEquals(size.width, ring.right + width / 2, 0.01f)
        }
        assertNull("too small for a ring", focusRingOutline(Size(6f, 6f), width))
    }

    @Test fun theAccentIsTheRingWhenItStandsOut() {
        val scheme = Palette.light(TimerStore.DEFAULT_SEED, monochrome = false)
        assertEquals(scheme.primary, focusRingColor(scheme))
    }

    @Test fun aFocusedTimeButtonShowsItsRingAndLosesItWhenFocusMoves() {
        rule.setContent {
            TimerTheme {
                androidx.compose.foundation.layout.Row {
                    ChunkyButton(label = "+30s", emphasized = false, enabled = true, heightDp = 64, fontSize = 18, onClick = {})
                    ChunkyButton(label = "+1m", emphasized = true, enabled = true, heightDp = 64, fontSize = 18, onClick = {})
                }
            }
        }
        val first = rule.onNodeWithText("+30s")
        val second = rule.onNodeWithText("+1m")
        first.assert(SemanticsMatcher.expectValue(FocusRingShown, false))
        first.performSemanticsAction(SemanticsActions.RequestFocus)
        rule.waitForIdle()
        first.assert(SemanticsMatcher.expectValue(FocusRingShown, true))
        second.assert(SemanticsMatcher.expectValue(FocusRingShown, false))
        second.performSemanticsAction(SemanticsActions.RequestFocus)
        rule.waitForIdle()
        first.assert(SemanticsMatcher.expectValue(FocusRingShown, false))
        second.assert(SemanticsMatcher.expectValue(FocusRingShown, true))
    }
}
