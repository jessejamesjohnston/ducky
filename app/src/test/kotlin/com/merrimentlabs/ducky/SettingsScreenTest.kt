package com.merrimentlabs.ducky

import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.ComposeTestRule
import android.app.Activity
import android.app.Application
import android.appwidget.AppWidgetManager
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.rules.ActivityScenarioRule
import com.merrimentlabs.ducky.ui.PRESET_SEEDS
import com.merrimentlabs.ducky.ui.SettingsScreen
import com.merrimentlabs.ducky.ui.TimerTheme
import com.merrimentlabs.ducky.widget.BubblesWidgetReceiver
import com.merrimentlabs.ducky.widget.EggTimerWidgetReceiver
import com.merrimentlabs.ducky.widget.PondWidgetReceiver
import com.merrimentlabs.ducky.widget.TimerWidgetReceiver
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

private typealias HostRule = AndroidComposeTestRule<ActivityScenarioRule<ComponentActivity>, ComponentActivity>

/**
 * [awaitNode] that also drains the main looper each pass: DataStore and IO hand-backs are posted
 * there, and frame pumping alone does not run them (the licenses list went missing about one run
 * in three after ComposeSmokeTest shared the JVM).
 */
private fun ComposeTestRule.settingsAwaitDrained(
    timeoutMs: Long = 5_000,
    find: ComposeTestRule.() -> SemanticsNodeInteraction,
): SemanticsNodeInteraction = awaitNode(timeoutMs) {
    shadowOf(Looper.getMainLooper()).idle()
    waitForIdle()
    find()
}

/** Every settings key back at its default, and no timer: the state a fresh install starts in. */
private fun resetSettingsStore(context: Context) = runBlocking {
    TimerStore.clearTimer(context)
    TimerStore.setSoundUri(context, null)
    TimerStore.setVibrate(context, true)
    TimerStore.DEFAULT_INCREMENTS.forEachIndexed { i, s -> TimerStore.setIncrement(context, i, s) }
    TimerStore.setStyle(context, TimerStore.DEFAULT_STYLE)
    TimerStore.setSeedColor(context, TimerStore.DEFAULT_SEED)
    TimerStore.setHighContrast(context, false)
}

/**
 * Settings writes run on the controller's Main.immediate scope and land through DataStore's IO
 * thread, so a tap's effect shows up a little later in real time: pump frames (and the main
 * looper) until [read] satisfies [until], or fail with the last value read.
 */
private fun <T> HostRule.awaitStore(
    timeoutMs: Long = 5_000,
    read: suspend (Context) -> T,
    until: (T) -> Boolean,
): T {
    val context = activity.applicationContext
    val deadline = System.currentTimeMillis() + timeoutMs
    while (true) {
        val value = runBlocking { read(context) }
        if (until(value)) return value
        if (System.currentTimeMillis() > deadline) throw AssertionError("store never matched; last: $value")
        pump(1)
        shadowOf(Looper.getMainLooper()).idle()
    }
}

/** Gives a write that should NOT happen a fair chance to land, then returns the settings. */
private fun HostRule.settingsAfterSettling(): TimerSettings {
    repeat(20) { pump(1); shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(5) }
    return runBlocking { TimerStore.settings(activity.applicationContext) }
}

