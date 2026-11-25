package com.merrimentlabs.ducky

import android.content.Context
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Settings and the licenses screen inside MainActivity, on a phone-height window: the gear,
 * the back arrows, the system Back (BackHandler), and the About card brought back into view
 * when the licenses screen closes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SettingsNavigationTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private val app: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        reset()
        rule.manualClock()
    }

    @After
    fun tearDown() = reset()

    private fun reset() = runBlocking {
        TimerStore.clearTimer(app)
        TimerStore.setStyle(app, TimerStore.DEFAULT_STYLE)
    }

    private val licensesRow = "Open-source notices, The open-source software Ducky is built with"

    private fun openSettings() {
        rule.awaitNode { onNodeWithContentDescription("Settings") }.performClick()
        rule.awaitNode { onNodeWithText("Vibrate") }
    }

    private fun systemBack() {
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.pump(5)
    }

    /** The About card scrolls back into view with an animation: pump until it is on screen. */
    private fun awaitLicensesRowOnScreen() {
        val deadline = System.currentTimeMillis() + 5_000
        val node = rule.onNodeWithContentDescription(licensesRow)
        while (runCatching { node.assertIsDisplayed() }.isFailure) {
            if (System.currentTimeMillis() > deadline) { node.assertIsDisplayed(); return }
            rule.pump(1)
        }
    }

    private fun openLicenses() {
        // The row is below the fold; its one spoken action is the same click.
        rule.onNodeWithContentDescription(licensesRow).performSemanticsAction(SemanticsActions.OnClick)
        rule.awaitNode { onNodeWithText("Libraries") }
        rule.onNodeWithText("Vibrate").assertDoesNotExist()
    }

    @Test
    fun `system Back closes Settings`() {
        openSettings()
        rule.onNodeWithText("Vibrate").assertIsDisplayed()
        systemBack()
        rule.awaitNode { onNodeWithContentDescription("Settings") }
        rule.onNodeWithText("Vibrate").assertDoesNotExist()
    }

    @Test
    fun `the back arrow closes Settings`() {
        openSettings()
        rule.onNodeWithContentDescription("Back").performClick()
        rule.awaitNode { onNodeWithContentDescription("Settings") }
        rule.onNodeWithText("Vibrate").assertDoesNotExist()
    }

    @Test
    fun `system Back from the licenses returns to Settings at the About card`() {
        openSettings()
        rule.onNodeWithContentDescription(licensesRow).assertIsNotDisplayed()
        openLicenses()
        systemBack()
        rule.awaitNode { onNodeWithContentDescription(licensesRow) }
        awaitLicensesRowOnScreen()
        rule.onNodeWithText("Libraries").assertDoesNotExist()
        // One more Back leaves Settings too.
        systemBack()
        rule.awaitNode { onNodeWithContentDescription("Settings") }
        rule.onNodeWithText("Vibrate").assertDoesNotExist()
    }

    @Test
    fun `the licenses back arrow returns to Settings at the About card`() {
        openSettings()
        openLicenses()
        rule.onNodeWithContentDescription("Back").performClick()
        rule.awaitNode { onNodeWithContentDescription(licensesRow) }
        awaitLicensesRowOnScreen()
    }
}
