package com.merrimentlabs.ducky

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.merrimentlabs.ducky.ui.pond.DuckColors
import com.merrimentlabs.ducky.ui.pond.DuckPainter
import com.merrimentlabs.ducky.ui.pond.DuckPose
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/**
 * The duck's eye, drawn big (20 px per SVG unit): wide open it is a dot, drowsy a lid
 * covers its top with a line a little wider than the eye, shut it is a soft curve.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DuckPainterTest {

    private val scale = 20f
    private val ox = 1000f
    private val oy = 1300f

    private fun render(eyeOpen: Float): PixelMap {
        val pose = DuckPose().apply { this.eyeOpen = eyeOpen }
        val img = ImageBitmap(2000, 1800)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(img), Size(2000f, 1800f)) {
            with(DuckPainter()) { drawDuck(pose, ox, oy, scale, outline = null) }
        }
        return img.toPixelMap()
    }

    /** The pixel at SVG point ([u], [v]); the duck's waterline point (50, 58) sits at (ox, oy). */
    private fun PixelMap.at(u: Float, v: Float) = this[(ox + (u - 50f) * scale).toInt(), (oy + (v - 58f) * scale).toInt()]

    private fun Color.isNear(o: Color) =
        abs(red - o.red) < 0.04f && abs(green - o.green) < 0.04f && abs(blue - o.blue) < 0.04f

    // The eye is a circle of radius 3.3 around (68, 15) when looking straight ahead.

    @Test fun `wide open, the eye is a full dot with no lid`() {
        val px = render(1f)
        assertTrue("top of the eye", px.at(68f, 12.6f).isNear(DuckColors.INK))
        assertTrue("bottom of the eye", px.at(68f, 17.4f).isNear(DuckColors.INK))
        assertTrue("no lid beside the eye: ${px.at(71.55f, 15f)}", px.at(71.55f, 15f).isNear(DuckColors.BODY))
    }

    @Test fun `drowsy, a lid hides the top of the eye and its line overhangs the eye`() {
        val px = render(0.5f) // the lid sits across the middle
        assertTrue("top half hidden: ${px.at(68f, 13.2f)}", px.at(68f, 13.2f).isNear(DuckColors.BODY))
        assertTrue("bottom half shows", px.at(68f, 17f).isNear(DuckColors.INK))
        assertTrue("lid line past the right edge: ${px.at(71.55f, 15f)}", px.at(71.55f, 15f).isNear(DuckColors.INK))
        assertTrue("lid line past the left edge", px.at(64.45f, 15f).isNear(DuckColors.INK))
        assertTrue("and no further", px.at(72.6f, 15f).isNear(DuckColors.BODY))
    }

    @Test fun `shut, the eye is a soft curve below where the dot was`() {
        val px = render(0.05f)
        assertTrue("no dot at the center: ${px.at(68f, 14f)}", px.at(68f, 14f).isNear(DuckColors.BODY))
        assertTrue("the curve dips under it", px.at(68f, 16.8f).isNear(DuckColors.INK))
    }
}
