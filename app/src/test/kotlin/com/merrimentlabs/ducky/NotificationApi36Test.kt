package com.merrimentlabs.ducky

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The countdown card as Android 16+ builds it: the PLATFORM Notification.Builder with a
 * ProgressStyle, because the compat builder drops the segment colors.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NotificationApi36Test {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val nm = context.getSystemService(NotificationManager::class.java)
    private val realWidgetRefresher = TimerController.widgetRefresher

    @Before
    fun setUp() = runBlocking {
        TimerController.widgetRefresher = {}
        TimerStore.clearTimer(context)
    }

    @After
    fun tearDown() = runBlocking {
        TimerStore.clearTimer(context)
        TimerController.resetWidgetDelivery()
        TimerController.widgetRefresher = realWidgetRefresher
    }

    /** The countdown card (TimerController's private COUNTDOWN_NOTIFICATION_ID). */
    private fun card(): Notification = shadowOf(nm).getNotification(1).also { assertNotNull(it) }

    private fun style(n: Notification) =
        Notification.Builder.recoverBuilder(context, n).style as Notification.ProgressStyle

    @Test
    fun `a running timer posts a ProgressStyle card of eight two-tone water segments`() = runBlocking {
        TimerController.addTime(context, 120_000L)
        val n = card()
        assertEquals(Notification.ProgressStyle::class.java.name, n.extras.getString(Notification.EXTRA_TEMPLATE))
        assertTrue("promoted ongoing (Live Update)", n.extras.getBoolean("android.requestPromotedOngoing"))
        assertTrue(n.extras.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN))
        assertEquals(Notification.CATEGORY_PROGRESS, n.category)

        val segments = style(n).progressSegments
        assertEquals(TimerNotifications.WAVE_SEGMENTS, segments.size)
        // At the platform's limit Android may merge the bar into one plain segment: stay under it.
        val limit = Notification.ProgressStyle::class.java.getDeclaredField("MAX_PROGRESS_SEGMENT_LIMIT")
            .apply { isAccessible = true }.getInt(null)
        assertTrue("${segments.size} segments, platform limit $limit", segments.size < limit)
        assertEquals("even, so the bar starts and ends on different tones", 0, segments.size % 2)
        assertEquals("segments add up to the total", 120_000, segments.sumOf { it.length })
        val accent = n.color
        val lighter = segments[1].color
        assertNotEquals(accent, lighter)
        for ((i, s) in segments.withIndex()) assertEquals("segment $i", if (i % 2 == 0) accent else lighter, s.color)
        // The light tone is the accent pushed toward white, channel by channel.
        for (ch in listOf(Color::red, Color::green, Color::blue)) assertTrue(ch(lighter) >= ch(accent))
        assertNotNull("Ducky rides the bar", style(n).progressTrackerIcon)

        assertEquals(listOf("Pause", "Cancel"), n.actions.map { it.title.toString() })
    }

    @Test
    fun `a paused timer keeps the Live Update with the frozen time and offers Resume`() = runBlocking {
        TimerController.addTime(context, 90_000L)
        TimerController.pause(context)
        val n = card()
        val remaining = TimerStore.state(context).pausedRemainingMillis
        assertTrue(n.extras.getBoolean("android.requestPromotedOngoing"))
        assertEquals(TimerMath.formatClock(remaining), n.shortCriticalText)
        assertEquals(listOf("Resume", "Cancel"), n.actions.map { it.title.toString() })
    }
}
