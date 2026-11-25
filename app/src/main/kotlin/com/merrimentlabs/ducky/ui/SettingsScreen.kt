package com.merrimentlabs.ducky.ui

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.merrimentlabs.ducky.R
import com.merrimentlabs.ducky.TimerController
import com.merrimentlabs.ducky.TimerSettings
import com.merrimentlabs.ducky.TimerStore

/**
 * The settings page: the alarm card, the time buttons, style, color and About, in one centered
 * column that is width-capped on large screens.
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenLicenses: () -> Unit,
    // Hoisted by MainActivity so Back from the licenses screen returns to the About card.
    scrollState: ScrollState = rememberScrollState(),
    // True when coming back from the licenses screen: bring the About card into view (the
    // saved scroll offset alone misses it after a rotation, since the layout height changes).
    revealAbout: Boolean = false,
    onAboutRevealed: () -> Unit = {},
) {
    val context = LocalContext.current
    val settings by TimerStore.settingsFlow(context).collectAsState(initial = null)
    val s = settings ?: return
    val style = LocalAppStyle.current
    val haptic = rememberHaptic()

    // Centered, width-capped so cards don't stretch edge-to-edge on tablets.
    // Order matters: widthIn must cap the incoming max BEFORE fillMaxWidth
    // fills it: the reverse fills to the full screen first and can't shrink.
    val colModifier = Modifier.widthIn(max = 600.dp).fillMaxWidth()
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(scrollState)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = colModifier.padding(horizontal = 8.dp, vertical = 6.dp)
        ) {
            IconButton(onClick = { haptic(); onBack() }) {
                Icon(
                    painter = painterResource(R.drawable.ic_back),
                    contentDescription = stringResource(R.string.back),
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
        }
        // Seoul: a large centered header, with the content pushed down within thumb reach.
        if (style.bigHeader) {
            Text(
                text = stringResource(R.string.settings_title),
                fontSize = 36.sp,
                fontWeight = FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                modifier = colModifier.padding(top = 34.dp, bottom = 52.dp)
            )
        } else {
            Text(
                text = stringResource(R.string.settings_title)
                    .let { if (style.upperCaseLabels) it.uppercase() else it },
                fontSize = 30.sp,
                fontWeight = FontWeight.Medium,
                fontFamily = style.clockFont,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = colModifier.padding(start = 24.dp, bottom = 18.dp)
            )
        }

        Column(
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = colModifier.padding(horizontal = 16.dp)
        ) {
            AlarmCard(s, onBack)
            TimeButtonsCard(s.incrementsSeconds)
            StyleCard(selectedKey = s.styleKey)
            ColorCard(seedArgb = s.seedColorArgb)
            AboutCard(revealAbout, onAboutRevealed, onOpenLicenses)
        }
    }
}

/**
 * The first card: alarm sound, vibrate, Test alarm, Add widget and high contrast. Test alarm
 * is offered only while no timer is set, and leaves for the timer screen, which has the Stop
 * and +1 min controls.
 */
@Composable
private fun AlarmCard(s: TimerSettings, onBack: () -> Unit) {
    val context = LocalContext.current
    val haptic = rememberHaptic()
    val systemDefault = stringResource(R.string.system_default)
    // "Test alarm" only from idle: it must never displace a live timer.
    val timerState by TimerStore.stateFlow(context).collectAsState(initial = null)
    // A deadline already behind the clock with no ring is shown as idle everywhere, so it counts
    // as idle here too (TimerController.testAlarm applies the same rule).
    val timerIdle = timerState?.let {
        !it.isRunning(System.currentTimeMillis()) && !it.isPaused && !it.isRinging
    } ?: false

    val soundTitle = remember(s.soundUri) {
        val uri = s.soundUri?.let(Uri::parse)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        runCatching {
            RingtoneManager.getRingtone(context, uri)?.getTitle(context)
        }.getOrNull() ?: systemDefault
    }

    val soundPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri: Uri? =
                result.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
            // null is the picker's "Silent", which the store reads as "system
            // default": the opposite. Silent is not offered (SHOW_SILENT
            // below); a null from any other picker is ignored, not stored.
            if (uri != null) {
                TimerController.requestSettingsWrite(context) { TimerStore.setSoundUri(it, uri.toString()) }
            }
        }
    }

    SettingsCard {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    val current = s.soundUri?.let(Uri::parse)
                        ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                    soundPicker.launch(
                        Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                            putExtra(
                                RingtoneManager.EXTRA_RINGTONE_TYPE,
                                RingtoneManager.TYPE_ALARM
                            )
                            putExtra(
                                RingtoneManager.EXTRA_RINGTONE_TITLE,
                                context.getString(R.string.alarm_sound)
                            )
                            putExtra(
                                RingtoneManager.EXTRA_RINGTONE_EXISTING_URI,
                                current
                            )
                            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                        }
                    )
                }
                .padding(horizontal = 18.dp, vertical = 16.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.alarm_sound),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = soundTitle,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = "›",
                fontSize = 24.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        SettingsDivider()
        SettingsToggleRow(
            title = stringResource(R.string.vibrate),
            subtitle = null,
            checked = s.vibrate,
            onChange = {
                haptic()
                TimerController.requestSettingsWrite(context) { c -> TimerStore.setVibrate(c, it) }
            }
        )
        SettingsDivider()
        SettingsActionRow(
            title = stringResource(R.string.test_alarm),
            subtitle = stringResource(
                if (timerIdle) R.string.test_alarm_subtitle
                else R.string.test_alarm_unavailable
            ),
            enabled = timerIdle,
            onClick = {
                haptic()
                // Off this screen's scope: onBack() below tears the
                // screen down, and the ring must not be canceled with it.
                TimerController.requestTestAlarm(context)
                onBack() // the timer screen has Stop / +1 min; this one doesn't
            }
        )
        // "Add widget": one row that opens a sheet of the four looks; picking one
        // shows the launcher's pin dialog (requestPinAppWidget, the only public way an
        // app can offer its widgets). Absent where the launcher can't pin (some
        // third-party launchers), rather than dead there.
        val widgetManager = remember { AppWidgetManager.getInstance(context) }
        if (widgetManager.isRequestPinAppWidgetSupported) {
            var showWidgetSheet by rememberSaveable { mutableStateOf(false) }
            SettingsDivider()
            SettingsActionRow(
                title = stringResource(R.string.add_widget),
                subtitle = stringResource(R.string.add_widget_subtitle),
                enabled = true,
                onClick = { haptic(); showWidgetSheet = true }
            )
            if (showWidgetSheet) {
                AddWidgetSheet(
                    onPick = { receiver ->
                        haptic()
                        showWidgetSheet = false
                        widgetManager.requestPinAppWidget(ComponentName(context, receiver), null, null)
                    },
                    onDismiss = { showWidgetSheet = false },
                )
            }
        }
        SettingsDivider()
        SettingsToggleRow(
            title = stringResource(R.string.high_contrast),
            subtitle = stringResource(R.string.high_contrast_subtitle),
            checked = s.highContrast,
            onChange = {
                haptic()
                TimerController.requestSettingsWrite(context, refreshWidgets = true) { c ->
                    TimerStore.setHighContrast(c, it)
                }
            }
        )
    }
}
