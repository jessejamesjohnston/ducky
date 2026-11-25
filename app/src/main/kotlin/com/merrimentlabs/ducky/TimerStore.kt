package com.merrimentlabs.ducky

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

val Context.timerDataStore by preferencesDataStore(name = "timer")

/**
 * DataStore is the source of truth for DISPLAY (end time, settings).
 * The AlarmManager alarm is the source of truth for FIRING.
 * Exactly one of endTimeMillis / pausedRemainingMillis is nonzero at a time
 * (paused means: no alarm scheduled, remaining frozen).
 */
data class TimerState(
    val endTimeMillis: Long,        // 0 = no timer counting down
    val totalMillis: Long,          // accumulated length of the current/last timer
    val ringingSinceMillis: Long,   // 0 = not ringing
    val pausedRemainingMillis: Long, // 0 = not paused
    // Display heartbeat: bumped by the water-level tick so LIVE widget
    // sessions recompose (their flows dedupe otherwise). No timer semantics.
    val lastTickMillis: Long = 0L,
) {
    fun isRunning(now: Long): Boolean = endTimeMillis > now
    val isRinging: Boolean get() = ringingSinceMillis > 0
    val isPaused: Boolean get() = pausedRemainingMillis > 0
}

data class TimerSettings(
    val soundUri: String?,        // null = system default alarm sound
    val vibrate: Boolean,
    val incrementsSeconds: List<Int>,
    val styleKey: String,         // AppStyle key, see ui/TimerTheme.kt
    val seedColorArgb: Int,       // accent seed the color schemes derive from
    val highContrast: Boolean,    // accessibility: stronger tonal separation
)

object TimerStore {
    private val END_TIME = longPreferencesKey("end_time_millis")
    private val TOTAL = longPreferencesKey("total_millis")
    private val RINGING_SINCE = longPreferencesKey("ringing_since_millis")
    private val PAUSED_REMAINING = longPreferencesKey("paused_remaining_millis")
    private val LAST_TICK = longPreferencesKey("last_tick_millis")
    private val SOUND_URI = stringPreferencesKey("sound_uri")
    private val VIBRATE = booleanPreferencesKey("vibrate")
    private val INC_1 = intPreferencesKey("increment_1")
    private val INC_2 = intPreferencesKey("increment_2")
    private val INC_3 = intPreferencesKey("increment_3")
    private val STYLE = stringPreferencesKey("style")
    private val SEED_COLOR = intPreferencesKey("seed_color")
    private val HIGH_CONTRAST = booleanPreferencesKey("high_contrast")

    val DEFAULT_INCREMENTS = listOf(30, 60, 300)
    const val DEFAULT_STYLE = "mountain_view"
    const val DEFAULT_SEED = 0xFF1E88E5.toInt() // water blue: Ducky's element

    internal fun stateOf(p: Preferences) = TimerState(
        endTimeMillis = p[END_TIME] ?: 0L,
        totalMillis = p[TOTAL] ?: 0L,
        ringingSinceMillis = p[RINGING_SINCE] ?: 0L,
        pausedRemainingMillis = p[PAUSED_REMAINING] ?: 0L,
        lastTickMillis = p[LAST_TICK] ?: 0L,
    )

    internal fun settingsOf(p: Preferences) = TimerSettings(
        soundUri = p[SOUND_URI],
        vibrate = p[VIBRATE] ?: true,
        incrementsSeconds = listOf(
            p[INC_1] ?: DEFAULT_INCREMENTS[0],
            p[INC_2] ?: DEFAULT_INCREMENTS[1],
            p[INC_3] ?: DEFAULT_INCREMENTS[2],
        ),
        styleKey = p[STYLE] ?: DEFAULT_STYLE,
        seedColorArgb = p[SEED_COLOR] ?: DEFAULT_SEED,
        highContrast = p[HIGH_CONTRAST] ?: false,
    )

    fun stateFlow(context: Context): Flow<TimerState> =
        context.timerDataStore.data.map(::stateOf)

