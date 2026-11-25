package com.merrimentlabs.ducky.ui.pond

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import kotlin.math.asin
import kotlin.math.sqrt

/**
 * Everything that makes the duck look alive, written by the pond simulation each frame and
 * read by [DuckPainter]. Plain fields on purpose: no allocation, no snapshot churn.
 */
class DuckPose {
    /** 1 = eyes open, 0 = closed (blink), in between = drowsy lids. */
    var eyeOpen = 1f
    /** Pupil direction, each -1..1 (y negative = looking up). */
    var lookX = 0f
    var lookY = 0f
    /** Wing lift: 0 = folded, 1 = raised high. */
    var wing = 0f
    /** Beak: 0 = closed, 1 = wide open (quack). */
    var beak = 0f
    /** Positive squashes (wider, shorter), negative stretches. */
    var squash = 0f
    /** Whole-duck rotation in degrees, around the waterline. */
    var rotation = 0f
    /** Which way the duck faces: 1 = right (as on merrimentlabs.com), -1 = left. */
    var facing = 1f
}

/**
 * The merrimentlabs.com duck (its SVG artwork, viewBox 96x80) translated to Compose paths and split
 * into parts that can move: body, belly shade, a wing that flaps around its shoulder, a
 * two-part beak whose lower half opens, and a dot eye with a lid for blinks and dozing.
 *
 * Geometry is in the SVG's own units. The duck's waterline (where it floats) is y = 58 and
 * its horizontal center x = 50; [draw] places that point, so the pond only needs to know
 * where the water is. Parsed once; drawing allocates nothing.
 */
class DuckPainter {

    private fun parse(d: String): Path = PathParser().parsePathString(d).toPath()

    private val body = parse(
        "M14 46c0-8 7-12 16-12h20c3-3 3-8 1-13-3-8 3-17 13-17 9 0 15 7 14 15-1 5-3 8-6 10 " +
            "7 2 12 8 12 17 0 14-13 24-35 24S14 62 14 46z"
    )
    private val shade = parse(
        "M14 46c0-8 7-12 16-12h20c-6 6-7 13-2 20 5 7 16 8 27 3-4 11-16 17-32 17C27 74 14 62 14 46z"
    )
    private val wingShape = parse("M58 45C51 39.5 38 40 31.5 45.5C37 52 48 54.5 58 49.5Z")
    private val wingLine = parse("M34 48c6 5 16 6 24 1")
    private val beakUpper = parse("M77 18c6-1 12 0 15 3c-3 1.2-9 1.6-15 0.8z")
    private val beakLower = parse("M77 21.3c6 1 11.5 0.6 15-0.3c-2 3-8 5-15 4.5z")
    private val closedEye = parse("M64.6 15.4Q68 18.2 71.4 15.4")

    private val outlineStroke = Stroke(width = 2.6f, join = StrokeJoin.Round)
    private val edgeStroke = Stroke(width = 1.1f, join = StrokeJoin.Round)
    private val wingStroke = Stroke(width = 3.2f, cap = StrokeCap.Round)
    private val eyeStroke = Stroke(width = 1.5f, cap = StrokeCap.Round)
    private val lidStroke = 1.0f

