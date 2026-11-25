package com.merrimentlabs.ducky

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AlarmReadinessTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val nm = context.getSystemService(NotificationManager::class.java)

    private fun alarmChannel(importance: Int) {
        // Robolectric lets a test re-create a channel; the platform would keep
        // the old importance, which is exactly the user-lowered state we model.
        nm.deleteNotificationChannel(TimerController.ALARM_CHANNEL_ID)
        nm.createNotificationChannel(
            NotificationChannel(TimerController.ALARM_CHANNEL_ID, "alarm", importance)
        )
    }

    @Before
    fun healthy() {
        shadowOf(nm).setNotificationsEnabled(true)
        alarmChannel(NotificationManager.IMPORTANCE_HIGH)
    }

    @Test
    fun `nothing blocks a healthy setup`() {
        assertNull(AlarmReadiness.check(context) { true })
    }

    @Test
    fun `notifications off outranks everything`() {
        shadowOf(nm).setNotificationsEnabled(false)
        alarmChannel(NotificationManager.IMPORTANCE_NONE)
        assertEquals(AlarmBlocker.NOTIFICATIONS_OFF, AlarmReadiness.check(context) { false })
    }

    @Test
    fun `an alarm channel below HIGH is reported, blocked or merely lowered`() {
        alarmChannel(NotificationManager.IMPORTANCE_NONE)
        assertEquals(AlarmBlocker.ALARM_CHANNEL_QUIET, AlarmReadiness.check(context) { true })
        alarmChannel(NotificationManager.IMPORTANCE_DEFAULT)
        assertEquals(AlarmBlocker.ALARM_CHANNEL_QUIET, AlarmReadiness.check(context) { true })
    }

    @Test
    fun `a missing channel is not a blocker`() {
        nm.deleteNotificationChannel(TimerController.ALARM_CHANNEL_ID)
        assertNull(AlarmReadiness.check(context) { true })
    }

    @Test
    fun `revoked full-screen intent is the last check`() {
        assertEquals(AlarmBlocker.FULL_SCREEN_OFF, AlarmReadiness.check(context) { false })
    }

    @Test
    fun `each blocker opens the settings page that fixes it`() {
        val pkg = context.packageName
        AlarmReadiness.settingsIntent(context, AlarmBlocker.NOTIFICATIONS_OFF).let {
            assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, it.action)
            assertEquals(pkg, it.getStringExtra(Settings.EXTRA_APP_PACKAGE))
        }
        AlarmReadiness.settingsIntent(context, AlarmBlocker.ALARM_CHANNEL_QUIET).let {
            assertEquals(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS, it.action)
            assertEquals(TimerController.ALARM_CHANNEL_ID, it.getStringExtra(Settings.EXTRA_CHANNEL_ID))
        }
        AlarmReadiness.settingsIntent(context, AlarmBlocker.FULL_SCREEN_OFF).let {
            assertEquals(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, it.action)
            assertEquals("package:$pkg", it.dataString)
        }
    }

    @Test
    fun `not now hides the full-screen tip for 30 days, then it comes back`() {
        val t0 = 1_800_000_000_000L
        assertFalse("never dismissed", FullScreenTip.dismissed(context, t0))
        FullScreenTip.dismiss(context, t0)
        assertTrue(FullScreenTip.dismissed(context, t0))
        assertTrue("a day short", FullScreenTip.dismissed(context, t0 + FullScreenTip.QUIET_MILLIS - 86_400_000L))
        assertFalse("30 days on", FullScreenTip.dismissed(context, t0 + FullScreenTip.QUIET_MILLIS))
        assertFalse("a clock set back is not hidden forever", FullScreenTip.dismissed(context, t0 - 1_000L))
    }
}
