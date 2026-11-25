package com.merrimentlabs.ducky

import android.Manifest
import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Looper
import android.provider.Settings
import android.view.KeyEvent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.merrimentlabs.ducky.ui.AppStyle
import com.merrimentlabs.ducky.ui.PRESET_SEEDS
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowActivity
import org.robolectric.shadows.ShadowAppTask

/**
 * The timer screen as a person uses it: quick-add, pause, cancel, the ringing actions, the
 * alarm-blocked banner and the settings pages it opens, the notification ask, Ctrl+comma, and
 * the launcher-icon swap on leaving. State is persisted BEFORE launch (empty rule), since the
 * screen's effects key off the first state it reads.
 */
@RunWith(RobolectricTestRunner::class)
// A phone-sized screen: Robolectric's default 320x470 dp puts the controls off the bottom.
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
class MainActivityTest {
    @get:Rule val rule = createEmptyComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val nm = context.getSystemService(NotificationManager::class.java)
    private val realWidgetRefresher = TimerController.widgetRefresher
    private var scenario: ActivityScenario<MainActivity>? = null
    private var activity: MainActivity? = null
    private val controllerMutex =
        TimerController::class.java.getDeclaredField("mutex").apply { isAccessible = true }
            .get(null) as kotlinx.coroutines.sync.Mutex

    @Before
    fun reset() {
        TimerController.widgetRefresher = {}
        resetStore()
    }

    @After
    fun restore() {
        scenario?.close()
        shadowOf(context as android.app.Application).checkActivities(false)
        // A request* command may still be running under the controller's lock: let it finish.
        runBlocking { controllerMutex.lock(); controllerMutex.unlock() }
        TimerController.resetWidgetDelivery()
        TimerController.widgetRefresher = realWidgetRefresher
        resetStore()
    }

    private fun resetStore() = runBlocking {
        TimerStore.clearTimer(context)
        TimerStore.setStyle(context, TimerStore.DEFAULT_STYLE)
        TimerStore.setSeedColor(context, TimerStore.DEFAULT_SEED)
    }

    private fun state() = runBlocking { TimerStore.state(context) }

    private fun launch(): MainActivity {
        rule.manualClock()
        scenario = ActivityScenario.launch(MainActivity::class.java).also { sc ->
            sc.onActivity { activity = it }
        }
        return activity!!
    }

