package com.merrimentlabs.ducky.ui.pond

/**
 * One still of a counted widget picture ("Bubbles", "Egg timer"): [count] items, of which
 * [left] still stand for time to go. Equal frames draw identical pictures, so a widget redraws
 * only when an item changes, never between (widget pictures can change only every 5 s or so:
 * AlarmManager's floor for the display tick).
 *
 * Remaining time is split into [count] equal slices. An item goes at the end of its slice, so
 * the last one goes when the timer rings: [left] counts the slices not yet finished. Short
 * timers get fewer items ([itemsFor]), so no slice is shorter than the widget's update step.
 */
data class StepFrame(
    val mood: HopMood,
    val count: Int,
    val left: Int,
    /** Under a minute to go while running: the picture gets ready for the ring. */
    val lastMinute: Boolean,
) {
    /** Items already gone (popped, hatched). */
    val gone: Int get() = count - left

    companion object {
        /** The last-minute cue shows at or under this much time left. */
        const val LAST_MINUTE_MS = 60_000L

        /**
         * The shortest slice a widget can show item by item. The display tick fires at most
         * every 5 s and a render can land up to about 0.8 s late (measured on a Pixel phone), so two
         * renders can be 5.8 s apart: a shorter slice would let two items go at once.
         */
        const val MIN_SLICE_MS = 7_000L

        /**
         * How many items a timer of [totalMillis] gets in a box that fits [widthCount]: as many
         * as fit, but never so many that a slice is shorter than [MIN_SLICE_MS]. A 30 s timer
         * gets 4, a minute or longer gets the full row, a timer under 14 s gets one. With no
         * timer (idle, Test alarm) the row is full.
         */
        fun itemsFor(widthCount: Int, totalMillis: Long): Int =
            if (totalMillis <= 0L) widthCount
            else minOf(widthCount.toLong(), (totalMillis / MIN_SLICE_MS).coerceAtLeast(1L)).toInt()

        /** How long after its deadline a timer that has not rung yet still counts as finishing. */
        const val RING_PENDING_MS = 60_000L

        /**
         * The deadline has just passed and the ring has not been recorded yet (FIRE is a moment
         * away, or a render queued before it lands now). The picture keeps its last item rather
         * than refilling as if no timer were set. Long-passed deadlines (a force-stopped app)
         * are not "finishing".
         */
        fun ringPending(endTimeMillis: Long, nowMillis: Long, paused: Boolean, ringing: Boolean): Boolean =
            !paused && !ringing && endTimeMillis in (nowMillis - RING_PENDING_MS)..nowMillis

        fun of(mood: HopMood, remainingMillis: Long, totalMillis: Long, count: Int): StepFrame {
            val n = count.coerceAtLeast(1)
            val left = when (mood) {
                HopMood.IDLE -> n
                HopMood.DONE -> 0
                HopMood.RUNNING, HopMood.PAUSED -> if (totalMillis <= 0L) 1 else {
                    // Integer ceiling of remaining / total * n: exact at every slice edge.
                    val r = remainingMillis.coerceIn(0L, totalMillis)
                    ((r * n + totalMillis - 1) / totalMillis).toInt().coerceIn(1, n)
                }
            }
            val lastMinute = mood == HopMood.RUNNING && remainingMillis <= LAST_MINUTE_MS
            return StepFrame(mood, n, left, lastMinute)
        }
    }
}
