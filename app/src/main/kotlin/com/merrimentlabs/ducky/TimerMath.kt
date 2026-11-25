package com.merrimentlabs.ducky

import android.content.Context

/**
 * Pure timer arithmetic. Additive kitchen-timer semantics:
 * a tap while idle starts a timer of that length; a tap while running
 * extends the running timer. The AlarmManager alarm is the source of truth
 * for firing; these functions only compute what to schedule/display.
 */
object TimerMath {

    /** New absolute end time after adding [addMillis] at wall-clock [nowMillis]. */
    fun newEndTime(nowMillis: Long, currentEndMillis: Long, addMillis: Long): Long =
        if (currentEndMillis > nowMillis) currentEndMillis + addMillis
        else nowMillis + addMillis

    /** "2:30", "12:00", "1:02:05": for the widget/app countdown. */
    fun formatClock(millis: Long): String {
        val totalSec = (millis.coerceAtLeast(0) + 999) / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    /** "30s", "1m", "5m", "1m 30s": for increment button labels. */
    fun formatIncrement(context: Context, seconds: Int): String {
        val m = seconds / 60
        val s = seconds % 60
        return when {
            m == 0 -> context.getString(R.string.increment_seconds, s)
            s == 0 -> context.getString(R.string.increment_minutes, m)
            else -> context.getString(R.string.increment_minutes_seconds, m, s)
        }
    }

    /** "+30s": the label on a button that adds [seconds]. */
    fun formatAddIncrement(context: Context, seconds: Int): String =
        context.getString(R.string.increment_add, formatIncrement(context, seconds))

    /** "30 seconds", "1 minute 30 seconds": localized, for screen readers. */
    fun speakDuration(context: Context, totalSeconds: Long): String {
        if (totalSeconds <= 0) return context.getString(R.string.a11y_zero_seconds)
        val res = context.resources
        val h = (totalSeconds / 3600).toInt()
        val m = ((totalSeconds % 3600) / 60).toInt()
        val s = (totalSeconds % 60).toInt()
        val parts = mutableListOf<String>()
        if (h > 0) parts += res.getQuantityString(R.plurals.duration_hours, h, h)
        if (m > 0) parts += res.getQuantityString(R.plurals.duration_minutes, m, m)
        if (s > 0) parts += res.getQuantityString(R.plurals.duration_seconds, s, s)
        return parts.joinToString(" ")
    }

    /**
     * Whole minutes to speak for a running widget, or null for "under a minute". A widget's
     * description is written when it renders and read whenever TalkBack gets to it, up to one
     * display tick (15 s on a long timer) later, so it speaks no finer than a minute, rounded to
     * the nearest one and said with "about".
     */
    fun approxMinutesLeft(millis: Long): Long? =
        if (millis < 60_000L) null else (millis + 30_000L) / 60_000L

    /** "about 2 minutes", "about 1 hour 5 minutes", or "under a minute", for a running widget. */
    fun speakApproxLeft(context: Context, millis: Long): String =
        approxMinutesLeft(millis)?.let { context.getString(R.string.a11y_about, speakDuration(context, it * 60)) }
            ?: context.getString(R.string.a11y_under_a_minute)

    /** Spoken remaining time from millis (rounds up like formatClock). */
    fun speakClock(context: Context, millis: Long): String =
        speakDuration(context, (millis.coerceAtLeast(0) + 999) / 1000)
}
