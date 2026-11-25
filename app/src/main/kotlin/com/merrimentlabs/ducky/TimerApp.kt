package com.merrimentlabs.ducky

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

class TimerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // The launcher-icon alias is synced when the user leaves MainActivity, never
        // here: disabling the alias a launch is going through closes that launch.
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        // The first "countdown" channel was IMPORTANCE_LOW, which
        // keeps notifications off the lock screen (and can block Live Updates
        // promotion). Importance can't be raised on an existing channel, so
        // replace it: DEFAULT importance, explicitly silent (the countdown
        // re-posts on every tap: it must never buzz).
        nm.deleteNotificationChannel("countdown")
        nm.createNotificationChannel(
            NotificationChannel(
                TimerController.COUNTDOWN_CHANNEL_ID,
                getString(R.string.channel_countdown),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                setSound(null, null)
                enableVibration(false)
            }
        )
        // Sound/vibration come from AlarmRingService (alarm stream), not the
        // channel: the channel just needs the importance for FSI to launch.
        nm.createNotificationChannel(
            NotificationChannel(
                TimerController.ALARM_CHANNEL_ID,
                getString(R.string.channel_alarm),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                setSound(null, null)
                enableVibration(false)
            }
        )
    }
}
