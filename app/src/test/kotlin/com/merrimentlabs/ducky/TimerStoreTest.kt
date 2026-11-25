package com.merrimentlabs.ducky

import android.content.Context
import androidx.datastore.preferences.core.emptyPreferences
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TimerStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun resetSettings() = runBlocking {
        TimerStore.setSoundUri(context, null)
        TimerStore.setVibrate(context, true)
        TimerStore.DEFAULT_INCREMENTS.forEachIndexed { i, s -> TimerStore.setIncrement(context, i, s) }
        TimerStore.setStyle(context, TimerStore.DEFAULT_STYLE)
        TimerStore.setSeedColor(context, TimerStore.DEFAULT_SEED)
        TimerStore.setHighContrast(context, false)
        TimerStore.clearTimer(context)
        TimerStore.touch(context, 0L) // the heartbeat survives clearTimer
    }

    @Before fun before() = resetSettings()
    @After fun after() = resetSettings()

    @Test
    fun `an empty store reads as the documented defaults`() {
        val s = TimerStore.settingsOf(emptyPreferences())
        assertNull(s.soundUri)
        assertTrue(s.vibrate)
        assertEquals(listOf(30, 60, 300), s.incrementsSeconds)
        assertEquals("mountain_view", s.styleKey)
        assertEquals(0xFF1E88E5.toInt(), s.seedColorArgb)
        assertFalse(s.highContrast)
        assertEquals(TimerState(0L, 0L, 0L, 0L, 0L), TimerStore.stateOf(emptyPreferences()))
    }

    @Test
    fun `every settings setter round-trips through the store and its flow`() = runBlocking {
        TimerStore.setSoundUri(context, "content://media/alarm/7")
        TimerStore.setVibrate(context, false)
        TimerStore.setIncrement(context, 0, 15)
        TimerStore.setIncrement(context, 1, 90)
        TimerStore.setIncrement(context, 2, 600)
        TimerStore.setStyle(context, "london")
        TimerStore.setSeedColor(context, 0xFF43A047.toInt())
        TimerStore.setHighContrast(context, true)

        val expected = TimerSettings(
            soundUri = "content://media/alarm/7",
            vibrate = false,
            incrementsSeconds = listOf(15, 90, 600),
            styleKey = "london",
            seedColorArgb = 0xFF43A047.toInt(),
            highContrast = true,
        )
        assertEquals(expected, TimerStore.settings(context))
        assertEquals(expected, TimerStore.settingsFlow(context).first())

        TimerStore.setSoundUri(context, null) // back to the system default sound
        assertNull(TimerStore.settings(context).soundUri)
    }

    @Test
    fun `timer transitions keep exactly one of deadline, paused and ringing`() = runBlocking {
        TimerStore.setTimer(context, 5_000L, 60_000L)
        assertEquals(TimerState(5_000L, 60_000L, 0L, 0L, TimerStore.state(context).lastTickMillis),
            TimerStore.state(context))

        TimerStore.setPaused(context, 30_000L)
        TimerStore.state(context).let {
            assertEquals(0L, it.endTimeMillis)
            assertEquals(30_000L, it.pausedRemainingMillis)
            assertEquals(60_000L, it.totalMillis)
            assertTrue(it.isPaused)
        }

        TimerStore.setRinging(context, 9_000L)
        TimerStore.state(context).let {
            assertEquals(0L, it.pausedRemainingMillis)
            assertEquals(9_000L, it.ringingSinceMillis)
            assertEquals(60_000L, it.totalMillis) // the alarm screen's "1:00 timer"
            assertTrue(it.isRinging)
        }

        TimerStore.touch(context, 12_345L)
        assertEquals(12_345L, TimerStore.stateFlow(context).first().lastTickMillis)

        TimerStore.clearTimer(context)
        TimerStore.state(context).let {
            assertFalse(it.isRinging || it.isPaused || it.isRunning(0L))
            assertEquals(0L, it.totalMillis)
        }
    }
}
