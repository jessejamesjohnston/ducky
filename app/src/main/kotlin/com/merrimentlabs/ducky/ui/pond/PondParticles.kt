package com.merrimentlabs.ducky.ui.pond

import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * The pond's particles, in fixed pools so a frame allocates nothing: water drops thrown up by
 * splashes and bumps, bubbles (rising from the floor, or from the duck when it ruffles its
 * feathers), ripples on the surface, and the Zs over a napping duck. [PondSim] owns one, spawns
 * into it and steps it each frame; Pond draws the live entries.
 *
 * It shares the simulation's random source, so a seeded pond plays out the same way every run.
 */
class PondParticles internal constructor(private val sim: PondSim, private val rng: Random) {

    companion object {
        const val DROPS = 48
        const val BUBBLES = 24
        const val RIPPLES = 10
        const val ZS = 3
        private const val TWO_PI = (2 * PI).toFloat()
    }

    val dropX = FloatArray(DROPS); val dropY = FloatArray(DROPS)
    private val dropVx = FloatArray(DROPS); private val dropVy = FloatArray(DROPS)
    val dropR = FloatArray(DROPS); val dropLife = FloatArray(DROPS)

    val bubX = FloatArray(BUBBLES); val bubY = FloatArray(BUBBLES)
    val bubR = FloatArray(BUBBLES); val bubLife = FloatArray(BUBBLES)
    val bubAir = BooleanArray(BUBBLES)
    private val bubVy = FloatArray(BUBBLES); private val bubPhase = FloatArray(BUBBLES)
    private val bubBaseX = FloatArray(BUBBLES)

    val ripX = FloatArray(RIPPLES); val ripAge = FloatArray(RIPPLES); val ripMax = FloatArray(RIPPLES)

    val zX = FloatArray(ZS); val zY = FloatArray(ZS); val zAge = FloatArray(ZS)

    private fun dp(v: Float) = v * sim.density
    private fun rand(a: Float, b: Float) = a + (b - a) * rng.nextFloat()

    internal fun burstAtDuck(n: Int, vMin: Float, vMax: Float) {
        for (i in 0 until n) {
            val x = sim.duckX + rand(-dp(44f), dp(44f))
            spawnDrop(
                x, sim.surfaceAt(x),
                (x - sim.duckX) / dp(44f) * rand(dp(60f), dp(170f)), -rand(dp(vMin), dp(vMax)),
                rand(dp(2f), dp(4.4f)),
            )
        }
    }

    internal fun burstAcross(n: Int) {
        for (i in 0 until n) {
            val x = rand(0f, sim.width)
            spawnDrop(x, sim.surfaceAt(x), rand(-dp(90f), dp(90f)), -rand(dp(260f), dp(640f)), rand(dp(1.8f), dp(4.6f)))
        }
    }

    internal fun spawnDrop(x: Float, y: Float, vx: Float, vy: Float, r: Float) {
        if (sim.reduced) return
        for (i in 0 until DROPS) if (dropLife[i] <= 0f) {
            dropX[i] = x; dropY[i] = y; dropVx[i] = vx; dropVy[i] = vy; dropR[i] = r; dropLife[i] = 1f
            return
        }
    }

    internal fun spawnRipple(x: Float, maxR: Float) {
        if (sim.reduced) return
        var oldest = 0
        for (i in 0 until RIPPLES) {
            if (ripAge[i] >= 1.4f || ripMax[i] == 0f) { oldest = i; break }
            if (ripAge[i] > ripAge[oldest]) oldest = i
        }
        ripX[oldest] = x; ripAge[oldest] = 0f; ripMax[oldest] = maxR
    }

    internal fun spawnAirBubble() {
        for (i in 0 until BUBBLES) if (bubLife[i] <= 0f) {
            bubAir[i] = true
            // Behind the duck (tail side), so none crosses its face or beak.
            bubBaseX[i] = sim.duckX - sim.pose.facing * rand(dp(8f), dp(70f))
            bubX[i] = bubBaseX[i]
            bubY[i] = sim.floorY - rand(dp(4f), dp(22f))
            bubVy[i] = rand(dp(50f), dp(110f))
            bubR[i] = rand(dp(3f), dp(7.5f))
            bubPhase[i] = rand(0f, TWO_PI)
            bubLife[i] = rand(1.1f, 2.0f)
            return
        }
    }

    internal fun spawnWaterBubble() {
        for (i in 0 until BUBBLES) if (bubLife[i] <= 0f) {
            bubAir[i] = false
            bubBaseX[i] = rand(sim.width * 0.06f, sim.width * 0.94f)
            bubX[i] = bubBaseX[i]
            bubY[i] = sim.floorY - rand(dp(2f), dp(30f))
            bubVy[i] = rand(dp(34f), dp(70f))
            bubR[i] = rand(dp(2f), dp(5f))
            bubPhase[i] = rand(0f, TWO_PI)
            bubLife[i] = 60f
            return
        }
    }

    internal fun spawnZ() {
        for (i in 0 until ZS) if (zAge[i] <= 0f || zAge[i] >= 2.6f) {
            zX[i] = sim.duckX + (DuckPainter.RIGHT - 18f) * sim.duckScale * sim.pose.facing
            zY[i] = sim.duckY + (DuckPainter.TOP + 4f) * sim.duckScale
            zAge[i] = 0.0001f
            return
        }
    }

    /** Drops fly and fall back in (some leave a ripple); ripples spread and fade. */
    internal fun stepDropsAndRipples(dt: Float) {
        val g = dp(1900f)
        for (i in 0 until DROPS) if (dropLife[i] > 0f) {
            dropVy[i] += g * dt
            dropX[i] += dropVx[i] * dt
            dropY[i] += dropVy[i] * dt
            if (dropVy[i] > 0f && dropY[i] > sim.surfaceAt(dropX[i])) {
                dropLife[i] = 0f
                if (rng.nextFloat() < 0.35f) spawnRipple(dropX[i], dp(16f))
            }
            if (dropY[i] > sim.height + dp(20f) || dropX[i] < -dp(20f) || dropX[i] > sim.width + dp(20f)) dropLife[i] = 0f
        }
        for (i in 0 until RIPPLES) if (ripMax[i] > 0f && ripAge[i] < 1.4f) ripAge[i] += dt
    }

    /** Bubbles rise and wobble (water bubbles pop at the surface); Zs drift up. */
    internal fun stepBubblesAndZs(dt: Float, time: Float) {
        for (i in 0 until BUBBLES) if (bubLife[i] > 0f) {
            bubLife[i] -= dt
            bubY[i] -= bubVy[i] * dt
            bubX[i] = bubBaseX[i] + sin(time * 3.1f + bubPhase[i]) * dp(3.5f)
            if (!bubAir[i] && bubY[i] - bubR[i] < sim.surfaceAt(bubX[i])) {
                bubLife[i] = 0f
                spawnRipple(bubX[i], dp(12f))
            }
        }
        for (i in 0 until ZS) if (zAge[i] > 0f && zAge[i] < 2.6f) {
            zAge[i] += dt
            zY[i] -= dp(20f) * dt
            zX[i] += sin(zAge[i] * 2.4f) * dp(10f) * dt
        }
    }

    /** Empties every pool (sim.reduced motion turned on, or a still pond). */
    internal fun clear() {
        dropLife.fill(0f); bubLife.fill(0f); ripMax.fill(0f); zAge.fill(0f)
    }
}
