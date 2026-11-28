package com.merrimentlabs.ducky

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.TextButton
import androidx.compose.material3.Button
import com.merrimentlabs.ducky.ui.focusRingColor
import com.merrimentlabs.ducky.ui.focusRing
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.merrimentlabs.ducky.ui.AppStyle

/**
 * One line, one tap: what is blocking the alarm and the settings page that
 * fixes it. Shown only while a blocker applies; never a diagnostics panel.
 */
@Composable
internal fun AlarmBlockedBanner(
    blocker: AlarmBlocker,
    style: AppStyle,
    asking: Boolean,
    askAgain: Boolean,
    onFix: () -> Unit,
    onDismiss: () -> Unit,
    explaining: Boolean = false,
    onContinue: () -> Unit = {},
) {
    if (explaining) {
        NotificationAskCard(style, onNotNow = onDismiss, onContinue = onContinue)
        return
    }
    val message = stringResource(
        when (blocker) {
            // While the system dialog is up: why Ducky asks.
            AlarmBlocker.NOTIFICATIONS_OFF -> when {
                asking -> R.string.notifications_why
                askAgain -> R.string.alarm_ask_notifications
                else -> R.string.alarm_blocked_notifications
            }
            AlarmBlocker.ALARM_CHANNEL_QUIET -> R.string.alarm_blocked_channel
            AlarmBlocker.FULL_SCREEN_OFF -> R.string.alarm_blocked_fullscreen
        }
    )
    val action = stringResource(
        if (blocker == AlarmBlocker.NOTIFICATIONS_OFF && askAgain) R.string.alarm_action_allow
        else R.string.alarm_blocked_action
    )
    // In the app's own colors, not the error palette: this is a setting to turn on, and the
    // alarm still rings (its sound and vibration don't depend on any of these). A neutral
    // surface with the accent on the icon and the action (the theme's secondary hue is a
    // shifted tint that clashed with the water).
    val shape = RoundedCornerShape(style.cardCorner)
    val scheme = MaterialTheme.colorScheme
    val tint = scheme.onSurface
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 4.dp)
            .focusRing(shape, focusRingColor(scheme.surfaceContainerHigh))
            .clip(shape)
            .background(scheme.surfaceContainerHigh)
            .then(if (style.outlined) Modifier.border(1.dp, scheme.outline, shape) else Modifier)
            .clickable(enabled = !asking, role = Role.Button, onClickLabel = action, onClick = onFix)
            .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_notification_timer),
            contentDescription = null,
            tint = scheme.primary,
            modifier = Modifier.padding(end = 12.dp, bottom = 8.dp).size(22.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(text = message, style = MaterialTheme.typography.titleSmall, color = tint)
            if (!asking) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = action,
                        style = MaterialTheme.typography.labelLarge,
                        color = scheme.primary,
                        modifier = Modifier.heightIn(min = 40.dp).wrapContentHeight(),
                    )
                    // The full-screen alarm is a nicety: the ring and its card work without it.
                    if (blocker == AlarmBlocker.FULL_SCREEN_OFF) {
                        Text(
                            text = stringResource(R.string.alarm_action_not_now),
                            style = MaterialTheme.typography.labelLarge,
                            color = tint,
                            modifier = Modifier
                                .padding(start = 8.dp)
                                .focusRing(RoundedCornerShape(8.dp), focusRingColor())
                                .clip(RoundedCornerShape(8.dp))
                                .clickable(role = Role.Button, onClick = onDismiss)
                                .heightIn(min = 48.dp)
                                .wrapContentHeight()
                                .padding(horizontal = 12.dp),
                        )
                    } else {
                        Spacer(Modifier.height(48.dp))
                    }
                }
            } else {
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

/**
 * Before Android's notification dialog: why Ducky asks, in one sentence, then "Not now" and a
 * filled "Continue", end-aligned like a dialog's buttons. Android's dialog opens only from
 * Continue, so the reason is on screen before the question, never at the same time.
 */
@Composable
private fun NotificationAskCard(style: AppStyle, onNotNow: () -> Unit, onContinue: () -> Unit) {
    val shape = RoundedCornerShape(style.cardCorner)
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 4.dp)
            .clip(shape)
            .background(scheme.surfaceContainerHigh)
            .then(if (style.outlined) Modifier.border(1.dp, scheme.outline, shape) else Modifier)
            .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painter = painterResource(R.drawable.ic_notification_timer),
                contentDescription = null,
                tint = scheme.primary,
                modifier = Modifier.padding(end = 12.dp).size(24.dp),
            )
            Text(
                text = stringResource(R.string.notifications_why),
                // 14sp, like the other banners: at the 16sp body size the card leaves the clock
                // too little room in a short split-screen window (ClockFitTest).
                style = MaterialTheme.typography.titleSmall,
                color = scheme.onSurface,
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            TextButton(
                onClick = onNotNow,
                modifier = Modifier.focusRing(CircleShape, focusRingColor()),
            ) {
                Text(stringResource(R.string.alarm_action_not_now), style = MaterialTheme.typography.labelLarge)
            }
            Button(
                onClick = onContinue,
                modifier = Modifier.focusRing(CircleShape, focusRingColor(scheme.primary)),
            ) {
                Text(stringResource(R.string.alarm_action_continue), style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}
