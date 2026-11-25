package com.merrimentlabs.ducky

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.drawable.Icon
import android.os.Build
import androidx.compose.ui.graphics.toArgb
import androidx.core.app.NotificationCompat
import com.merrimentlabs.ducky.ui.AppStyle
import com.merrimentlabs.ducky.ui.Palette

/**
 * The ongoing countdown and paused cards, drawn in the app's theme. [TimerController] decides
 * when a card is posted or removed; this object only builds and posts them. On Android 16 and
 * newer the cards are built with the platform builder and ask to be promoted to a Live Update.
 */
internal object TimerNotifications {

    private const val COUNTDOWN_NOTIFICATION_ID = 1

    /**
     * Everything a notification can carry of the app theme, resolved once for the device's
     * current day/night mode. (Promoted notifications must NOT be colorized: a full-background
     * tint disqualifies Live Updates promotion. So accent + colored progress segments is the
     * sanctioned styling surface.)
     */
    internal data class NotifTheme(
        val accent: Int,      // scheme.primary for the current day/night mode
        val onAccent: Int,    // text/icons placed ON accent
        val onSurface: Int,   // body text for custom views
    )

    internal suspend fun notifTheme(context: Context): NotifTheme {
        val settings = TimerStore.settings(context)
        val style = AppStyle.fromKey(settings.styleKey)
        val night = (context.resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val scheme =
            if (night) Palette.dark(settings.seedColorArgb, style.monochrome, settings.highContrast)
            else Palette.light(settings.seedColorArgb, style.monochrome, settings.highContrast)
        // No largeIcon: Android 17 already paints the (duck) app icon in the
        // leading circle: a second duck bubble was pure duplication.
        return NotifTheme(
            accent = scheme.primary.toArgb(),
            onAccent = scheme.onPrimary.toArgb(),
            onSurface = scheme.onSurface.toArgb(),
        )
    }

    /**
     * Live-updating countdown: chronometer text + status-bar chip count down in
     * the SYSTEM's process (no re-posting), so this is still zero-polling.
     * On Android 16+ the promoted-ongoing request gives it the Live Updates
     * treatment: status-bar chip, AOD, and lock-screen prominence.
     */
    suspend fun postCountdown(
        context: Context,
        endTimeMillis: Long,
        totalMillis: Long,
    ) {
        val elapsed = (totalMillis - (endTimeMillis - System.currentTimeMillis()))
            .coerceIn(0L, totalMillis)
        val theme = notifTheme(context)
        val subText = context.getString(
            R.string.notification_total, TimerMath.formatClock(totalMillis)
        )
        val totalInt = totalMillis.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val elapsedInt = elapsed.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        // API 36+: build with the PLATFORM builder. The compat ProgressStyle
        // dropped setColor/segment colors in conversion (observed on device:
        // default blue bar, no accent): going native bypasses the mapping.
        val notification: Notification = if (Build.VERSION.SDK_INT >= 36) {
            Notification.Builder(context, TimerController.COUNTDOWN_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification_timer)
                .setContentTitle(context.getString(R.string.notification_running))
                .setWhen(endTimeMillis)
                .setUsesChronometer(true)
                .setChronometerCountDown(true)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(openAppPendingIntent(context))
                .setCategory(Notification.CATEGORY_PROGRESS)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setColor(theme.accent)
                .setSubText(subText)
                .setStyle(
                    Notification.ProgressStyle()
                        .setStyledByProgress(true)
                        // Alternating two-tone segments: a repeating water
                        // pattern (the closest a system-rendered bar gets to
                        // waves). Ducky rides on top as the tracker icon.
                        .setProgressSegments(waveSegments(totalInt, theme.accent))
                        .setProgress(elapsedInt)
                        .setProgressTrackerIcon(
                            // Ducky floats along the accent waterline; onSurface
                            // tint so it doesn't dissolve into the accent bar.
                            Icon.createWithResource(context, R.drawable.ic_duck)
                                .setTint(theme.onSurface)
                        )
                )
                // Platform has no public setter/constant; the compat
                // setRequestPromotedOngoing writes exactly this extra (verified
                // by decompiling core 1.17.0).
                .addExtras(
                    android.os.Bundle().apply {
                        putBoolean("android.requestPromotedOngoing", true)
                    }
                )
                .addAction(action(context, R.string.action_pause, RequestCodes.PAUSE, TimerController.ACTION_PAUSE))
                .addAction(action(context, R.string.action_cancel, RequestCodes.CANCEL, TimerController.ACTION_CANCEL))
                .build()
        } else {
            NotificationCompat.Builder(context, TimerController.COUNTDOWN_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification_timer)
                .setContentTitle(context.getString(R.string.notification_running))
                .setWhen(endTimeMillis)
                .setUsesChronometer(true)
                .setChronometerCountDown(true)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(openAppPendingIntent(context))
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setColor(theme.accent)
                .setSubText(subText)
                .addAction(
                    0, context.getString(R.string.action_pause),
                    receiverPendingIntent(context, RequestCodes.PAUSE, TimerController.ACTION_PAUSE)
                )
                .addAction(
                    0, context.getString(R.string.action_cancel),
                    receiverPendingIntent(context, RequestCodes.CANCEL, TimerController.ACTION_CANCEL)
                )
                .build()
        }
        notificationManager(context).notify(COUNTDOWN_NOTIFICATION_ID, notification)
    }

    /**
     * How many segments the water bar has. ProgressStyle has a limit of 10 segments; past it
     * Android merges them into one plain segment (and logs "Progress segments limit (10) is
     * reached"), which would lose the two-tone water. Eight stays clear of the limit, and an
     * even count starts and ends the bar on different tones.
     */
    internal const val WAVE_SEGMENTS = 8

    /** Alternating accent / lighter-accent segments: stylized water. */
    @androidx.annotation.RequiresApi(36)
    private fun waveSegments(
        totalInt: Int,
        accent: Int,
    ): List<Notification.ProgressStyle.Segment> {
        val lighter = lighten(accent, 0.45f)
        val count = WAVE_SEGMENTS
        val segLen = (totalInt / count).coerceAtLeast(1)
        return (0 until count).map { i ->
            val len = if (i == count - 1) totalInt - segLen * (count - 1) else segLen
            Notification.ProgressStyle.Segment(len.coerceAtLeast(1))
                .setColor(if (i % 2 == 0) accent else lighter)
        }
    }

    private fun lighten(argb: Int, fraction: Float): Int {
        fun ch(shift: Int): Int {
            val c = (argb shr shift) and 0xFF
            return (c + ((255 - c) * fraction)).toInt().coerceIn(0, 255)
        }
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    private fun action(
        context: Context,
        labelRes: Int,
        requestCode: Int,
        actionName: String,
    ): Notification.Action =
        Notification.Action.Builder(
            Icon.createWithResource(context, R.drawable.ic_notification_timer),
            context.getString(labelRes),
            receiverPendingIntent(context, requestCode, actionName)
        ).build()

    /** The paused card: the frozen time left, with Resume and Cancel. */
    suspend fun postPaused(context: Context, remainingMillis: Long) {
        val title = context.getString(
            R.string.notification_paused, TimerMath.formatClock(remainingMillis)
        )
        val theme = notifTheme(context)
        val notification: Notification = if (Build.VERSION.SDK_INT >= 36) {
            Notification.Builder(context, TimerController.COUNTDOWN_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification_timer)
                .setContentTitle(title)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(openAppPendingIntent(context))
                .setCategory(Notification.CATEGORY_PROGRESS)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setColor(theme.accent)
                // Keep the Live Updates surface while paused; the chip shows
                // the frozen remaining time instead of a chronometer.
                .setShortCriticalText(TimerMath.formatClock(remainingMillis))
                .addExtras(
                    android.os.Bundle().apply {
                        putBoolean("android.requestPromotedOngoing", true)
                    }
                )
                .addAction(action(context, R.string.action_resume, RequestCodes.RESUME, TimerController.ACTION_RESUME))
                .addAction(action(context, R.string.action_cancel, RequestCodes.CANCEL, TimerController.ACTION_CANCEL))
                .build()
        } else {
            NotificationCompat.Builder(context, TimerController.COUNTDOWN_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification_timer)
                .setContentTitle(title)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(openAppPendingIntent(context))
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setColor(theme.accent)
                .addAction(
                    0, context.getString(R.string.action_resume),
                    receiverPendingIntent(context, RequestCodes.RESUME, TimerController.ACTION_RESUME)
                )
                .addAction(
                    0, context.getString(R.string.action_cancel),
                    receiverPendingIntent(context, RequestCodes.CANCEL, TimerController.ACTION_CANCEL)
                )
                .build()
        }
        notificationManager(context).notify(COUNTDOWN_NOTIFICATION_ID, notification)
    }

    /** Removes the countdown or paused card. */
    fun cancelCountdown(context: Context) {
        notificationManager(context).cancel(COUNTDOWN_NOTIFICATION_ID)
    }

    /** Removes the ringing alarm's card. */
    fun dismissAlarm(context: Context) {
        notificationManager(context).cancel(TimerController.ALARM_NOTIFICATION_ID)
    }

    private fun receiverPendingIntent(context: Context, requestCode: Int, action: String) =
        PendingIntent.getBroadcast(
            context, requestCode,
            Intent(context, TimerReceiver::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun openAppPendingIntent(context: Context) = PendingIntent.getActivity(
        context, RequestCodes.NOTIFICATION_OPEN_APP,
        Intent(context, MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun notificationManager(context: Context) =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
}
