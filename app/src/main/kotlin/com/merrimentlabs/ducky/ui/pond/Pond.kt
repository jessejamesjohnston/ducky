package com.merrimentlabs.ducky.ui.pond

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.HapticFeedbackConstants
import android.provider.Settings
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.merrimentlabs.ducky.TimerState
import kotlin.coroutines.coroutineContext
import kotlin.math.min
import kotlin.math.sin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * The pond: the full-screen water surface of the timer, with the duck riding it.
 *
 * Decorative by contract: it has no semantics of its own (the screen's readout carries the
 * time and state for TalkBack), and it only READS timer state. The frame loop runs while the
 * composable is on screen (Compose's frame clock pauses when the activity stops); the
 * accelerometer is registered on resume and unregistered on pause, and never at all under
 * reduced motion. Its reading tilts the water and sends the duck gliding (PondSim, DuckGlide);
 * it is used for drawing only, never stored or sent.
 */
@Composable
fun Pond(
    sim: PondSim,
    state: TimerState,
    colors: PondColors,
    reducedMotion: Boolean,
    basinCorner: Dp,
    modifier: Modifier = Modifier,
    /**
     * Square bottom corners: beside the controls (two panes) the pond runs to the window's
     * bottom edge, where its rounding left wedges of sky (the window or the screen rounds its own
     * outer corner).
     */
    squareBottomCorners: Boolean = false,
) {
    val density = LocalDensity.current.density
    SideEffect {
        sim.density = density
        sim.reduced = reducedMotion
        sim.setState(state)
    }

    // Only while started: the frame clock already parks in the background, but the
    // reduced-motion poll below is a plain delay loop and would keep waking.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val view = LocalView.current
    LaunchedEffect(sim, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var last = 0L
            var bumps = sim.hardBumps
            sim.onShown()
            while (isActive) {
                if (sim.reduced) {
                    // Still pond: re-derive the level a few times a second, draw only on change.
                    last = 0L
                    sim.step(0f, System.currentTimeMillis())
                    delay(200)
                    continue
                }
                withFrameNanos { t ->
                    val dt = if (last == 0L) 1f / 60f else (t - last) / 1_000_000_000f
                    last = t
                    sim.step(dt, System.currentTimeMillis())
                }
                // A hard bump against the pond's wall: one light tick, as the app's buttons
                // give. performHapticFeedback follows the system's touch-feedback setting.
                if (sim.hardBumps != bumps) {
                    bumps = sim.hardBumps
                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                }
            }
        }
    }

    val context = LocalContext.current
    LifecycleResumeEffect(reducedMotion) {
        val sm = if (reducedMotion) null else context.getSystemService(SensorManager::class.java)
        val sensor = sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val listener = object : SensorEventListener {
            private var lastTs = 0L
            override fun onSensorChanged(e: SensorEvent) {
                val dt = if (lastTs == 0L) 0.02f
                else ((e.timestamp - lastTs) / 1_000_000_000f).coerceIn(0.001f, 0.2f)
                lastTs = e.timestamp
                sim.onAccelerometer(e.values[0], e.values[1], e.values[2], view.display?.rotation ?: 0, dt)
            }
            override fun onAccuracyChanged(s: Sensor?, accuracy: Int) = Unit
        }
        if (sm != null && sensor != null) {
            sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
        }
        onPauseOrDispose {
            sm?.unregisterListener(listener)
            sim.onSensorGone()
        }
    }

    val duck = remember { DuckPainter() }
    val visitorPainter = remember { VisitorPainter() }
    Spacer(
        modifier = modifier.drawWithCache {
            val corner = if (squareBottomCorners) 0f else basinCorner.toPx()
            val k = PondCache(size.width, size.height, density, colors, corner)
            sim.width = size.width
            sim.height = size.height
            onDrawBehind {
                sim.tick.longValue // frame invalidation: draw only, never recomposition
                drawPond(sim, colors, k, duck, visitorPainter)
            }
        }
    )
}

/**
 * Reduced motion: the system "Remove animations" switch (animator duration scale 0). The
 * recomposer's [MotionDurationScale] is live snapshot state, so flipping the switch while the
 * screen is up takes effect without a relaunch; the platform setting is the fallback.
 */
@Composable
fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    val initial = remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    return produceState(initial) {
        val m = coroutineContext[MotionDurationScale] ?: return@produceState
        snapshotFlow { m.scaleFactor == 0f }.collect { value = it }
    }.value
}

