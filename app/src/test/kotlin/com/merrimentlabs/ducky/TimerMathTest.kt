package com.merrimentlabs.ducky

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TimerMathTest {

    @Test
    fun `tap while idle starts timer from now`() {
        assertEquals(1_030_000L, TimerMath.newEndTime(1_000_000L, 0L, 30_000L))
    }

    @Test
    fun `tap while running extends the end time`() {
        // running until 1_060_000, +1m -> 1_120_000
        assertEquals(1_120_000L, TimerMath.newEndTime(1_000_000L, 1_060_000L, 60_000L))
    }

    @Test
    fun `tap after expiry starts fresh from now, not from the stale end`() {
        assertEquals(1_030_000L, TimerMath.newEndTime(1_000_000L, 900_000L, 30_000L))
    }

    @Test
    fun `additive taps accumulate - brief example`() {
        // +1m, +1m, +30s at the same instant -> one 2:30 timer
        val now = 1_000_000L
        var end = 0L
        end = TimerMath.newEndTime(now, end, 60_000L)
        end = TimerMath.newEndTime(now, end, 60_000L)
        end = TimerMath.newEndTime(now, end, 30_000L)
        assertEquals(now + 150_000L, end)
    }

    @Test
    fun `clock formatting rounds up partial seconds`() {
        assertEquals("2:30", TimerMath.formatClock(150_000L))
        assertEquals("2:30", TimerMath.formatClock(149_400L)) // 149.4s displays as 2:30
        assertEquals("0:00", TimerMath.formatClock(0L))
        assertEquals("0:00", TimerMath.formatClock(-5L))
        assertEquals("12:00", TimerMath.formatClock(720_000L))
        assertEquals("1:02:05", TimerMath.formatClock(3_725_000L))
    }

    @Test
    fun `increment labels`() {
        val context: Context = ApplicationProvider.getApplicationContext()
        assertEquals("30s", TimerMath.formatIncrement(context, 30))
        assertEquals("1m", TimerMath.formatIncrement(context, 60))
        assertEquals("5m", TimerMath.formatIncrement(context, 300))
        assertEquals("1m 30s", TimerMath.formatIncrement(context, 90))
        assertEquals("+1m 30s", TimerMath.formatAddIncrement(context, 90))
    }

    @Test
    fun `spoken remaining time rounds up partial seconds like the clock`() {
        val context: Context = ApplicationProvider.getApplicationContext()
        // The readout shows 0:01 for 1 ms left; TalkBack must not say "0 seconds" for it.
        assertEquals("1 second", TimerMath.speakClock(context, 1L))
        assertEquals("1 minute 30 seconds", TimerMath.speakClock(context, 89_001L))
        assertEquals("2 minutes", TimerMath.speakClock(context, 120_000L))
        assertEquals("0 seconds", TimerMath.speakClock(context, -5_000L))
    }
}
