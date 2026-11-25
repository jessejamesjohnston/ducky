package com.merrimentlabs.ducky

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.merrimentlabs.ducky.ui.LicensesScreen
import com.merrimentlabs.ducky.ui.SettingsScreen
import com.merrimentlabs.ducky.ui.TimerTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    // Asked when a timer is first set (TimerScreen), never at launch. The result is not read
    // here on purpose: the permission dialog is its own activity, so dismissing it resumes us
    // and the readiness check in TimerScreen re-runs; a denial surfaces as the banner.
    // The answer also bumps [notificationAnswers], so the banner moves on from the reason even
    // when the system answers without showing a dialog (no pause/resume to notice it by).
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            notificationAnswers.intValue++
            // A ring playing now posted its card while notifications were off: post it again.
            if (granted) AlarmRingService.repostIfRinging(this)
        }

    private val notificationAnswers = mutableIntStateOf(0)

    /**
     * The launcher icon follows the accent by swapping activity-aliases, and Android closes a
     * task when the alias that started it is disabled. So the swap waits until the user has
     * really left: we stop while this screen is still the top of our task (Home, Recents,
     * another app), or while finishing (backed out). If something we opened sits on top of us
     * in our task (the sound picker, a browser page), the user is still here: no swap yet.
     * (onUserLeaveHint was tried first; it also fires when we launch the picker ourselves.)
     */
    private fun syncLauncherIcon() {
        val app = applicationContext
        CoroutineScope(Dispatchers.Default).launch {
            LauncherIcon.apply(app, TimerStore.settings(app).seedColorArgb)
        }
    }

    override fun onStop() {
        super.onStop()
        if (isChangingConfigurations) return
        val top = runCatching {
            getSystemService(android.app.ActivityManager::class.java).appTasks
                .firstOrNull { it.taskInfo.taskId == taskId }?.taskInfo?.topActivity
        }.getOrNull()
        if (isFinishing || top == null || top.className == MainActivity::class.java.name) syncLauncherIcon()
    }

    /** Bumped by Ctrl+comma; the composition opens Settings when it changes. */
    private val settingsShortcut = mutableIntStateOf(0)

    // Ctrl+key presses no view handled arrive here.
    override fun onKeyShortcut(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_COMMA && event.isCtrlPressed &&
            !event.isAltPressed && !event.isShiftPressed && !event.isMetaPressed
        ) {
            settingsShortcut.intValue++
            return true
        }
        return super.onKeyShortcut(keyCode, event)
    }

    /** Remembered before the dialog shows, so a rotation while it is up never asks twice. */
    private fun askNotifications() {
        NotificationAsk.markAsked(this)
        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    /**
     * After one "Don't allow" Android still shows its dialog once more; after the second it
     * answers by itself, so the fix there is the settings page. Also false when the permission is
     * granted and the app's notifications are switched off (only Settings turns them back on).
     */
    private fun canAskNotificationsAgain(): Boolean =
        checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        TimerController.requestRearm(this)
        enableEdgeToEdge()
        setContent {
            TimerTheme {
                // rememberSaveable: a tablet rotation recreates the activity;
                // a plain remember would bounce the user out of Settings.
                var showSettings by rememberSaveable { mutableStateOf(false) }
                // Settings > About > Open-source notices (back returns to Settings).
                var showLicenses by rememberSaveable { mutableStateOf(false) }
                // Settings' scroll position lives here, outside the Settings composable, so it
                // survives a trip to the licenses screen (Back lands on the About card) and a
                // rotation. Leaving Settings for the timer resets it: Settings opens at the top.
                val settingsScroll = rememberScrollState()
                LaunchedEffect(showSettings) { if (!showSettings) settingsScroll.scrollTo(0) }
                var revealAbout by rememberSaveable { mutableStateOf(false) }
                // Ctrl+comma (onKeyShortcut) opens Settings from anywhere.
                LaunchedEffect(settingsShortcut.intValue) {
                    if (settingsShortcut.intValue > 0) { showSettings = true; showLicenses = false }
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                ) {
                    if (showSettings && showLicenses) {
                        BackHandler { showLicenses = false; revealAbout = true }
                        LicensesScreen(onBack = { showLicenses = false; revealAbout = true })
                    } else if (showSettings) {
                        BackHandler { showSettings = false }
                        SettingsScreen(
                            onBack = { showSettings = false },
                            onOpenLicenses = { showLicenses = true },
                            scrollState = settingsScroll,
                            revealAbout = revealAbout,
                            onAboutRevealed = { revealAbout = false },
                        )
                    } else {
                        TimerScreen(
                            onOpenSettings = { showSettings = true },
                            onAskNotifications = ::askNotifications,
                            canAskNotifications = ::canAskNotificationsAgain,
                            notificationAnswers = notificationAnswers.intValue,
                        )
                    }
                }
            }
        }
    }
}
