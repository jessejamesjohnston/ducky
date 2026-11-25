package com.merrimentlabs.ducky

import android.os.Looper
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.ComposeTestRule
import org.robolectric.Shadows.shadowOf

/**
 * Compose tests for Ducky's screens. Two things keep the stock helpers from working here:
 * the pond animates forever (a self-advancing clock never lets Compose go idle), and every
 * screen waits for its first DataStore read, which arrives from a real IO thread that the
 * virtual frame clock cannot speed up. So tests drive the clock by hand ([pump]) and give IO
 * real time while they wait for a node ([awaitNode]).
 */
fun ComposeTestRule.manualClock() {
    mainClock.autoAdvance = false
}

/** Advances [frames] frames of virtual time, yielding a little real time to IO each frame. */
fun ComposeTestRule.pump(frames: Int = 10) {
    repeat(frames) {
        Thread.sleep(2)
        mainClock.advanceTimeByFrame()
    }
}

/** Pumps until [find] matches a node (or fails after [timeoutMs] of real time). */
fun ComposeTestRule.awaitNode(
    timeoutMs: Long = 5_000,
    find: ComposeTestRule.() -> SemanticsNodeInteraction,
): SemanticsNodeInteraction {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (true) {
        val node = find()
        val found = runCatching { node.fetchSemanticsNode() }.isSuccess
        if (found) return node
        if (System.currentTimeMillis() > deadline) return node.also { it.fetchSemanticsNode() }
        // DataStore's update to a screen is a main-looper message that frame pumping alone never
        // runs; drain it too, or the wait can pass only when something else happened to (CI
        // failed on this while a faster machine never did).
        if (!mainClock.autoAdvance) shadowOf(Looper.getMainLooper()).idle()
        pump(1)
    }
}