/**
 * Settings composed on its own (in TimerTheme, like MainActivity does), with the callbacks
 * counted. Each test asserts what a tap changes in TimerStore, the intent it starts, or the
 * callback it fires. The window is tall enough for the whole page, so every row is on screen
 * and taps land without scrolling; the tests about scrolling use a phone-height window.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h2200dp")
class SettingsScreenTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val context: Context get() = rule.activity.applicationContext
    private val backs = AtomicInteger()
    private val licensesOpened = AtomicInteger()
    private val widgetRefreshes = AtomicInteger()
    private val realWidgetRefresher = TimerController.widgetRefresher

    @Before
    fun setUp() {
        resetSettingsStore(rule.activity.applicationContext)
        // Count widget refreshes instead of rendering Glance (a pinned widget would render).
        TimerController.widgetRefresher = { widgetRefreshes.incrementAndGet() }
        // No manualClock: nothing here animates forever, and a hand-driven clock left layout
        // a frame behind the reads (and stalled the semantics scroll actions).
    }

    @After
    fun tearDown() {
        // A test alarm leaves a ringing session behind; end it so no other class sees it.
        runBlocking {
            val state = TimerStore.state(context)
            if (state.isRinging) TimerController.stopRinging(context, state.ringingSinceMillis)
        }
        resetSettingsStore(context)
        TimerController.resetWidgetDelivery()
        TimerController.widgetRefresher = realWidgetRefresher
        shadowOf(AppWidgetManager.getInstance(context)).setRequestPinAppWidgetSupported(false)
        shadowOf(context as Application).checkActivities(false)
    }

    private fun show(
        revealAbout: Boolean = false,
        onAboutRevealed: (() -> Unit)? = null,
        modifier: Modifier = Modifier,
    ) {
        rule.setContent {
            TimerTheme {
                Box(modifier) {
                    if (onAboutRevealed == null) {
                        SettingsScreen(
                            onBack = { backs.incrementAndGet() },
                            onOpenLicenses = { licensesOpened.incrementAndGet() },
                            revealAbout = revealAbout,
                        )
                    } else {
                        SettingsScreen(
                            onBack = { backs.incrementAndGet() },
                            onOpenLicenses = { licensesOpened.incrementAndGet() },
                            revealAbout = revealAbout,
                            onAboutRevealed = onAboutRevealed,
                        )
                    }
                }
            }
        }
        rule.settingsAwaitDrained { onNodeWithText("Settings") }
    }

    // --- Card 1: sound, vibrate, test alarm, add widget, high contrast -------------------------

    @Test
    fun `the vibrate switch writes the setting both ways without touching widgets`() {
        show()
        rule.onNodeWithText("Vibrate").assertIsOn().performClick()
        rule.awaitStore(read = TimerStore::settings) { !it.vibrate }
        rule.settingsAwaitDrained { onNodeWithText("Vibrate") }.assertIsOff().performClick()
        rule.awaitStore(read = TimerStore::settings) { it.vibrate }
        rule.settingsAfterSettling()
        assertEquals("vibrate is not a widget setting", 0, widgetRefreshes.get())
    }

    @Test
    fun `high contrast turns on and refreshes the widgets`() {
        show()
        rule.onNodeWithText("High contrast").assertIsOff().performClick()
        rule.awaitStore(read = TimerStore::settings) { it.highContrast }
        rule.awaitStore(read = { widgetRefreshes.get() }) { it >= 1 }
        rule.onNodeWithText("High contrast").assertIsOn()
    }

    @Test
    fun `the sound row opens the alarm picker on the current sound and stores the pick`() {
        show()
        rule.onNodeWithText("Alarm sound").performClick()
        rule.pump(2)
        val started = shadowOf(rule.activity).nextStartedActivityForResult
        assertNotNull("the picker never started", started)
        val picker = started.intent
        assertEquals(RingtoneManager.ACTION_RINGTONE_PICKER, picker.action)
        assertEquals(RingtoneManager.TYPE_ALARM, picker.getIntExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, -1))
        assertEquals("Alarm sound", picker.getStringExtra(RingtoneManager.EXTRA_RINGTONE_TITLE))
        // Silent is never offered: the store reads a null pick as "system default".
        assertEquals(false, picker.getBooleanExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true))
        assertEquals(
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
            picker.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, Uri::class.java)
        )

        val picked = Uri.parse("content://media/internal/audio/media/42")
        shadowOf(rule.activity).receiveResult(
            picker, Activity.RESULT_OK,
            Intent().putExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, picked)
        )
        rule.awaitStore(read = TimerStore::settings) { it.soundUri == picked.toString() }
    }

    @Test
    fun `the picker opens on the stored sound, and a null or canceled pick changes nothing`() {
        val stored = "content://media/internal/audio/media/7"
        runBlocking { TimerStore.setSoundUri(context, stored) }
        show()
        rule.onNodeWithText("Alarm sound").performClick()
        rule.pump(2)
        val picker = shadowOf(rule.activity).nextStartedActivityForResult.intent
        assertEquals(
            Uri.parse(stored),
            picker.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, Uri::class.java)
        )

        // OK with no URI (another picker's "Silent"): ignored, never stored as "default".
        shadowOf(rule.activity).receiveResult(picker, Activity.RESULT_OK, Intent())
        assertEquals(stored, rule.settingsAfterSettling().soundUri)

        rule.onNodeWithText("Alarm sound").performClick()
        rule.pump(2)
        val again = shadowOf(rule.activity).nextStartedActivityForResult.intent
        shadowOf(rule.activity).receiveResult(
            again, Activity.RESULT_CANCELED,
            Intent().putExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri.parse("content://x/1"))
        )
        assertEquals(stored, rule.settingsAfterSettling().soundUri)
    }

    @Test
    fun `test alarm from idle starts a real ring session and leaves Settings`() {
        show()
        rule.onNodeWithText("Rings now to check sound and volume").assertIsDisplayed()
        rule.onNodeWithText("Test alarm").assertIsEnabled().performClick()
        assertEquals("Settings hands over to the timer screen", 1, backs.get())
        val state = rule.awaitStore(read = TimerStore::state) { it.isRinging }
        assertEquals("a test ring has no length", 0L, state.totalMillis)
        // The ring service starts right after the ring is persisted, on the controller's thread.
        val service = rule.awaitStore(read = { shadowOf(context as Application).peekNextStartedService() }) { it != null }
        assertEquals(AlarmRingService::class.java.name, service?.component?.className)
    }

    @Test
    fun `test alarm is unavailable while a timer runs`() {
        runBlocking { TimerStore.setTimer(context, System.currentTimeMillis() + 600_000L, 600_000L) }
        show()
        rule.settingsAwaitDrained { onNodeWithText("Available when no timer is set") }
        rule.onNodeWithText("Test alarm").assertIsNotEnabled().performClick()
        rule.pump(5)
        assertEquals(0, backs.get())
        assertTrue(!runBlocking { TimerStore.state(context) }.isRinging)
    }

    @Test
    fun `test alarm is unavailable while a timer is paused or ringing`() {
        runBlocking { TimerStore.setPaused(context, 30_000L) }
        show()
        rule.settingsAwaitDrained { onNodeWithText("Available when no timer is set") }
        rule.onNodeWithText("Test alarm").assertIsNotEnabled()
        // Ringing (a session with no service behind it: only the store is read here).
        runBlocking { TimerStore.setRinging(context, System.currentTimeMillis()) }
        rule.pump(5)
        rule.settingsAwaitDrained { onNodeWithText("Available when no timer is set") }
        rule.onNodeWithText("Test alarm").assertIsNotEnabled()
        runBlocking { TimerStore.clearTimer(context) }
        rule.settingsAwaitDrained { onNodeWithText("Rings now to check sound and volume") }
        rule.onNodeWithText("Test alarm").assertIsEnabled()
    }

    @Test
    fun `a past deadline with no ring counts as idle`() {
        runBlocking { TimerStore.setTimer(context, System.currentTimeMillis() - 1_000L, 60_000L) }
        show()
        rule.settingsAwaitDrained { onNodeWithText("Rings now to check sound and volume") }
        rule.onNodeWithText("Test alarm").assertIsEnabled()
    }

    @Test
    fun `add widget is absent where the launcher cannot pin`() {
        show()
        rule.onNodeWithText("Add widget").assertDoesNotExist()
    }

    @Test
    fun `each widget look in the sheet asks the launcher to pin its own receiver`() {
        val manager = AppWidgetManager.getInstance(context)
        shadowOf(manager).setRequestPinAppWidgetSupported(true)
        show()
        val looks = listOf(
            "Add the Water tank widget. The water drains as the timer runs." to TimerWidgetReceiver::class.java,
            "Add the Lily pads widget. The duck hops pad to pad toward the bank." to PondWidgetReceiver::class.java,
            "Add the Bubbles widget. The bubbles pop one by one." to BubblesWidgetReceiver::class.java,
            "Add the Egg timer widget. The eggs hatch one by one." to EggTimerWidgetReceiver::class.java,
        )
        looks.forEachIndexed { picked, (description, receiver) ->
            rule.onNodeWithText("Add widget").performClick()
            rule.settingsAwaitDrained { onNodeWithText("Add a widget") }
            rule.onNodeWithText("Pick a look. Every Ducky widget shows the same timer.").assertExists()
            rule.pump(60) // let the sheet finish expanding
            rule.settingsAwaitDrained { onNodeWithContentDescription(description) }.performClick()
            rule.pump(60)
            rule.onNodeWithText("Add a widget").assertDoesNotExist()
            looks.forEachIndexed { i, (_, r) ->
                val expected = if (i <= picked) 1 else 0
                assertEquals("${r.simpleName} after picking ${receiver.simpleName}",
                    expected, manager.getAppWidgetIds(ComponentName(context, r)).size)
            }
        }
    }

    @Test
    fun `dismissing the widget sheet pins nothing`() {
        val manager = AppWidgetManager.getInstance(context)
        shadowOf(manager).setRequestPinAppWidgetSupported(true)
        show()
        rule.onNodeWithText("Add widget").performClick()
        rule.settingsAwaitDrained { onNodeWithText("Add a widget") }
        rule.pump(60)
        rule.onNodeWithContentDescription("Close sheet").performClick()
        rule.pump(60)
        rule.onNodeWithText("Add a widget").assertDoesNotExist()
        for (r in listOf(TimerWidgetReceiver::class.java, PondWidgetReceiver::class.java,
            BubblesWidgetReceiver::class.java, EggTimerWidgetReceiver::class.java)) {
            assertEquals(0, manager.getAppWidgetIds(ComponentName(context, r)).size)
        }
    }

    // --- Card 2: time buttons ------------------------------------------------------------------

    @Test
    fun `the steppers walk each button through the choices and refresh the widgets`() {
        show()
        rule.onNodeWithContentDescription("Increase Button 1").performClick()
        rule.awaitStore(read = TimerStore::settings) { it.incrementsSeconds == listOf(45, 60, 300) }
        rule.settingsAwaitDrained { onNodeWithText("45s") }
        rule.onNodeWithContentDescription("Decrease Button 3").performClick()
        rule.awaitStore(read = TimerStore::settings) { it.incrementsSeconds == listOf(45, 60, 180) }
        rule.onNodeWithContentDescription("Decrease Button 2").performClick()
        rule.awaitStore(read = TimerStore::settings) { it.incrementsSeconds == listOf(45, 45, 180) }
        rule.awaitStore(read = { widgetRefreshes.get() }) { it >= 1 }
    }

    @Test
    fun `steppers stop at the ends and snap an off-list value to its neighbors`() {
        runBlocking {
            TimerStore.setIncrement(context, 0, 5)      // the first choice
            TimerStore.setIncrement(context, 1, 50)     // between 45 and 60
            TimerStore.setIncrement(context, 2, 3_600)  // past the last choice (1800)
        }
        show()
        rule.onNodeWithContentDescription("Decrease Button 1").assertIsNotEnabled()
        rule.onNodeWithContentDescription("Increase Button 3").assertIsNotEnabled()

        // 50 sits at 60's slot: up is the choice after 60, down the one before.
        rule.onNodeWithContentDescription("Increase Button 2").performClick()
        rule.awaitStore(read = TimerStore::settings) { it.incrementsSeconds[1] == 90 }
        // Past the end counts as the last slot: down is the second to last.
        rule.onNodeWithContentDescription("Decrease Button 3").performClick()
        rule.awaitStore(read = TimerStore::settings) { it.incrementsSeconds[2] == 1_200 }
        rule.onNodeWithContentDescription("Increase Button 1").assertIsEnabled().performClick()
        rule.awaitStore(read = TimerStore::settings) { it.incrementsSeconds[0] == 10 }
    }

    @Test
    fun `two quick taps on a stepper move two steps`() {
        show() // Button 1 at its default, 30 s
        // The second tap lands before the first one's write comes back to the screen: both run
        // in one main-thread task, so nothing recomposes in between.
        val click = rule.onNodeWithContentDescription("Increase Button 1").fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsActions.OnClick].action!!
        rule.runOnUiThread { click(); click() }
        rule.awaitStore(read = TimerStore::settings) { it.incrementsSeconds[0] == 60 } // 30 -> 45 -> 60
        rule.settingsAfterSettling()
        assertEquals(60, runBlocking { TimerStore.settings(context) }.incrementsSeconds[0])
    }

    @Test
    fun `the last choice disables only the increase stepper`() {
        runBlocking {
            TimerStore.setIncrement(context, 0, 1_800)
            TimerStore.setIncrement(context, 1, 7)
        }
        show()
        rule.onNodeWithContentDescription("Increase Button 1").assertIsNotEnabled()
        rule.onNodeWithContentDescription("Decrease Button 1").assertIsEnabled().performClick()
        rule.awaitStore(read = TimerStore::settings) { it.incrementsSeconds[0] == 1_200 }
        rule.onNodeWithContentDescription("Decrease Button 2").performClick()
        rule.awaitStore(read = TimerStore::settings) { it.incrementsSeconds[1] == 5 }
    }

    // --- Card 3: style -------------------------------------------------------------------------

    @Test
    fun `each style tile applies its style, restyles this page and refreshes the widgets`() {
        show()
        rule.onNodeWithContentDescription("Mountain View style, selected").assertExists()

        rule.onNodeWithContentDescription("London style").performClick()
        rule.awaitStore(read = TimerStore::settings) { it.styleKey == "london" }
        // London labels in capitals, this page's title included.
        rule.settingsAwaitDrained { onNodeWithText("SETTINGS") }
        rule.onNodeWithText("STYLE").assertExists()
        rule.onNodeWithContentDescription("London style, selected").assertExists()

        rule.onNodeWithContentDescription("Seoul style").performClick()
        rule.awaitStore(read = TimerStore::settings) { it.styleKey == "seoul" }
        rule.settingsAwaitDrained { onNodeWithText("Settings") }
        rule.onNodeWithContentDescription("Seoul style, selected").assertExists()

        rule.onNodeWithContentDescription("Beijing style").performClick()
        rule.awaitStore(read = TimerStore::settings) { it.styleKey == "beijing" }
        rule.settingsAwaitDrained { onNodeWithContentDescription("Beijing style, selected") }

        rule.onNodeWithContentDescription("Mountain View style").performClick()
        rule.awaitStore(read = TimerStore::settings) { it.styleKey == TimerStore.DEFAULT_STYLE }
        rule.awaitStore(read = { widgetRefreshes.get() }) { it >= 1 }
    }

    // --- Card 4: accent ------------------------------------------------------------------------

    @Test
    fun `a preset swatch sets the accent and marks itself selected`() {
        show()
        rule.onNodeWithContentDescription("Water blue, selected").assertExists()
        rule.onNodeWithContentDescription("Red").performClick()
        rule.awaitStore(read = TimerStore::settings) { it.seedColorArgb == PRESET_SEEDS[0] }
        rule.settingsAwaitDrained { onNodeWithContentDescription("Red, selected") }
        rule.onNodeWithContentDescription("Water blue").assertExists()
        rule.onNodeWithText("Custom color…").assertExists()
        rule.awaitStore(read = { widgetRefreshes.get() }) { it >= 1 }
    }

    @Test
    fun `a custom accent selects no swatch and offers to change it`() {
        runBlocking { TimerStore.setSeedColor(context, 0xFF123456.toInt()) }
        show()
        rule.settingsAwaitDrained { onNodeWithText("Custom color · tap to change") }
        for (name in listOf("Red", "Water blue", "Brown")) {
            rule.onNodeWithContentDescription("$name, selected").assertDoesNotExist()
            rule.onNodeWithContentDescription(name).assertExists()
        }
    }

    @Test
    fun `a typical phone width lays the swatches out four to a row`() {
        show()
        val top = { name: String -> rule.onNodeWithContentDescription(name).fetchSemanticsNode().positionInRoot.y }
        assertEquals(top("Red"), top("Olive"))
        assertTrue(top("Green") > top("Olive"))
        assertEquals(top("Green"), top("Cyan"))
        assertTrue(top("Indigo") > top("Cyan"))
    }

    @Test
    fun `a very narrow window falls back to two swatches a row`() {
        show(modifier = Modifier.width(150.dp))
        val top = { name: String -> rule.onNodeWithContentDescription(name).fetchSemanticsNode().positionInRoot.y }
        assertEquals(top("Red"), top("Orange"))
        assertTrue(top("Amber") > top("Orange"))
        assertEquals(top("Amber"), top("Olive"))
        assertTrue(top("Green") > top("Olive"))
    }

    // --- Card 5: About -------------------------------------------------------------------------

    @Test
    fun `Help opens the support page in the browser`() {
        show()
        rule.onNodeWithContentDescription("Help, Answers to common questions, opens in browser").performScrollTo().performClick()
        val intent = shadowOf(context as Application).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(Uri.parse("https://merrimentlabs.com/support/ducky"), intent.data)
    }

    @Test
    fun `Privacy opens the policy in the browser`() {
        show()
        rule.onNodeWithContentDescription("Privacy, Ducky collects nothing and can't go online, opens in browser").performClick()
        val intent = shadowOf(context as Application).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(Uri.parse("https://merrimentlabs.com/privacy/ducky"), intent.data)
    }

    @Test
    fun `the maker, source code, license and releases rows open their pages in the browser`() {
        show()
        val rows = listOf(
            "Merriment Labs, Made by Jesse Johnston, opens in browser" to "https://merrimentlabs.com",
            "Source code, github.com/jessejamesjohnston/ducky, opens in browser" to
                "https://github.com/jessejamesjohnston/ducky",
            "License, Apache License 2.0, opens in browser" to
                "https://github.com/jessejamesjohnston/ducky/blob/main/LICENSE",
            "Releases, See the latest version on GitHub, opens in browser" to
                "https://github.com/jessejamesjohnston/ducky/releases",
        )
        for ((row, url) in rows) {
            rule.onNodeWithContentDescription(row).performScrollTo().performClick()
            val intent = shadowOf(context as Application).nextStartedActivity
            assertEquals(row, Intent.ACTION_VIEW, intent.action)
            assertEquals(row, Uri.parse(url), intent.data)
        }
    }

    @Test
    fun `About lists the shared rows first, in order, then Help and Contact`() {
        show()
        val order = listOf(
            "Merriment Labs, ", "Source code, ", "Privacy, ", "Open-source notices, ", "License, ",
            "Releases, ", "Help, ", "Contact, ",
        )
        val tops = order.map { label ->
            rule.onNodeWithContentDescription(label, substring = true).fetchSemanticsNode().positionInRoot.y
        }
        assertEquals("rows out of order: $tops", tops.sorted(), tops)
    }

    @Test
    fun `Contact writes an email to Merriment Labs with the version in the subject`() {
        show()
        rule.onNodeWithContentDescription("Contact, hello@merrimentlabs.com, opens email").performScrollTo().performClick()
        val intent = shadowOf(context as Application).nextStartedActivity
        assertEquals(Intent.ACTION_SENDTO, intent.action)
        assertEquals(Uri.parse("mailto:"), intent.data)
        assertArrayEquals(arrayOf("hello@merrimentlabs.com"), intent.getStringArrayExtra(Intent.EXTRA_EMAIL))
        assertEquals("Ducky ${BuildConfig.VERSION_NAME}", intent.getStringExtra(Intent.EXTRA_SUBJECT))
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        assertTrue("a mail app was found, so nothing is copied", clipboard.primaryClip == null)
    }

    @Test
    fun `with no mail app Contact copies the address, and the browser rows fail quietly`() {
        // Make startActivity throw ActivityNotFoundException for intents nothing handles.
        shadowOf(context as Application).checkActivities(true)
        show()
        rule.onNodeWithContentDescription("Contact, hello@merrimentlabs.com, opens email").performScrollTo().performClick()
        val clip = context.getSystemService(ClipboardManager::class.java).primaryClip
        assertNotNull("the address was not copied", clip)
        assertEquals("Contact", clip!!.description.label)
        assertEquals("hello@merrimentlabs.com", clip.getItemAt(0).text)

        rule.onNodeWithContentDescription("Help, Answers to common questions, opens in browser").performScrollTo().performClick()
        assertNull(shadowOf(context as Application).nextStartedActivity)
        rule.onNodeWithText("Settings").assertExists() // still here, nothing crashed
    }

    @Test
    fun `the licenses row opens the licenses screen`() {
        show()
        rule.onNodeWithContentDescription("Open-source notices, The open-source software Ducky is built with").performClick()
        assertEquals(1, licensesOpened.get())
        assertEquals(0, backs.get())
    }

    @Test
    fun `the About header names the app and its version`() {
        show()
        rule.onNodeWithText("Ducky ${BuildConfig.VERSION_NAME}").assertIsDisplayed()
        rule.onNodeWithText("The kitchen timer you can't miss.").assertExists()
        rule.onNodeWithText("Copyright © 2026 Jesse Johnston. Published by Merriment Labs.").assertExists()
    }

    @Test
    @Config(qualifiers = "w360dp-h640dp")
    fun `coming back from the licenses brings the About card into view`() {
        val revealed = AtomicInteger()
        show(revealAbout = true, onAboutRevealed = { revealed.incrementAndGet() })
        rule.awaitStore(read = { revealed.get() }) { it == 1 }
        rule.pump(10)
        rule.onNodeWithContentDescription("Open-source notices, The open-source software Ducky is built with")
            .assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w360dp-h640dp")
    fun `revealAbout works without a callback`() {
        show(revealAbout = true)
        rule.pump(20)
        rule.onNodeWithContentDescription("Open-source notices, The open-source software Ducky is built with")
            .assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w360dp-h640dp")
    fun `Settings opens at the top when not revealing About`() {
        show()
        rule.pump(10)
        rule.onNodeWithContentDescription("Open-source notices, The open-source software Ducky is built with")
            .assertIsNotDisplayed()
        rule.onNodeWithText("Vibrate").assertIsDisplayed()
    }

    @Test
    fun `the back arrow leaves Settings`() {
        show()
        rule.onNodeWithContentDescription("Back").performClick()
        assertEquals(1, backs.get())
        assertEquals(0, licensesOpened.get())
    }
}