    /**
     * Draw the duck with its waterline point at ([x], [y]) in the current canvas, [scale] px
     * per SVG unit. [outline] (high contrast) adds a crisp ink outline.
     */
    fun DrawScope.drawDuck(
        pose: DuckPose,
        x: Float,
        y: Float,
        scale: Float,
        outline: Color?,
        alpha: Float = 1f,
    ) {
        val sq = pose.squash.coerceIn(-0.3f, 0.3f)
        val fx = if (pose.facing >= 0f) 1f else -1f
        withTransform({
            translate(x, y)
            rotate(pose.rotation, Offset.Zero)
            scale(scale * fx, scale, Offset.Zero)
            translate(-50f, -58f)
            // Squash and stretch about the duck's bottom, so a landing stays planted.
            scale(1f + sq * 0.7f, 1f - sq, Offset(50f, 70f))
        }) {
            drawPath(body, DuckColors.BODY, alpha)
            drawPath(shade, DuckColors.SHADE, alpha * 0.55f)
            if (outline == null) drawPath(body, DuckColors.EDGE, alpha * 0.40f, style = edgeStroke)

            // Wing: rotates around the shoulder; positive degrees lift the tip.
            rotate(degrees = pose.wing.coerceIn(-0.3f, 1.2f) * 40f, pivot = Offset(57f, 46f)) {
                drawPath(wingShape, DuckColors.WING, alpha)
                drawPath(wingLine, DuckColors.WING_LINE, alpha, style = wingStroke)
                if (outline != null) drawPath(wingShape, outline, alpha, style = edgeStroke)
            }

            // Cheek: a little warmth under the eye.
            drawOval(
                DuckColors.CHEEK, topLeft = Offset(67.6f, 21.4f), size = Size(6.6f, 3.8f),
                alpha = alpha * 0.30f,
            )

            // Beak: the lower half opens around the corner of the mouth.
            rotate(degrees = pose.beak.coerceIn(0f, 1f) * 26f, pivot = Offset(77.5f, 21.6f)) {
                drawPath(beakLower, DuckColors.BEAK_DARK, alpha)
                if (outline != null) drawPath(beakLower, outline, alpha, style = edgeStroke)
            }
            drawPath(beakUpper, DuckColors.BEAK, alpha)
            if (outline != null) {
                drawPath(beakUpper, outline, alpha, style = edgeStroke)
                drawPath(body, outline, alpha, style = outlineStroke)
            }

            drawEye(pose, alpha)
        }
    }

    private fun DrawScope.drawEye(pose: DuckPose, alpha: Float) {
        val open = pose.eyeOpen.coerceIn(0f, 1f)
        if (open < 0.14f) {
            // Closed: a soft happy curve instead of a squashed dot.
            drawPath(closedEye, DuckColors.INK, alpha, style = eyeStroke)
            return
        }
        val r = 3.3f
        val cx = 68f + pose.lookX.coerceIn(-1f, 1f) * 0.9f
        val cy = 15f + pose.lookY.coerceIn(-1f, 1f) * 0.8f
        // The lid is a horizontal chord: the visible eye is the circle segment below it.
        // d runs from -r (wide open) to +r (shut); drawArc(useCenter = false) fills that
        // segment directly, so no clip path (and no per-frame allocation) is needed.
        val d = -r + (1f - open) * 2f * r
        val theta = Math.toDegrees(asin((d / r).toDouble().coerceIn(-1.0, 1.0))).toFloat()
        drawArc(
            DuckColors.INK,
            startAngle = theta,
            sweepAngle = 180f - 2f * theta,
            useCenter = false,
            topLeft = Offset(cx - r, cy - r),
            size = Size(2f * r, 2f * r),
            alpha = alpha,
        )
        if (open > 0.8f) {
            drawCircle(Color.White, radius = 1.05f, center = Offset(cx + 1.2f, cy - 1.2f), alpha = alpha)
        }
        if (open < 0.92f) {
            val half = sqrt((r * r - d * d).coerceAtLeast(0f)) + 0.5f
            drawLine(
                DuckColors.INK, Offset(cx - half, cy + d), Offset(cx + half, cy + d),
                strokeWidth = lidStroke, cap = StrokeCap.Round, alpha = alpha,
            )
        }
    }

    companion object {
        /** Duck extents in SVG units relative to its waterline point (x 50, y 58). */
        const val TOP = -54f
        const val BOTTOM = 12f
        const val LEFT = -36f
        const val RIGHT = 42f
        const val WIDTH_UNITS = RIGHT - LEFT

        /** Horizontal offset of the duck's tail and head, for ripples. */
        fun tailOffset(facing: Float) = -30f * facing
    }
}
