package com.merrimentlabs.ducky

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.content.edit
import androidx.core.net.toUri

/**
 * Why a set timer might not be able to alert the user. The alarm's sound and
 * vibration come from AlarmRingService and survive all three, but the visible
 * alarm (heads-up card, full-screen AlarmActivity, lock-screen wake) does not,
 * and a kitchen timer that can only be heard from the next room is half a
 * timer. Ordered most to least blocking; [AlarmReadiness.check] reports the
 * first one that applies.
 */
enum class AlarmBlocker {
    /** POST_NOTIFICATIONS denied or the app's notifications toggled off. */
    NOTIFICATIONS_OFF,

    /** The alarm channel exists but the user turned it down below HIGH: no heads-up, no full-screen. */
    ALARM_CHANNEL_QUIET,

    /** The per-app full-screen-intent grant (Android 14+) was revoked. */
    FULL_SCREEN_OFF,
}

object AlarmReadiness {

    /**
     * [canUseFullScreenIntent] is injectable because the platform call goes
     * through the notification service binder, which Robolectric does not
     * shadow; production uses the real one.
     */
    fun check(
        context: Context,
        canUseFullScreenIntent: (NotificationManager) -> Boolean = { it.canUseFullScreenIntent() },
    ): AlarmBlocker? {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (!nm.areNotificationsEnabled()) return AlarmBlocker.NOTIFICATIONS_OFF
        // Null channel = not created yet (first launch before TimerApp ran);
        // nothing to report until it exists.
        val channel = nm.getNotificationChannel(TimerController.ALARM_CHANNEL_ID)
        if (channel != null && channel.importance < NotificationManager.IMPORTANCE_HIGH) {
            return AlarmBlocker.ALARM_CHANNEL_QUIET
        }
        if (!canUseFullScreenIntent(nm)) return AlarmBlocker.FULL_SCREEN_OFF
        return null
    }

    /** The system settings page that fixes [blocker]: one tap, no explaining. */
    fun settingsIntent(context: Context, blocker: AlarmBlocker): Intent = when (blocker) {
        AlarmBlocker.NOTIFICATIONS_OFF ->
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        AlarmBlocker.ALARM_CHANNEL_QUIET ->
            Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                .putExtra(Settings.EXTRA_CHANNEL_ID, TimerController.ALARM_CHANNEL_ID)
        AlarmBlocker.FULL_SCREEN_OFF ->
            Intent(
                Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                "package:${context.packageName}".toUri()
            )
    }
}

/**
 * "Not now" on the full-screen alarm tip: hidden for [QUIET_MILLIS] (30 days), then offered
 * again once. Remembered with the other screen preferences.
 */
object FullScreenTip {
    private const val PREFS = "ducky_ui"
    private const val DISMISSED_AT = "fullscreen_tip_dismissed_at"
    const val QUIET_MILLIS = 30L * 24 * 60 * 60 * 1000

    fun dismissed(context: Context, now: Long = System.currentTimeMillis()): Boolean {
        val at = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(DISMISSED_AT, 0L)
        // A clock set back before the dismissal counts as fresh, never as "hidden forever".
        return at > 0L && now >= at && now - at < QUIET_MILLIS
    }

    fun dismiss(context: Context, now: Long = System.currentTimeMillis()) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putLong(DISMISSED_AT, now) }
    }
}

/**
 * The notification permission is asked at the moment of need (a timer is set), not at launch.
 * Until it has been asked once, a missing permission is not a problem to report: nothing is
 * set that could ring. Remembered in a small preferences file, apart from the timer's state.
 */
object NotificationAsk {
    private const val PREFS = "ducky_ui"
    private const val ASKED = "notifications_asked"
    private const val DEFERRED = "notifications_deferred"

    private fun granted(context: Context) =
        context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * True until the reason has been answered: the permission is missing, Ducky never asked for it,
     * and the person has not said "Not now" to the reason card.
     */
    fun pending(context: Context): Boolean =
        !granted(context) && !prefs(context).getBoolean(ASKED, false) && !prefs(context).getBoolean(DEFERRED, false)

    /**
     * True after "Not now" while Ducky has still never asked: Android would show its dialog, so the
     * banner offers "Allow notifications" (after every resume, until the permission is granted).
     */
    fun deferred(context: Context): Boolean =
        !granted(context) && !prefs(context).getBoolean(ASKED, false) && prefs(context).getBoolean(DEFERRED, false)

    /** Records "Not now" on the reason card. */
    fun defer(context: Context) {
        prefs(context).edit { putBoolean(DEFERRED, true) }
    }

    fun markAsked(context: Context) {
        prefs(context).edit { putBoolean(ASKED, true) }
    }
}
