package com.merrimentlabs.ducky

import android.content.Context
import android.content.Intent
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Chronometer
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The debug-only widget gallery: one label plus one rendered widget per size, for the mode and
 * look the launch intent asks for (`adb shell am start ... --es mode paused --es look eggs`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WidgetPreviewActivityTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val defaultSizes = listOf(390 to 100, 320 to 70, 250 to 70, 200 to 140, 130 to 130, 130 to 60)

    private fun walk(v: View): List<View> =
        listOf(v) + if (v is ViewGroup) (0 until v.childCount).flatMap { walk(v.getChildAt(it)) } else emptyList()

    /** Launches the gallery and waits (bounded) until it has drawn [entries] label + widget pairs. */
    private fun launch(entries: Int, extras: Intent.() -> Unit = {}): LinearLayout {
        val intent = Intent(context, WidgetPreviewActivity::class.java).apply(extras)
        val activity = Robolectric.buildActivity(WidgetPreviewActivity::class.java, intent).setup().get()
        val scroll = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as ScrollView
        val column = scroll.getChildAt(0) as LinearLayout
        val deadline = System.nanoTime() + 30_000_000_000L
        while (column.childCount < entries * 2) {
            shadowOf(Looper.getMainLooper()).idle()
            if (System.nanoTime() > deadline) throw AssertionError("gallery drew ${column.childCount} of ${entries * 2} views")
            Thread.sleep(10)
        }
        assertEquals(entries * 2, column.childCount)
        return column
    }

    private fun labels(column: LinearLayout) = (0 until column.childCount step 2).map { (column.getChildAt(it) as TextView).text.toString() }

    private fun widget(column: LinearLayout, index: Int) = walk(column.getChildAt(index * 2 + 1))

    private fun timeBox(views: List<View>) =
        views.mapNotNull { it.contentDescription?.toString() }.firstOrNull { it.endsWith("Opens Ducky.") || it == "Time's up, ringing" }

    @Test
    fun `with no extras it draws a running tank at all six sizes`() {
        val column = launch(defaultSizes.size)
        assertEquals(defaultSizes.map { (w, h) -> "$w × $h · running" }, labels(column))
        for (i in defaultSizes.indices) {
            val views = widget(column, i)
            assertTrue(timeBox(views), timeBox(views)!!.startsWith("Timer, about"))
            // Each widget is laid out at its size in px.
            val (w, h) = defaultSizes[i]
            val lp = column.getChildAt(i * 2 + 1).layoutParams
            assertEquals((w * context.resources.displayMetrics.density).toInt(), lp.width)
            assertEquals((h * context.resources.displayMetrics.density).toInt(), lp.height)
        }
    }

    @Test
    fun `a paused bubbles widget at one requested size`() {
        val column = launch(1) {
            putExtra("mode", "paused"); putExtra("look", "bubbles")
            putExtra("width", 200); putExtra("height", 140); putExtra("remaining", 45_000L)
        }
        assertEquals(listOf("200 × 140 · paused"), labels(column))
        val views = widget(column, 0)
        assertEquals("Timer paused, 45 seconds left. Opens Ducky.", timeBox(views))
        assertEquals("00:45", views.filterIsInstance<Chronometer>().single().text.toString())
    }

    @Test
    fun `a ringing lily pad widget in London high contrast`() {
        val column = launch(1) {
            putExtra("mode", "ringing"); putExtra("pond", true); putExtra("style", "london"); putExtra("hc", true)
            putExtra("seed", 0xFFD32F2F.toInt()); putExtra("width", 130); putExtra("height", 60)
        }
        assertEquals(listOf("130 × 60 · ringing"), labels(column))
        val views = widget(column, 0)
        assertEquals("Time's up, ringing", timeBox(views))
        val texts = views.filterIsInstance<TextView>().map { it.text.toString() }
        assertTrue(texts.toString(), texts.containsAll(listOf("+1 MIN", "STOP")))
    }

    @Test
    fun `an idle egg timer widget`() {
        val column = launch(1) {
            putExtra("mode", "idle"); putExtra("look", "eggs"); putExtra("width", 390); putExtra("height", 100)
        }
        assertEquals(listOf("390 × 100 · idle"), labels(column))
        assertEquals("No timer set. Opens Ducky.", timeBox(widget(column, 0)))
    }

    @Test
    fun `an unknown look falls back to the tank`() {
        val column = launch(1) {
            putExtra("look", "kites"); putExtra("width", 200); putExtra("height", 140); putExtra("total", 600_000L)
        }
        assertTrue(timeBox(widget(column, 0))!!.startsWith("Timer, about"))
    }
}
