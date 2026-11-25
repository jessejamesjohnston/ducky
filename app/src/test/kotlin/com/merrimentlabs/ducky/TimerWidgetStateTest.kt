package com.merrimentlabs.ducky

import android.content.Context
import androidx.glance.state.GlanceState
import androidx.test.core.app.ApplicationProvider
import com.merrimentlabs.ducky.widget.TimerWidgetState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TimerWidgetStateTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `Glance reads current timer and settings through the same cached store`() = runBlocking {
        GlanceState.getValue(context, TimerWidgetState, "test-widget")
        TimerStore.setTimer(context, 90_000L, 120_000L)
        TimerStore.touch(context, 10_000L)
        TimerStore.setStyle(context, "london")
        val value = GlanceState.getValue(context, TimerWidgetState, "test-widget")
        assertEquals(90_000L, TimerStore.stateOf(value).endTimeMillis)
        assertEquals(10_000L, TimerStore.stateOf(value).lastTickMillis)
        assertEquals("london", TimerStore.settingsOf(value).styleKey)
    }

    @Test
    fun `deleting a widget preserves timer and settings for the app and other widgets`() = runBlocking {
        TimerStore.setPaused(context, 45_000L)
        TimerStore.setStyle(context, "seoul")
        GlanceState.getValue(context, TimerWidgetState, "deleted-widget")
        GlanceState.deleteStore(context, TimerWidgetState, "deleted-widget")
        val value = GlanceState.getValue(context, TimerWidgetState, "remaining-widget")
        assertEquals(45_000L, TimerStore.stateOf(value).pausedRemainingMillis)
        assertEquals("seoul", TimerStore.settingsOf(value).styleKey)
    }

    @Test
    fun `a running widget speaks the time left no finer than a minute`() {
        // Read up to one display tick (15 s) after the render: never seconds while running.
        assertEquals("under a minute", TimerMath.speakApproxLeft(context, 59_999L))
        assertEquals("under a minute", TimerMath.speakApproxLeft(context, 0L))
        assertEquals("about 1 minute", TimerMath.speakApproxLeft(context, 60_000L))
        assertEquals("about 1 minute", TimerMath.speakApproxLeft(context, 89_999L))
        assertEquals("about 2 minutes", TimerMath.speakApproxLeft(context, 90_000L))
        assertEquals("about 1 hour 5 minutes", TimerMath.speakApproxLeft(context, 65 * 60_000L + 10_000L))
        assertEquals(
            "Timer, about 12 minutes left. Opens Ducky.",
            context.getString(R.string.a11y_widget_running, TimerMath.speakApproxLeft(context, 12 * 60_000L - 5_000L)),
        )
    }
}