/** Everything the draw pass reuses: built once per size/colors, never per frame. */
private class PondCache(val w: Float, val h: Float, private val density: Float, c: PondColors, corner: Float) {
    private fun dp(v: Float) = v * density

    val n = 64
    val front = FloatArray(n + 1)
    val back = FloatArray(n + 1)
    val frontPath = Path()
    val backPath = Path()
    val crestPath = Path()

    val basin = Path().apply {
        addRoundRect(
            RoundRect(
                0f, 0f, w, h,
                topLeftCornerRadius = CornerRadius.Zero, topRightCornerRadius = CornerRadius.Zero,
                bottomRightCornerRadius = CornerRadius(corner), bottomLeftCornerRadius = CornerRadius(corner),
            )
        )
    }
    val hazeH = dp(70f)
    /**
     * The haze continues below the mean surface (hidden under the water) so a tilted or
     * sloshing surface never uncovers a hard edge on its low side. Covers the clamped tilt
     * (tan 0.325 is about 0.34 of the half width) plus the slosh.
     */
    val hazeBelow = w * 0.2f + dp(24f)
    val hazeBrush = Brush.verticalGradient(
        0f to Color.Transparent,
        hazeH / (hazeH + hazeBelow) to c.haze.copy(alpha = 0.55f),
        1f to c.haze.copy(alpha = 0.55f),
        startY = -hazeH, endY = hazeBelow,
    )
    val waterBrush = Brush.verticalGradient(
        0f to c.waterTop.copy(alpha = if (c.highContrast) 1f else 0.9f),
        0.05f to c.waterTop,
        1f to c.waterDeep,
        startY = 0f, endY = h * 0.9f,
    )
    val floorH = dp(64f)
    val floorBrush = Brush.verticalGradient(
        0f to Color.Transparent, 1f to c.floorShade.copy(alpha = 0.45f), startY = h - floorH, endY = h,
    )
    val shaftBrush = Brush.verticalGradient(0f to c.shaft, 1f to Color.Transparent, startY = 0f, endY = 1f)
    val shaftPath = Path().apply {
        moveTo(-0.5f, 0f); lineTo(0.5f, 0f); lineTo(0.85f, 1f); lineTo(-0.85f, 1f); close()
    }
    val zPath = Path().apply { moveTo(0f, 0f); lineTo(1f, 0f); lineTo(0f, 1f); lineTo(1f, 1f) }

