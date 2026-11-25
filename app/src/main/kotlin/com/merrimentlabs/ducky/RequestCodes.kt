package com.merrimentlabs.ducky

/**
 * Request codes for every PendingIntent Ducky creates, in one place so no two collide.
 *
 * A request code is part of a PendingIntent's identity. An alarm armed by the installed version
 * must still be found and replaced by the next one, so these values never change.
 */
internal object RequestCodes {
    /** The alarm clock's "show" intent: what tapping the lock screen's next-alarm chip opens. */
    const val ALARM_SHOW_APP = 0
    /** The timer's own alarm: the FIRE broadcast. */
    const val FIRE = 1
    const val CANCEL = 2
    /** Tapping a countdown or paused notification opens the app. */
    const val NOTIFICATION_OPEN_APP = 3
    const val PAUSE = 4
    const val RESUME = 5
    /** The display tick that re-renders the widgets while a timer runs. */
    const val TICK = 6
    const val STOP_RING = 7
    const val EXTEND_RING = 8
    /** The ringing notification's full-screen intent, which opens the alarm screen. */
    const val RING_FULL_SCREEN = 10
}