    private fun settle() = repeat(3) {
        rule.pump(1)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun awaitTrue(done: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!done()) {
            if (System.currentTimeMillis() > deadline) fail("condition not reached in 5 s")
            rule.pump(1)
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    private fun alarmChannel(importance: Int) {
        nm.deleteNotificationChannel(TimerController.ALARM_CHANNEL_ID)
        nm.createNotificationChannel(NotificationChannel(TimerController.ALARM_CHANNEL_ID, "alarm", importance))
    }

    private fun grantNotifications() =
        shadowOf(context as android.app.Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

    /**
     * Delivers the system's answer to a permission request the way ComponentActivity does: as an
     * activity result carrying the permissions and their grant results.
     */
    private fun answerPermissionRequest(request: ShadowActivity.PermissionsRequest, result: Int) {
        val data = Intent()
            .putExtra(RequestMultiplePermissions.EXTRA_PERMISSIONS, request.requestedPermissions)
            .putExtra(RequestMultiplePermissions.EXTRA_PERMISSION_GRANT_RESULTS, intArrayOf(result))
        activity!!.activityResultRegistry.dispatchResult(request.requestCode, android.app.Activity.RESULT_OK, data)
    }

    // ---- Launch ----

    @Test
    fun `opening the app re-arms a running timer whose alarm is gone`() {
        // As Android 14 leaves it after a force-stop: the deadline is saved, the alarm is not.
        val end = System.currentTimeMillis() + 10 * 60_000L
        runBlocking { TimerStore.setTimer(context, end, 10 * 60_000L) }
        val alarms = shadowOf(context.getSystemService(android.app.AlarmManager::class.java))
        assertTrue(alarms.scheduledAlarms.isEmpty())
        launch()
        awaitTrue { alarms.scheduledAlarms.any { it.triggerAtMs == end } }
    }

    // ---- Countdown controls ----

    @Test
    fun `a quick-add tile starts a timer and the readout counts it`() {
        grantNotifications()
        launch()
        rule.awaitNode { onNodeWithText("Add time to start") }
        rule.onNodeWithContentDescription("Cancel timer").assertIsNotEnabled()

        rule.awaitNode { onNodeWithContentDescription("Add 30 seconds") }.performClick()
        awaitTrue { state().totalMillis == 30_000L }
        assertTrue(state().isRunning(System.currentTimeMillis()))
        rule.awaitNode { onNodeWithText("Tap to pause") }
        rule.awaitNode { onNodeWithContentDescription("seconds remaining", substring = true) }
        rule.onNodeWithContentDescription("Cancel timer").assertIsEnabled()
    }

    @Test
    fun `tapping the pond pauses and resumes, and Cancel clears`() {
        grantNotifications()
        runBlocking { TimerController.addTime(context, 300_000L) }
        launch()
        rule.awaitNode { onNodeWithText("Tap to pause") }.performClick()
        awaitTrue { state().isPaused }
        rule.awaitNode { onNodeWithText("Paused · tap to resume") }
        rule.awaitNode { onNodeWithContentDescription("Timer paused, ", substring = true) }

        rule.onNodeWithText("Paused · tap to resume").performClick()
        awaitTrue { state().isRunning(System.currentTimeMillis()) }
        rule.awaitNode { onNodeWithText("Tap to pause") }

        rule.onNodeWithContentDescription("Cancel timer").performClick()
        awaitTrue { state().totalMillis == 0L }
        rule.awaitNode { onNodeWithText("Add time to start") }
    }

    @Test
    fun `the pond takes keyboard focus while a timer runs`() {
        grantNotifications()
        runBlocking { TimerController.addTime(context, 300_000L) }
        launch()
        val pond = rule.awaitNode { onNodeWithText("Tap to pause") }
        pond.performSemanticsAction(SemanticsActions.RequestFocus)
        settle()
        rule.onNodeWithText("Tap to pause").assertIsFocused()
    }

    // ---- Ringing ----

    @Test
    fun `a ring in the app offers Stop, and Stop ends this ring`() {
        grantNotifications()
        val session = ringing()
        launch()
        rule.awaitNode { onNodeWithText("Ringing…") }
        rule.awaitNode { onNodeWithContentDescription("Timer ringing") }
        rule.onNodeWithContentDescription("Add 30 seconds").assertDoesNotExist()
        rule.awaitNode { onNodeWithContentDescription("Stop alarm") }.performClick()
        awaitTrue { !state().isRinging }
        assertNotEquals(session, state().ringingSinceMillis)
        rule.awaitNode { onNodeWithText("Add time to start") }
    }

    @Test
    fun `+1 min in the app turns the ring into a fresh minute`() {
        grantNotifications()
        ringing()
        launch()
        rule.awaitNode { onNodeWithContentDescription("Add one more minute") }.performClick()
        awaitTrue { state().isRunning(System.currentTimeMillis()) }
        assertEquals(TimerController.EXTEND_MILLIS, state().totalMillis)
        rule.awaitNode { onNodeWithText("Tap to pause") }
    }

    private fun ringing(): Long = runBlocking {
        TimerStore.setTimer(context, System.currentTimeMillis() - 5_000L, 60_000L)
        val session = System.currentTimeMillis() - 5_000L
        TimerStore.setRinging(context, session)
        session
    }

    // ---- Alarm-blocked banner ----

    @Test
    fun `the full-screen banner opens the full-screen-intent page for this app`() {
        // Robolectric has no shadow for canUseFullScreenIntent: the binder stub answers false.
        grantNotifications()
        launch()
        rule.awaitNode { onNodeWithText("Let the alarm fill the screen when time's up, even on the lock screen.") }
            .performClick()
        val started = shadowOf(activity!!).nextStartedActivity
        assertEquals(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, started.action)
        assertEquals("package:${context.packageName}", started.dataString)
    }

    @Test
    fun `a quiet alarm channel banner opens that channel's settings`() {
        grantNotifications()
        alarmChannel(NotificationManager.IMPORTANCE_DEFAULT)
        launch()
        rule.awaitNode {
            onNodeWithText("Timer alarms are set not to pop up, so the alarm can't show.")
        }.performClick()
        val started = shadowOf(activity!!).nextStartedActivity
        assertEquals(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS, started.action)
        assertEquals(TimerController.ALARM_CHANNEL_ID, started.getStringExtra(Settings.EXTRA_CHANNEL_ID))
    }

    @Test
    fun `a missing settings page falls back to the app's notification settings`() {
        grantNotifications()
        // Only the app notification page resolves; the full-screen page does not exist here.
        val app = context as android.app.Application
        shadowOf(app).checkActivities(true)
        val settingsApp = ComponentName("com.android.settings", "com.android.settings.AppNotifications")
        shadowOf(context.packageManager).addActivityIfNotPresent(settingsApp)
        shadowOf(context.packageManager).addIntentFilterForActivity(
            settingsApp,
            IntentFilter(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply { addCategory(Intent.CATEGORY_DEFAULT) },
        )
        launch()
        rule.awaitNode { onNodeWithText("Let the alarm fill the screen when time's up, even on the lock screen.") }
            .performClick()
        val started = shadowOf(activity!!).nextStartedActivity
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, started.action)
        assertEquals(context.packageName, started.getStringExtra(Settings.EXTRA_APP_PACKAGE))
    }

    @Test
    fun `not now hides the full-screen tip, and it stays hidden`() {
        grantNotifications()
        launch()
        rule.awaitNode { onNodeWithText("Not now") }.performClick()
        awaitTrue { FullScreenTip.dismissed(context) }
        rule.pump()
        rule.onNodeWithText("Let the alarm fill the screen when time's up, even on the lock screen.").assertDoesNotExist()
        assertNull("not now opened nothing", shadowOf(activity!!).nextStartedActivity)
    }

    @Test
    fun `the notifications banner has no not-now`() {
        shadowOf(nm).setNotificationsEnabled(false)
        NotificationAsk.markAsked(context)
        launch()
        rule.awaitNode { onNodeWithText("Notifications are off, so the alarm can't show when time's up.") }
        rule.onNodeWithText("Not now").assertDoesNotExist()
    }

    @Test
    fun `after one no, the banner asks with the system dialog again instead of opening Settings`() {
        shadowOf(nm).setNotificationsEnabled(false)
        NotificationAsk.markAsked(context)
        launch()
        shadowOf(context.packageManager).setShouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS, true)
        // A resume re-reads whether Android would still show its dialog.
        scenario!!.moveToState(Lifecycle.State.STARTED)
        scenario!!.moveToState(Lifecycle.State.RESUMED)
        rule.awaitNode { onNodeWithText("Allow notifications so the alarm can show when time's up.") }
        rule.awaitNode { onNodeWithText("Allow") }.performClick()
        val request = shadowOf(activity!!).lastRequestedPermission
        assertEquals(listOf(Manifest.permission.POST_NOTIFICATIONS), request.requestedPermissions.toList())
        val next = shadowOf(activity!!).nextStartedActivity
        assertFalse("no Settings page: $next", next?.action.orEmpty().startsWith("android.settings"))
    }

    // ---- Notification ask (at the moment of need, never at launch) ----

    @Test
    fun `notifications are asked when a timer is set, with the reason on the banner, then the answer shows`() {
        shadowOf(nm).setNotificationsEnabled(false)
        launch()
        rule.awaitNode { onNodeWithText("Add time to start") }
        // Nothing set yet: no ask, and no complaint about notifications.
        assertNull(shadowOf(activity!!).lastRequestedPermission)
        rule.onNodeWithText("Notifications are off, so the alarm can't show when time's up.").assertDoesNotExist()

        rule.awaitNode { onNodeWithContentDescription("Add 30 seconds") }.performClick()
        rule.awaitNode {
            onNodeWithText("Ducky rings through a notification. Allow notifications so the alarm can show.")
        }
        val request = shadowOf(activity!!).lastRequestedPermission
        assertEquals(listOf(Manifest.permission.POST_NOTIFICATIONS), request.requestedPermissions.toList())
        assertFalse(NotificationAsk.pending(context)) // remembered before the dialog shows

        // Denied: the banner moves on from the reason to what is true now.
        answerPermissionRequest(request, PackageManager.PERMISSION_DENIED)
        rule.awaitNode { onNodeWithText("Notifications are off, so the alarm can't show when time's up.") }
            .performClick()
        val started = shadowOf(activity!!).nextStartedActivity
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, started.action)
    }

    @Test
    fun `allowing notifications during a ring asks the ring service to repost its card`() {
        shadowOf(nm).setNotificationsEnabled(false)
        ringing()
        AlarmRingService.running = true
        try {
            launch()
            awaitTrue { shadowOf(activity!!).lastRequestedPermission != null }
            val request = shadowOf(activity!!).lastRequestedPermission
            grantNotifications()
            answerPermissionRequest(request, PackageManager.PERMISSION_GRANTED)
            val started = generateSequence { shadowOf(context as android.app.Application).nextStartedService }
                .firstOrNull { it.action == AlarmRingService.ACTION_REPOST }
            assertNotNull("repost requested", started)
        } finally {
            AlarmRingService.running = false
        }
    }

    @Test
    fun `a timer already running when the app opens triggers the ask too`() {
        runBlocking { TimerController.addTime(context, 300_000L) }
        launch()
        awaitTrue { shadowOf(activity!!).lastRequestedPermission != null }
        assertEquals(
            Manifest.permission.POST_NOTIFICATIONS,
            shadowOf(activity!!).lastRequestedPermission.requestedPermissions.single(),
        )
    }

    // ---- Navigation ----

    @Test
    fun `Ctrl+comma opens Settings, other chords do not`() {
        grantNotifications()
        val a = launch()
        rule.awaitNode { onNodeWithText("Add time to start") }
        val shifted = KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_COMMA, 0,
            KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON)
        assertFalse(a.onKeyShortcut(KeyEvent.KEYCODE_COMMA, shifted))
        val bareComma = KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_COMMA, 0, 0)
        assertFalse(a.onKeyShortcut(KeyEvent.KEYCODE_COMMA, bareComma))
        val plainS = KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_S, 0, KeyEvent.META_CTRL_ON)
        assertFalse(a.onKeyShortcut(KeyEvent.KEYCODE_S, plainS))
        settle()
        rule.onNodeWithText("Alarm sound").assertDoesNotExist()

        val ctrlComma = KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_COMMA, 0, KeyEvent.META_CTRL_ON)
        assertTrue(a.onKeyShortcut(KeyEvent.KEYCODE_COMMA, ctrlComma))
        rule.awaitNode { onNodeWithText("Alarm sound") }
    }

    @Test
    fun `back from the licenses lands on Settings, by system back or the screen's own arrow`() {
        grantNotifications()
        val a = launch()
        rule.awaitNode { onNodeWithText("Add time to start") }
        a.onKeyShortcut(
            KeyEvent.KEYCODE_COMMA,
            KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_COMMA, 0, KeyEvent.META_CTRL_ON),
        )
        openLicenses()
        a.onBackPressedDispatcher.onBackPressed()
        rule.awaitNode { onNodeWithContentDescription("Open-source notices, ", substring = true) }
        rule.onNodeWithText("Ducky is built with the software below. Tap any to read its license.")
            .assertDoesNotExist()

        openLicenses()
        rule.onNodeWithContentDescription("Back").performClick()
        rule.awaitNode { onNodeWithContentDescription("Open-source notices, ", substring = true) }
        rule.onNodeWithText("Ducky is built with the software below. Tap any to read its license.")
            .assertDoesNotExist()

        // And back from Settings returns to the timer.
        a.onBackPressedDispatcher.onBackPressed()
        rule.awaitNode { onNodeWithText("Add time to start") }
    }

    private fun openLicenses() {
        // The row sits far down Settings. performScrollTo never converges under the manual clock
        // (the pond keeps the layout busy), so the row's click action is invoked directly.
        rule.awaitNode {
            onNodeWithContentDescription("Open-source notices, ", substring = true)
        }.performSemanticsAction(SemanticsActions.OnClick)
        rule.awaitNode { onNodeWithText("Ducky is built with the software below. Tap any to read its license.") }
    }

    // ---- Leaving: the launcher icon follows the accent ----

    @Test
    fun `leaving the app swaps the launcher icon to the accent`() {
        grantNotifications()
        runBlocking { TimerStore.setSeedColor(context, PRESET_SEEDS[0]) } // red
        launch()
        rule.awaitNode { onNodeWithText("Add time to start") }
        scenario!!.moveToState(Lifecycle.State.CREATED) // Home: stopped, not finishing
        val red = LauncherIcon.aliasComponent(context.packageName, 0)
        awaitTrue {
            context.packageManager.getComponentEnabledSetting(red) ==
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        }
    }

    @Test
    fun `no icon swap while something the app opened is on top of it`() {
        grantNotifications()
        runBlocking { TimerStore.setSeedColor(context, PRESET_SEEDS[0]) }
        val a = launch()
        rule.awaitNode { onNodeWithText("Add time to start") }
        // The sound picker (another app's activity) sits on top of us in our task.
        val task = ShadowAppTask.newInstance()
        shadowOf(task).setTaskInfo(ActivityManager.RecentTaskInfo().apply {
            taskId = a.taskId
            topActivity = ComponentName("com.android.providers.media", "RingtonePickerActivity")
        })
        shadowOf(context.getSystemService(ActivityManager::class.java)).setAppTasks(listOf(task))
        scenario!!.moveToState(Lifecycle.State.CREATED)
        Thread.sleep(300)
        shadowOf(Looper.getMainLooper()).idle()
        assertNotEquals(
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            context.packageManager.getComponentEnabledSetting(LauncherIcon.aliasComponent(context.packageName, 0)),
        )
    }

    // ---- Styles ----

    @Test
    fun `London shouts its labels in outlined tiles`() {
        grantNotifications()
        runBlocking {
            TimerStore.setStyle(context, AppStyle.LONDON.key)
            TimerController.addTime(context, 300_000L)
        }
        launch()
        rule.awaitNode { onNodeWithText("TAP TO PAUSE") }
        rule.awaitNode { onNodeWithText("CANCEL") }.assertIsEnabled()
        rule.onNodeWithText("+30S").assertExists()
        rule.onNodeWithText("DUCKY").assertExists()
    }

    @Test
    fun `London's idle Cancel tile is drawn disabled`() {
        grantNotifications()
        runBlocking { TimerStore.setStyle(context, AppStyle.LONDON.key) }
        launch()
        rule.awaitNode { onNodeWithText("CANCEL") }.assertIsNotEnabled()
        rule.awaitNode { onNodeWithText("ADD TIME TO START") }
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `Beijing fills accent tiles with a gradient, Mountain View with a flat color`() {
        grantNotifications()
        runBlocking { TimerStore.setStyle(context, AppStyle.BEIJING.key) }
        launch()
        assertTrue(topAndBottomDiffer())
        scenario!!.close()

        runBlocking { TimerStore.setStyle(context, AppStyle.MOUNTAIN_VIEW.key) }
        launch()
        assertFalse(topAndBottomDiffer())
    }

    /** Compares the "+30s" tile's fill just inside its top and bottom edges. */
    private fun topAndBottomDiffer(): Boolean {
        val tile = rule.awaitNode { onNodeWithContentDescription("Add 30 seconds") }
        settle()
        val bounds = tile.fetchSemanticsNode().boundsInWindow
        // captureToImage waits for a frame the manual clock never draws; draw the window instead.
        val root = activity!!.window.decorView
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        val x = bounds.center.x.toInt()
        val top = bitmap.getPixel(x, bounds.top.toInt() + 4)
        val bottom = bitmap.getPixel(x, bounds.bottom.toInt() - 5)
        assertNotEquals("the tile was not drawn", 0, Color.alpha(top))
        return top != bottom
    }

    // ---- Wide screens ----

    @Test
    @Config(qualifiers = "w900dp-h480dp-land")
    fun `a wide landscape screen puts the controls beside the pond`() {
        grantNotifications()
        launch()
        val pond = rule.awaitNode { onNodeWithText("Add time to start") }.fetchSemanticsNode().boundsInRoot
        val add = rule.awaitNode { onNodeWithContentDescription("Add 30 seconds") }.fetchSemanticsNode().boundsInRoot
        assertTrue("controls $add should be right of the pond $pond", add.left >= pond.right)
    }

    @Test
    @Config(qualifiers = "w859dp-h954dp")
    fun `an unfolded foldable held upright stacks the pond over bigger buttons`() {
        grantNotifications()
        launch()
        val pond = rule.awaitNode { onNodeWithText("Add time to start") }.fetchSemanticsNode().boundsInRoot
        val add = rule.awaitNode { onNodeWithContentDescription("Add 30 seconds") }.fetchSemanticsNode().boundsInRoot
        val dp = activity!!.resources.displayMetrics.density
        assertTrue("stacked: controls $add below the pond text $pond", add.top > pond.bottom)
        assertTrue("grown buttons: ${add.height / dp} dp", add.height / dp > 120f)
    }

    @Test
    @Config(qualifiers = "w900dp-h480dp-land")
    fun `beside the controls the settings cog sits at the window's top end`() {
        grantNotifications()
        launch()
        val add = rule.awaitNode { onNodeWithContentDescription("Add 30 seconds") }.fetchSemanticsNode().boundsInRoot
        val cog = rule.awaitNode { onNodeWithContentDescription("Settings") }.fetchSemanticsNode().boundsInRoot
        val width = activity!!.window.decorView.width.toFloat()
        val dp = activity!!.resources.displayMetrics.density
        assertTrue("cog $cog over the controls (right of ${add.left})", cog.left > add.left)
        assertTrue("cog near the right edge: ${width - cog.right} px", width - cog.right < 40f * dp)
        assertTrue("cog above the controls", cog.bottom < add.top)
        rule.onNodeWithContentDescription("Settings").performClick()
        rule.awaitNode { onNodeWithText("Alarm sound") }
    }
}
