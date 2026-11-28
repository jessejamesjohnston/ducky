package com.merrimentlabs.ducky

import com.merrimentlabs.ducky.widget.WidgetForm
import com.merrimentlabs.ducky.widget.widgetFormFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.DataInputStream
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.roundToInt

/**
 * Each widget lands looking like its picker preview: the size a provider declares as its default
 * draws the same layout as the preview image, and a person can still shrink it to the one-row
 * strip.
 */
class WidgetDefaultSizeTest {

    private companion object {
        const val ANDROID = "http://schemas.android.com/apk/res/android"
        /** The previews are renders from a 420 dpi screen (2.625 px per dp), kept in drawable-nodpi. */
        const val PREVIEW_PX_PER_DP = 2.625f
        val PROVIDERS = listOf("timer_widget_info", "pond_widget_info", "bubbles_widget_info", "eggs_widget_info")
    }

    private fun res(): File = listOf(File("src/main/res"), File("app/src/main/res")).first { it.exists() }

    private fun provider(name: String): Element =
        DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder()
            .parse(File(res(), "xml/$name.xml")).documentElement

    private fun Element.attr(name: String): String = getAttributeNS(ANDROID, name)

    private fun Element.dp(name: String): Float = attr(name).removeSuffix("dp").toFloat()

    /** The preview image's size in dp, read from its PNG header. */
    private fun previewDp(p: Element): Pair<Float, Float> {
        val file = File(res(), "drawable-nodpi/${p.attr("previewImage").removePrefix("@drawable/")}.png")
        DataInputStream(file.inputStream()).use { png ->
            png.skipBytes(16) // signature (8) + IHDR length and type (8)
            return png.readInt() / PREVIEW_PX_PER_DP to png.readInt() / PREVIEW_PX_PER_DP
        }
    }

    @Test fun everyWidgetDefaultsToThreeCellsByTwo() {
        for (name in PROVIDERS) {
            val p = provider(name)
            assertEquals("$name targetCellWidth", "3", p.attr("targetCellWidth"))
            assertEquals("$name targetCellHeight", "2", p.attr("targetCellHeight"))
        }
    }

    @Test fun everyWidgetsDefaultSizeDrawsTheLayoutItsPreviewShows() {
        for (name in PROVIDERS) {
            val p = provider(name)
            val (w, h) = previewDp(p)
            assertEquals("$name preview is the 200 x 140 dp render", 200 to 140, w.roundToInt() to h.roundToInt())
            val previewForm = widgetFormFor(w, h)
            assertEquals("$name preview layout", WidgetForm.STACKED_THREE, previewForm)
            assertEquals("$name default size layout", previewForm, widgetFormFor(p.dp("minWidth"), p.dp("minHeight")))
            assertTrue("$name default size fits inside its preview", p.dp("minWidth") <= w && p.dp("minHeight") <= h + 0.5f)
        }
    }

    @Test fun everyWidgetCanStillShrinkToTheStrip() {
        for (name in PROVIDERS) {
            val p = provider(name)
            assertEquals("$name resizes both ways", "horizontal|vertical", p.attr("resizeMode"))
            assertEquals("$name smallest size", WidgetForm.COMPACT, widgetFormFor(p.dp("minResizeWidth"), p.dp("minResizeHeight")))
        }
    }
}