    val bandStroke = Stroke(width = dp(10f), join = StrokeJoin.Round)
    val crestStroke = Stroke(width = dp(2f), cap = StrokeCap.Round, join = StrokeJoin.Round)
    val lineStroke = Stroke(width = dp(3f), cap = StrokeCap.Round, join = StrokeJoin.Round)
    val rippleStroke = Stroke(width = dp(1.6f))
    val bubbleStroke = Stroke(width = dp(1.3f))
    val zStroke = Stroke(width = 0.2f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    val dotSize = dp(2.2f)

    /** London: a dot-matrix grid, clipped to the water at draw time. */
    val dots: List<Offset> = if (c.dots == null) emptyList() else buildList {
        val step = dp(7f)
        var y = step / 2f
        var row = 0
        while (y < h) {
            var x = if (row % 2 == 0) step / 2f else step
            while (x < w) { add(Offset(x, y)); x += step }
            y += step; row++
        }
    }
}

private fun DrawScope.drawPond(sim: PondSim, c: PondColors, k: PondCache, duck: DuckPainter, visitors: VisitorPainter) {
    val w = size.width
    val h = size.height
    drawRect(c.sky)
    if (sim.duckX.isNaN()) return // not stepped yet
    val d = density
    val mean = sim.meanY

    for (i in 0..k.n) {
        val x = w * i / k.n
        k.front[i] = sim.surfaceAt(x) - mean
        k.back[i] = sim.backSurfaceAt(x) - mean
    }
    val bottom = h - mean + d
    k.backPath.rewind()
    k.frontPath.rewind()
    k.crestPath.rewind()
    k.backPath.moveTo(0f, k.back[0])
    k.frontPath.moveTo(0f, k.front[0])
    k.crestPath.moveTo(0f, k.front[0])
    for (i in 1..k.n) {
        val x = w * i / k.n
        k.backPath.lineTo(x, k.back[i])
        k.frontPath.lineTo(x, k.front[i])
        k.crestPath.lineTo(x, k.front[i])
    }
    k.backPath.lineTo(w, bottom); k.backPath.lineTo(0f, bottom); k.backPath.close()
    k.frontPath.lineTo(w, bottom); k.frontPath.lineTo(0f, bottom); k.frontPath.close()

    clipPath(k.basin) {
        translate(top = mean) {
            drawRect(k.hazeBrush, topLeft = Offset(0f, -k.hazeH), size = Size(w, k.hazeH + k.hazeBelow))
            drawPath(k.backPath, c.backWave, alpha = if (c.highContrast) 1f else 0.62f)
        }

        with(duck) { drawDuck(sim.pose, sim.duckX, sim.duckY, sim.duckScale, c.duckOutline) }

        translate(top = mean) {
            drawPath(k.frontPath, k.waterBrush)
            clipPath(k.frontPath) {
                if (!c.highContrast) drawShafts(sim, k, h - mean)
                translate(top = -mean) {
                    // Under the water only (this clip), behind the readout (a layer above).
                    with(visitors) { drawVisitor(sim.visitors) }
                    if (c.dots != null) {
                        drawPoints(k.dots, PointMode.Points, c.dots, strokeWidth = k.dotSize, cap = StrokeCap.Round)
                    }
                    drawWaterBubbles(sim, c, k)
                    drawRect(k.floorBrush, topLeft = Offset(0f, h - k.floorH), size = Size(w, k.floorH))
                }
                if (!c.highContrast) {
                    translate(top = 5f * d) { drawPath(k.crestPath, c.crest, alpha = 0.16f, style = k.bandStroke) }
                }
            }
            // The highlight line steps aside behind the readout, so it never runs through the
            // hint (the water's color still changes there, and so does the text's ink).
            val crestAlpha = if (c.dark) 0.55f else 0.85f
            drawAroundReadout(sim, sim.crestGap()) { fade ->
                drawPath(k.crestPath, c.crest, alpha = crestAlpha * fade, style = k.crestStroke)
                c.surfaceLine?.let { drawPath(k.crestPath, it, alpha = fade, style = k.lineStroke) }
            }
        }

        // Ripples ride the surface, spreading and fading.
        for (i in 0 until PondParticles.RIPPLES) {
            val max = sim.particles.ripMax[i]
            val age = sim.particles.ripAge[i]
            if (max <= 0f || age >= 1.4f) continue
            val t = age / 1.4f
            val r = max * (1f - (1f - t) * (1f - t))
            val x = sim.particles.ripX[i]
            val y = sim.surfaceAt(x)
            drawOval(
                c.crest, topLeft = Offset(x - r, y - r * 0.26f), size = Size(2f * r, r * 0.52f),
                alpha = (1f - t) * (if (c.dark) 0.45f else 0.7f), style = k.rippleStroke,
            )
        }
    }

    // Above everything: droplets, the alarm's bubbles, sleepy Zs.
    for (i in 0 until PondParticles.DROPS) {
        if (sim.particles.dropLife[i] <= 0f) continue
        drawCircle(c.droplet, radius = sim.particles.dropR[i], center = Offset(sim.particles.dropX[i], sim.particles.dropY[i]))
        drawCircle(
            Color.White, radius = sim.particles.dropR[i] * 0.38f,
            center = Offset(sim.particles.dropX[i] - sim.particles.dropR[i] * 0.3f, sim.particles.dropY[i] - sim.particles.dropR[i] * 0.3f),
            alpha = 0.7f,
        )
    }
    for (i in 0 until PondParticles.BUBBLES) {
        if (sim.particles.bubLife[i] <= 0f || !sim.particles.bubAir[i]) continue
        val a = min(1f, sim.particles.bubLife[i] / 0.3f)
        val r = sim.particles.bubR[i]
        val center = Offset(sim.particles.bubX[i], sim.particles.bubY[i])
        drawCircle(c.waterTop, radius = r, center = center, alpha = 0.18f * a)
        drawCircle(c.waterDeep, radius = r, center = center, alpha = 0.8f * a, style = k.bubbleStroke)
        drawCircle(Color.White, radius = r * 0.28f, center = Offset(center.x - r * 0.35f, center.y - r * 0.35f), alpha = a)
    }
    for (i in 0 until PondParticles.ZS) {
        val age = sim.particles.zAge[i]
        if (age <= 0f || age >= 2.6f) continue
        val a = min(min(1f, age / 0.4f), (2.6f - age) / 0.9f).coerceIn(0f, 1f)
        val s = (7f + age * 3.2f) * d
        withTransform({
            translate(sim.particles.zX[i], sim.particles.zY[i])
            scale(s, s, Offset.Zero)
        }) {
            drawPath(k.zPath, c.inkAbove, alpha = a * 0.6f, style = k.zStroke)
        }
    }
}

/**
 * Runs [draw] with an alpha factor of 1 away from the readout and 1 - [gap] behind it (its width
 * plus 8 dp each side), ramping between the two over 16 dp in a few steps. The clips are
 * vertical strips through the whole pond, so a line passing under the readout fades out and back
 * in, wherever its waves carry it.
 */
private inline fun DrawScope.drawAroundReadout(sim: PondSim, gap: Float, draw: DrawScope.(Float) -> Unit) {
    if (gap <= 0f) { draw(1f); return }
    val d = density
    val l = sim.readoutLeft - 8f * d
    val r = sim.readoutRight + 8f * d
    val fade = 16f * d
    val steps = 6
    val step = fade / steps
    val far = 100_000f
    clipRect(l - fade, -far, r + fade, far, ClipOp.Difference) { draw(1f) }
    if (gap < 1f) clipRect(l, -far, r, far) { draw(1f - gap) }
    for (i in 0 until steps) {
        val a = 1f - gap * (i + 0.5f) / steps
        clipRect(l - fade + i * step, -far, l - fade + (i + 1) * step, far) { draw(a) }
        clipRect(r + fade - (i + 1) * step, -far, r + fade - i * step, far) { draw(a) }
    }
}

private fun DrawScope.drawShafts(sim: PondSim, k: PondCache, depth: Float) {
    val d = density
    val t = sim.clock
    val len = min(depth * 0.8f, 560f * d)
    if (len < 24f * d) return
    for (i in 0 until 3) {
        val baseX = size.width * (0.18f + 0.33f * i)
        val sway = sin(t * 0.22f + i * 1.7f) * 26f * d
        val width = (54f + 26f * ((i * 7) % 3)) * d
        val a = 0.65f + 0.35f * sin(t * 0.5f + i * 2.3f)
        withTransform({
            translate(baseX + sway, 0f)
            rotate(12f + 3f * sin(t * 0.17f + i), Offset.Zero)
            scale(width, len, Offset.Zero)
        }) {
            drawPath(k.shaftPath, k.shaftBrush, alpha = a)
        }
    }
}

private fun DrawScope.drawWaterBubbles(sim: PondSim, c: PondColors, k: PondCache) {
    for (i in 0 until PondParticles.BUBBLES) {
        if (sim.particles.bubLife[i] <= 0f || sim.particles.bubAir[i]) continue
        val r = sim.particles.bubR[i]
        val center = Offset(sim.particles.bubX[i], sim.particles.bubY[i])
        drawCircle(c.bubble, radius = r, center = center, style = k.bubbleStroke)
        drawCircle(c.bubble, radius = r * 0.3f, center = Offset(center.x - r * 0.35f, center.y - r * 0.35f))
    }
}

/**
 * Clip a readout layer to one side of the live water surface: [above] keeps the part in the
 * air, otherwise the part under water. Two stacked copies of the readout, one per side, give
 * text that changes ink exactly where the water crosses it. Reads [PondSim.tick], so it redraws
 * with the pond and never recomposes.
 */
fun Modifier.pondInk(sim: PondSim, above: Boolean): Modifier = this.drawWithCache {
    val path = Path()
    val n = 24
    onDrawWithContent {
        sim.tick.longValue
        if (sim.readoutTop.isNaN() || sim.duckX.isNaN()) {
            if (above) drawContent()
            return@onDrawWithContent
        }
        val w = size.width
        val hgt = size.height
        val edge = if (above) -hgt else hgt * 2f
        path.rewind()
        path.moveTo(0f, edge)
        path.lineTo(w, edge)
        for (i in n downTo 0) {
            val x = w * i / n
            val y = (sim.surfaceAt(sim.readoutLeft + x) - sim.readoutTop).coerceIn(-hgt, hgt * 2f)
            path.lineTo(x, y)
        }
        path.close()
        clipPath(path) { this@onDrawWithContent.drawContent() }
    }
}