    fun settingsFlow(context: Context): Flow<TimerSettings> =
        context.timerDataStore.data.map(::settingsOf)

    suspend fun state(context: Context): TimerState =
        stateOf(context.timerDataStore.data.first())

    suspend fun settings(context: Context): TimerSettings =
        settingsOf(context.timerDataStore.data.first())

    suspend fun setTimer(context: Context, endTimeMillis: Long, totalMillis: Long) {
        context.timerDataStore.edit {
            it[END_TIME] = endTimeMillis
            it[TOTAL] = totalMillis
            it[RINGING_SINCE] = 0L
            it[PAUSED_REMAINING] = 0L
        }
    }

    suspend fun setPaused(context: Context, remainingMillis: Long) {
        context.timerDataStore.edit {
            it[END_TIME] = 0L
            it[RINGING_SINCE] = 0L
            it[PAUSED_REMAINING] = remainingMillis
        }
    }

    suspend fun clearTimer(context: Context) {
        context.timerDataStore.edit {
            it[END_TIME] = 0L
            it[TOTAL] = 0L
            it[RINGING_SINCE] = 0L
            it[PAUSED_REMAINING] = 0L
        }
    }

    /** Timer expired: keep totalMillis for the alarm screen's "12:00 timer" line. */
    suspend fun setRinging(context: Context, sinceMillis: Long) {
        context.timerDataStore.edit {
            it[END_TIME] = 0L
            it[RINGING_SINCE] = sinceMillis
            it[PAUSED_REMAINING] = 0L
        }
    }

    /** Display heartbeat for the widget water level; no timer semantics. */
    suspend fun touch(context: Context, nowMillis: Long) {
        context.timerDataStore.edit { it[LAST_TICK] = nowMillis }
    }

    suspend fun setSoundUri(context: Context, uri: String?) {
        context.timerDataStore.edit {
            if (uri == null) it.remove(SOUND_URI) else it[SOUND_URI] = uri
        }
    }

    suspend fun setVibrate(context: Context, vibrate: Boolean) {
        context.timerDataStore.edit { it[VIBRATE] = vibrate }
    }

    suspend fun setIncrement(context: Context, index: Int, seconds: Int) {
        val key = listOf(INC_1, INC_2, INC_3)[index]
        context.timerDataStore.edit { it[key] = seconds }
    }

    /** Stepper values for the three time buttons (Settings > Time buttons). */
    val INCREMENT_CHOICES = listOf(5, 10, 15, 30, 45, 60, 90, 120, 180, 300, 600, 900, 1200, 1800)

    /**
     * The stepper slot [seconds] sits in: its own, or for a value that is not a choice the
     * next larger choice (past the last, the last).
     */
    fun incrementSlot(seconds: Int): Int = INCREMENT_CHOICES.indexOf(seconds).let {
        if (it >= 0) it else INCREMENT_CHOICES.indexOfFirst { c -> c > seconds }
            .let { i -> if (i >= 0) i else INCREMENT_CHOICES.lastIndex }
    }

    /**
     * One stepper tap on time button [index]: the next choice up or down from the STORED value,
     * read inside the write, so taps that land before the screen has caught up each count.
     */
    suspend fun stepIncrement(context: Context, index: Int, up: Boolean) {
        val key = listOf(INC_1, INC_2, INC_3)[index]
        context.timerDataStore.edit {
            val slot = incrementSlot(it[key] ?: DEFAULT_INCREMENTS[index])
            val next = if (up) slot + 1 else slot - 1
            if (next in INCREMENT_CHOICES.indices) it[key] = INCREMENT_CHOICES[next]
        }
    }

    suspend fun setStyle(context: Context, styleKey: String) {
        context.timerDataStore.edit { it[STYLE] = styleKey }
    }

    suspend fun setSeedColor(context: Context, argb: Int) {
        context.timerDataStore.edit { it[SEED_COLOR] = argb }
    }

    suspend fun setHighContrast(context: Context, enabled: Boolean) {
        context.timerDataStore.edit { it[HIGH_CONTRAST] = enabled }
    }
}
