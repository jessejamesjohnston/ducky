package com.merrimentlabs.ducky

import androidx.compose.ui.semantics.Role
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import com.merrimentlabs.ducky.ui.LicensesScreen
import com.merrimentlabs.ducky.ui.TimerTheme
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Settings > About > Open-source notices: Ducky's own row, then one row per component; each
 * row opens a page of its license text. The data comes from the real res/raw/aboutlibraries.json
 * (OpenSourceLicensesTest checks the file itself).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LicensesScreenTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val app: Context = ApplicationProvider.getApplicationContext()
    private val backs = AtomicInteger()
    private val notices by lazy { OpenSourceLicenses.load(app) }

    // No manualClock here: nothing on this screen animates forever, and the lazy list's
    // scroll-to-node actions need the clock to advance on its own (they spin otherwise).
    @Before
    fun setUp() = resetStyle()

    @After
    fun tearDown() = resetStyle()

    private fun resetStyle() = runBlocking { TimerStore.setStyle(app, TimerStore.DEFAULT_STYLE) }

    private fun show() {
        // Read the expected first card before the screen starts its own IO read of the file.
        val firstLibrary = notices.libraries.first().name
        // A loader that does not suspend: the harness resumes a suspended produceState on the IO
        // worker that finished the read, and the list then lands in the middle of a LazyColumn
        // measure ("Index 6, size 6"). On a phone it resumes on the main thread.
        val loaded = notices
        rule.setContent { TimerTheme { LicensesScreen(onBack = { backs.incrementAndGet() }, load = { loaded }) } }
        rule.awaitNode { onNodeWithText("Ducky") }
        // The library list arrives from an IO read after the first frame.
        rule.awaitNode(timeoutMs = 20_000) { onNode(hasText(firstLibrary)) }
    }

    /** The clickable row titled [name] (its merged node carries the Button role). */
    private fun row(name: String) = rule.onNode(hasText(name) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))

    private fun scrollTo(text: String) {
        rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(text))
        rule.pump(2)
    }

    @Test
    fun `Ducky's own row opens a page with the NOTICE lines and the Apache text, and Back returns`() {
        show()
        rule.onNodeWithText("Open-source notices").assertExists()
        row("Ducky").assert(hasText("Apache License 2.0"))
        rule.onNodeWithText("Copyright 2026 Jesse Johnston", substring = true).assertDoesNotExist()

        row("Ducky").performClick()
        rule.awaitNode { onNodeWithText("Copyright 2026 Jesse Johnston", substring = true) }
        rule.onNodeWithText("TERMS AND CONDITIONS FOR USE, REPRODUCTION, AND DISTRIBUTION", substring = true)
            .assertExists()
        rule.onNodeWithText("Open-source notices").assertDoesNotExist()

        rule.onNodeWithContentDescription("Back").performClick()
        rule.awaitNode { onNodeWithText("Open-source notices") }
        assertEquals("Back on a page returns to the list, not out of the screen", 0, backs.get())
    }

    @Test
    fun `a library row shows version and license and opens its own page`() {
        val core = notices.libraries.first { it.id == "androidx.core:core" }
        val subtitle = "${core.version} · ${core.licenses.joinToString(", ") { it.name }}"
        show()
        scrollTo(core.name)
        row(core.name).assert(hasText(subtitle)).performClick()
        rule.awaitNode { onNodeWithText(core.id) }
        rule.onNodeWithText(core.name).assertExists()
    }

    @Test
    fun `Material Symbols has no version, so its row shows just the license`() {
        show()
        scrollTo("Material Symbols")
        // Other rows read "1.2.3 · Apache License 2.0"; this one is the bare license name.
        row("Material Symbols").assert(hasText("Apache License 2.0")).assert(!hasText("·", substring = true))
        row("Material Symbols").performClick()
        rule.awaitNode { onNodeWithText(OpenSourceLicenses.MATERIAL_SYMBOLS_ID) }
    }

    @Test
    fun `the bundled typeface is listed under its own license`() {
        show()
        scrollTo("Figtree")
        row("Figtree").assert(hasText("SIL Open Font License 1.1", substring = true)).performClick()
        rule.awaitNode { onNodeWithText("SIL OPEN FONT LICENSE Version 1.1", substring = true) }
    }

    @Test
    fun `the list runs from the first library to the last, in name order`() {
        val sorted = notices.libraries.map { it.name }
        assertEquals(sorted.sortedWith(String.CASE_INSENSITIVE_ORDER), sorted)
        show()
        scrollTo(sorted.last())
        row(sorted.last()).assertExists()
        scrollTo(sorted.first())
        row(sorted.first()).assertExists()
    }

    @Test
    fun `Back leaves the licenses`() {
        show()
        rule.onNodeWithContentDescription("Back").performClick()
        assertEquals(1, backs.get())
    }

    @Test
    fun `London capitalizes the title and the label`() {
        runBlocking { TimerStore.setStyle(app, "london") }
        show()
        rule.awaitNode { onNodeWithText("OPEN-SOURCE NOTICES") }
        rule.onNodeWithText("LIBRARIES").assertExists()
    }
}
