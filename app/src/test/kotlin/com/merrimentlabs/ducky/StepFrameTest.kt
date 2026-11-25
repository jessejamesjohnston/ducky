package com.merrimentlabs.ducky

import com.merrimentlabs.ducky.ui.pond.HopMood
import com.merrimentlabs.ducky.ui.pond.StepFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The counted widget pictures ("Bubbles", "Egg timer"): which still shows when. */
class StepFrameTest {

    private val twoMinutes = 120_000L

    /** Every 5 s step of a 2-minute timer, the widget's tick floor on a Pixel phone. */
    private fun steps(total: Long, count: Int) =
        (total downTo 1L step 5_000L).map { it to StepFrame.of(HopMood.RUNNING, it, total, count) }

    @Test fun `a 2-minute timer at every 5 s step, 8 items`() {
        // 8 items: one slice is 15 s. An item goes at the end of its slice.
        val expected = mapOf(
            120_000L to 8, 115_000L to 8, 110_000L to 8, 105_000L to 7, 100_000L to 7,
            95_000L to 7, 90_000L to 6, 75_000L to 5, 60_000L to 4, 45_000L to 3,
            30_000L to 2, 20_000L to 2, 15_000L to 1, 10_000L to 1, 5_000L to 1,
        )
        val frames = steps(twoMinutes, 8).toMap()
        for ((remaining, left) in expected) {
            assertEquals("left at ${remaining / 1000} s", left, frames.getValue(remaining).left)
        }
        // The whole run: never grows, steps by at most one item per 5 s, and each change
        // happens exactly on a slice edge.
        var last = 8
        for ((remaining, f) in steps(twoMinutes, 8)) {
            assertTrue("never grows", f.left <= last)
            assertTrue("one item per step", last - f.left <= 1)
            // A multiple of 15 s lies in [remaining, remaining + 5 s): the slice ended in this step.
            if (f.left < last) assertTrue("change at $remaining ms is on a slice edge", (remaining + 4_999L) / 15_000L > (remaining - 1L) / 15_000L)
            else assertTrue("no change at $remaining ms means no edge", (remaining + 4_999L) / 15_000L == (remaining - 1L) / 15_000L || remaining == twoMinutes)
            last = f.left
        }
        assertEquals("the last item stays until the ring", 1, last)
    }

    @Test fun `the picture only changes at slice edges, so frames inside one slice are equal`() {
        val a = StepFrame.of(HopMood.RUNNING, 118_000L, twoMinutes, 8)
        val b = StepFrame.of(HopMood.RUNNING, 106_000L, twoMinutes, 8)
        val c = StepFrame.of(HopMood.RUNNING, 104_999L, twoMinutes, 8)
        assertEquals(a, b)
        assertTrue(a != c)
        // Exact at the edge: with 105 000 ms left the first slice is over.
        assertEquals(7, StepFrame.of(HopMood.RUNNING, 105_000L, twoMinutes, 8).left)
        assertEquals(8, StepFrame.of(HopMood.RUNNING, 105_001L, twoMinutes, 8).left)
    }

    @Test fun `the last minute starts at 60 s left, while running only`() {
        assertTrue(StepFrame.of(HopMood.RUNNING, 60_000L, twoMinutes, 8).lastMinute)
        assertFalse(StepFrame.of(HopMood.RUNNING, 60_001L, twoMinutes, 8).lastMinute)
        assertTrue(StepFrame.of(HopMood.RUNNING, 1L, twoMinutes, 8).lastMinute)
        // A paused timer naps; it never shows the get-ready cue.
        assertFalse(StepFrame.of(HopMood.PAUSED, 30_000L, twoMinutes, 8).lastMinute)
        // A timer under a minute is in its last minute from the start.
        assertTrue(StepFrame.of(HopMood.RUNNING, 45_000L, 45_000L, 5).lastMinute)
        // A long timer: the cue is wide enough for its 15 s ticks to land four times.
        val long = 3_600_000L
        val ticks = (60_000L downTo 1L step 15_000L).count { StepFrame.of(HopMood.RUNNING, it, long, 8).lastMinute }
        assertEquals(4, ticks)
    }

    @Test fun `ringing shows every item gone, idle shows them all`() {
        val ringing = StepFrame.of(HopMood.DONE, 0L, twoMinutes, 6)
        assertEquals(0, ringing.left)
        assertEquals(6, ringing.gone)
        assertFalse(ringing.lastMinute)
        // Test alarm: a ring with total 0.
        assertEquals(0, StepFrame.of(HopMood.DONE, 0L, 0L, 6).left)
        val idle = StepFrame.of(HopMood.IDLE, 0L, 0L, 6)
        assertEquals(6, idle.left)
        assertFalse(idle.lastMinute)
    }

    @Test fun `paused freezes the frame it paused on`() {
        val running = StepFrame.of(HopMood.RUNNING, 70_000L, twoMinutes, 8)
        val paused = StepFrame.of(HopMood.PAUSED, 70_000L, twoMinutes, 8)
        assertEquals(running.left, paused.left)
        assertEquals(paused, StepFrame.of(HopMood.PAUSED, 70_000L, twoMinutes, 8))
    }

