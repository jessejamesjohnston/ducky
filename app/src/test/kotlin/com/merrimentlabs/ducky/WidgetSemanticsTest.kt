package com.merrimentlabs.ducky

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.Chronometer
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What TalkBack hears on a widget. The time box speaks the state ("No timer set. Opens Ducky.");
 * the clock inside it must not add its own stop: a Chronometer describes itself as a duration
 * (now minus its base), not as its text, so a stopped 00:00 was read as "2 seconds".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WidgetSemanticsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `the widget clock is hidden from screen readers in both fonts`() {
        for (layout in listOf(R.layout.widget_chrono_center, R.layout.widget_chrono_center_mono)) {
            val root = LayoutInflater.from(context).inflate(layout, null)
            val name = context.resources.getResourceEntryName(layout)
            val ticking = root.findViewById<View>(R.id.widget_chrono)
            val stopped = root.findViewById<TextView>(R.id.widget_clock_frozen)
            assertEquals(name, Chronometer::class.java, ticking.javaClass)
            assertEquals(name, TextView::class.java, stopped.javaClass)
            for (view in listOf(ticking, stopped)) {
                assertEquals(name, View.IMPORTANT_FOR_ACCESSIBILITY_NO, view.importantForAccessibility)
                assertFalse(name, view.isImportantForAccessibility)
            }
            // Same text settings, so swapping between them never changes the slot's size.
            val t = ticking as TextView
            assertEquals(name, t.textSize, stopped.textSize, 0f)
            assertEquals(name, t.typeface, stopped.typeface)
            assertEquals(name, t.gravity, stopped.gravity)
            assertEquals(name, t.includeFontPadding, stopped.includeFontPadding)
            assertEquals(name, t.fontFeatureSettings, stopped.fontFeatureSettings)
        }
    }

    @Test
    fun `the time box names each state in words, never as a bare duration`() {
        assertEquals("No timer set. Opens Ducky.", context.getString(R.string.a11y_widget_idle))
        assertEquals("Time's up, ringing. Opens Ducky.", context.getString(R.string.a11y_widget_ringing))
        assertEquals("Time's up, ringing", context.getString(R.string.a11y_widget_ringing_row))
    }

    @Test
    fun `the ring state is called Time's up on the alarm, the notification and the widget`() {
        assertEquals("Time's up", context.getString(R.string.alarm_done))
        assertEquals("Time's up", context.getString(R.string.widget_done))
    }
}
