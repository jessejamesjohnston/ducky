package com.merrimentlabs.ducky

import android.view.HapticFeedbackConstants
import androidx.compose.ui.platform.LocalView
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.merrimentlabs.ducky.ui.LocalAppStyle
import com.merrimentlabs.ducky.ui.TimerTheme
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay

/**
 * Lock-screen / full-screen alarm. Two plain, labeled outcomes ("+1 min"
 * and "Stop"), the same pair the notification, the app, and the widget
 * offer. Ducky is the character on the screen, not the control: nothing
 * here asks the user to hold or drag the duck.
 */
class AlarmActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        setContent {
            TimerTheme {
                AlarmScreen(onStopped = { finishAndRemoveTask() })
            }
        }
    }
}

@Composable
private fun AlarmScreen(onStopped: () -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current
    val state by TimerStore.stateFlow(context).collectAsState(initial = null)

    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                now = System.currentTimeMillis()
                delay(500)
            }
        }
    }

    // Whatever surface ends the ring (notification, widget, app, this
    // screen), the persisted state flips and this screen leaves.
    LaunchedEffect(state) {
        if (state != null && !state!!.isRinging) onStopped()
    }

    // Buttons act once per ring; the controller's session guard makes a duplicate
    // harmless, but the screen shouldn't invite one. A new ring (this screen is
    // single-instance and can come back for the next one) starts with working buttons.
    val session = state?.ringingSinceMillis ?: 0L
    var acted by remember(session) { mutableStateOf(false) }
    val style = LocalAppStyle.current

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
    ) {
        Spacer(modifier = Modifier.height(48.dp))

        Icon(
            painter = painterResource(R.drawable.ic_duck),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(120.dp)
        )
        Text(
            text = stringResource(R.string.alarm_done).let { if (style.upperCaseLabels) it.uppercase() else it },
            fontSize = 13.sp,
            letterSpacing = if (style.upperCaseLabels) 2.sp else 0.5.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = style.clockFont,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 20.dp)
        )
        Text(
            text = "0:00",
            fontSize = 74.sp,
            fontWeight = style.clockWeight,
            fontFamily = style.clockFont,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 8.dp)
        )
        state?.let { s ->
            if (s.isRinging) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .padding(top = 12.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                        .padding(horizontal = 18.dp, vertical = 9.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                    )
                    Text(
                        // total == 0 is the Settings "Test alarm" (no duration to name).
                        text = "  " + if (s.totalMillis > 0) stringResource(
                            R.string.ringing_status,
                            TimerMath.formatClock(s.totalMillis),
                            TimerMath.formatClock(now - s.ringingSinceMillis)
                        ) else stringResource(
                            R.string.ringing_status_test,
                            TimerMath.formatClock(now - s.ringingSinceMillis)
                        ),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(40.dp))

        val enabled = !acted && session > 0L
        AlarmActions(
            enabled = enabled,
            modifier = Modifier
                .widthIn(max = 520.dp)
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            onExtend = {
                view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                acted = true
                TimerController.requestExtendRing(context, session)
            },
            onStop = {
                view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                acted = true
                TimerController.requestStopRing(context, session)
            },
        )

        Spacer(modifier = Modifier.height(32.dp))
    }
}