    @Test fun `odd inputs stay in range`() {
        // The wall clock set back: more left than the total.
        assertEquals(8, StepFrame.of(HopMood.RUNNING, 500_000L, twoMinutes, 8).left)
        // Deadline passed but the ring not yet processed: the last item stays.
        assertEquals(1, StepFrame.of(HopMood.RUNNING, -3_000L, twoMinutes, 8).left)
        assertEquals(1, StepFrame.of(HopMood.RUNNING, 0L, twoMinutes, 8).left)
        // No total recorded.
        assertEquals(1, StepFrame.of(HopMood.RUNNING, 30_000L, 0L, 8).left)
        // Count floor.
        assertEquals(1, StepFrame.of(HopMood.IDLE, 0L, 0L, 0).count)
        // A 24-hour timer does not overflow.
        assertEquals(8, StepFrame.of(HopMood.RUNNING, 86_400_000L, 86_400_000L, 8).left)
    }

    /**
     * Every render of a run, the way the widget gets them: one at the start, then one per
     * display tick. The tick asks for total / 90 (3 to 15 s), AlarmManager will not fire it
     * sooner than 5 s, and on a Pixel phone a render landed up to 0.8 s after its tick.
     * Returns the items left at each render, then 0
     * for the ring.
     */
    private fun run(total: Long, widthCount: Int, lateMs: (Int) -> Long): List<Int> {
        val interval = maxOf(5_000L, (total / 90).coerceIn(3_000L, 15_000L))
        val count = StepFrame.itemsFor(widthCount, total)
        val lefts = mutableListOf<Int>()
        var t = 0L
        var k = 0
        while (t < total) {
            lefts += StepFrame.of(HopMood.RUNNING, total - t, total, count).left
            k++
            t = k * interval + lateMs(k)
        }
        lefts += StepFrame.of(HopMood.DONE, 0L, total, count).left
        return lefts
    }

    private val totals = listOf(
        5_000L, 10_000L, 15_000L, 20_000L, 30_000L, 45_000L, 59_000L, 60_000L, 90_000L, 120_000L,
        300_000L, 600_000L, 1_800_000L, 3_600_000L,
    )

    @Test fun `a short timer gets fewer items, so no slice is shorter than 7 s`() {
        assertEquals(1, StepFrame.itemsFor(8, 5_000L))
        assertEquals(1, StepFrame.itemsFor(8, 13_999L))
        assertEquals(2, StepFrame.itemsFor(8, 14_000L))
        assertEquals(4, StepFrame.itemsFor(8, 30_000L))
        assertEquals(6, StepFrame.itemsFor(8, 45_000L))
        assertEquals(8, StepFrame.itemsFor(8, 60_000L))
        assertEquals(3, StepFrame.itemsFor(3, 3_600_000L))
        // No timer (idle, Test alarm): the full row.
        assertEquals(7, StepFrame.itemsFor(7, 0L))
        for (total in totals) for (width in 3..8) {
            val n = StepFrame.itemsFor(width, total)
            assertTrue("${total / 1000} s, $width wide: $n", n in 1..width)
            if (n > 1) assertTrue("${total / 1000} s: slice ${total / n} ms", total / n >= StepFrame.MIN_SLICE_MS)
        }
    }

    @Test fun `just past the deadline, before the ring is recorded, the last item stays`() {
        val now = 1_000_000L
        assertTrue(StepFrame.ringPending(now - 1L, now, paused = false, ringing = false))
        assertTrue(StepFrame.ringPending(now, now, paused = false, ringing = false))
        assertTrue(StepFrame.ringPending(now - 59_000L, now, paused = false, ringing = false))
        // Not yet due, long gone (a force-stopped app), paused, ringing or no timer: not pending.
        assertFalse(StepFrame.ringPending(now + 1L, now, paused = false, ringing = false))
        assertFalse(StepFrame.ringPending(now - 61_000L, now, paused = false, ringing = false))
        assertFalse(StepFrame.ringPending(now - 1L, now, paused = true, ringing = false))
        assertFalse(StepFrame.ringPending(now - 1L, now, paused = false, ringing = true))
        assertFalse(StepFrame.ringPending(0L, now, paused = false, ringing = false))
        // The widget then draws it as running with nothing left: one item, not a full row.
        assertEquals(1, StepFrame.of(HopMood.RUNNING, 0L, 30_000L, 4).left)
    }

    @Test fun `adding time to a short timer grows the row, it never drops two`() {
        // 30 s timer, 4 bubbles; at 20 s left, +30 s: 50 s of 60 s left, 8 bubbles, 7 standing.
        val before = StepFrame.of(HopMood.RUNNING, 20_000L, 30_000L, StepFrame.itemsFor(8, 30_000L))
        val after = StepFrame.of(HopMood.RUNNING, 50_000L, 60_000L, StepFrame.itemsFor(8, 60_000L))
        assertEquals(3, before.left)
        assertEquals(8, after.count)
        assertEquals(7, after.left)
    }

    @Test fun `at every render of every run, at most one item goes`() {
        val lateness = listOf<(Int) -> Long>(
            { 0L },                               // on time
            { 800L },                             // every render late by the most seen
            { k -> if (k % 2 == 0) 800L else 0L }, // alternating: the widest gap
            { k -> (k * 337L) % 801L },           // scattered
        )
        val failures = mutableListOf<String>()
        for (total in totals) for (width in 3..8) for ((i, late) in lateness.withIndex()) {
            val lefts = run(total, width, late)
            val drops = lefts.zipWithNext { a, b -> a - b }
            if (drops.any { it > 1 } || drops.any { it < 0 }) {
                failures += "${total / 1000} s, $width wide, lateness $i: $lefts"
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test fun `the last item is still there at the last render before the ring`() {
        for (total in totals) for (width in 3..8) {
            val lefts = run(total, width) { 800L }
            assertEquals("${total / 1000} s, $width wide: $lefts", 1, lefts[lefts.size - 2])
        }
    }
}
