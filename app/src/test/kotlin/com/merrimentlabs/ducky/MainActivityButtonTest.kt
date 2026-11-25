package com.merrimentlabs.ducky

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** ChunkyButton on its own: the paths the timer screen's callers never take. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MainActivityButtonTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun `without a spoken label the visible label is what TalkBack reads, and taps land`() {
        var taps = 0
        rule.setContent {
            ChunkyButton(label = "+2m", emphasized = true, enabled = true, heightDp = 64, fontSize = 20) { taps++ }
        }
        rule.onNodeWithText("+2m")
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.ContentDescription))
            .performClick()
        assertEquals(1, taps)
    }

    @Test
    fun `a disabled tile ignores taps`() {
        var taps = 0
        rule.setContent {
            ChunkyButton(label = "Cancel", emphasized = false, enabled = false, heightDp = 64, fontSize = 17) { taps++ }
        }
        rule.onNodeWithText("Cancel").assertIsNotEnabled().performClick()
        assertEquals(0, taps)
    }
}
