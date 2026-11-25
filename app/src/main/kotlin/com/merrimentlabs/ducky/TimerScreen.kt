package com.merrimentlabs.ducky

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.merrimentlabs.ducky.ui.AppStyle
import com.merrimentlabs.ducky.ui.LocalAppStyle
import com.merrimentlabs.ducky.ui.pond.Pond
import com.merrimentlabs.ducky.ui.pond.PondColors
import com.merrimentlabs.ducky.ui.pond.PondSim
import com.merrimentlabs.ducky.ui.pond.pondInk
import com.merrimentlabs.ducky.ui.pond.rememberReducedMotion
import kotlinx.coroutines.delay

/**
 * The timer screen: the pond with its header and the controls, plus the alarm-setup card while
 * something would stop the alarm from ringing. Collects the timer and settings from TimerStore
 * and sends every tap through TimerController.
 */
@Composable
internal fun TimerScreen(
    onOpenSettings: () -> Unit,
    onAskNotifications: () -> Unit,
    canAskNotifications: () -> Boolean,
    notificationAnswers: Int,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val style = LocalAppStyle.current
    val state by TimerStore.stateFlow(context).collectAsState(initial = null)
    val settings by TimerStore.settingsFlow(context).collectAsState(initial = null)

    // Display tick only: AlarmManager owns firing, DataStore owns end time.
    // Runs only while a deadline is set: paused / idle / ringing screens have
    // nothing that changes with the clock.
    // Only while the screen is started: a backgrounded app must not keep waking for it.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(state?.endTimeMillis, lifecycle) {
        now = System.currentTimeMillis()
        if ((state?.endTimeMillis ?: 0L) <= 0L) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                now = System.currentTimeMillis()
                delay(250)
            }
        }
    }

    val s = state ?: return
    val running = s.isRunning(now)
    val paused = s.isPaused
    val remaining = if (paused) s.pausedRemainingMillis else (s.endTimeMillis - now).coerceAtLeast(0)
    val increments = settings?.incrementsSeconds ?: TimerStore.DEFAULT_INCREMENTS

    fun haptic() = view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
    fun onIncrement(seconds: Int) {
        haptic(); TimerController.requestAddTime(context, seconds * 1000L)
    }

    // Can a set timer actually alert? Re-checked on every resume: coming back
    // from the system settings page (or the permission dialog) is exactly
    // when the answer changes.
    var blocker by remember { mutableStateOf<AlarmBlocker?>(null) }
    // Notifications are asked for the first time a timer is set here (started in the app, or
    // found running from a widget), with the reason on the banner while the dialog is up.
    // Before that, missing notifications are not reported: nothing is set that could ring.
    var askPending by remember { mutableStateOf(NotificationAsk.pending(context)) }
    // Saveable: a rotation while the system dialog is up keeps the reason on the banner.
    var asking by rememberSaveable { mutableStateOf(false) }
    // Notifications off and Android still shows its dialog: the card asks there, no Settings trip.
    var askAgain by remember { mutableStateOf(false) }
    // "Not now" on the full-screen tip: the alarm still rings and shows its card without it.
    var fullScreenTipDismissed by remember { mutableStateOf(FullScreenTip.dismissed(context)) }
    LifecycleResumeEffect(Unit) {
        blocker = AlarmReadiness.check(context)
        askPending = NotificationAsk.pending(context)
        askAgain = canAskNotifications()
        onPauseOrDispose { }
    }
    // The answer came back (with or without a dialog): show what is true now.
    LaunchedEffect(notificationAnswers) {
        if (notificationAnswers > 0) {
            asking = false
            blocker = AlarmReadiness.check(context)
            askPending = NotificationAsk.pending(context)
            askAgain = canAskNotifications()
        }
    }
    // A ring counts as "set" too: Test alarm before any timer, or opening the app to a ring
    // started from a widget, is exactly when notifications matter.
    val timerSet = running || paused || s.isRinging
    LaunchedEffect(timerSet) {
        if (timerSet && NotificationAsk.pending(context)) {
            asking = true
            askPending = false
            onAskNotifications()
        }
    }
    val shownBlocker = when {
        blocker == AlarmBlocker.NOTIFICATIONS_OFF && askPending -> null
        blocker == AlarmBlocker.FULL_SCREEN_OFF && fullScreenTipDismissed -> null
        else -> blocker
    }
    fun openFix(b: AlarmBlocker) {
        haptic()
        if (b == AlarmBlocker.NOTIFICATIONS_OFF && askAgain) {
            asking = true
            onAskNotifications()
            return
        }
        // A missing settings page (odd OEM builds) falls back to the app's
        // notification settings rather than crashing the timer.
        runCatching { context.startActivity(AlarmReadiness.settingsIntent(context, b)) }
            .onFailure {
                context.startActivity(
                    AlarmReadiness.settingsIntent(context, AlarmBlocker.NOTIFICATIONS_OFF)
                )
            }
    }

    val onToggle = { haptic(); TimerController.requestTogglePause(context) }
    val onCancel = { haptic(); TimerController.requestCancel(context) }
    val onStop = { haptic(); TimerController.requestStopRing(context, s.ringingSinceMillis) }
    val onExtend = { haptic(); TimerController.requestExtendRing(context, s.ringingSinceMillis) }

    PondScreen(
        s = s,
        style = style,
        seed = settings?.seedColorArgb ?: TimerStore.DEFAULT_SEED,
        highContrast = settings?.highContrast ?: false,
        remaining = remaining,
        running = running,
        paused = paused,
        blocker = shownBlocker,
        askingNotifications = asking,
        askAgain = askAgain,
        onDismissBlocker = {
            haptic()
            FullScreenTip.dismiss(context)
            fullScreenTipDismissed = true
        },
        increments = increments,
        context = context,
        onSettings = { haptic(); onOpenSettings() },
        onFix = { openFix(it) },
        onToggle = onToggle,
        onIncrement = ::onIncrement,
        onCancel = onCancel,
        onStop = onStop,
        onExtend = onExtend,
    )
}

@Composable
private fun Header(style: AppStyle, onSettings: (() -> Unit)?) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 10.dp)
    ) {
        Text(
            text = stringResourceStyled(stringResource(R.string.app_name), style.upperCaseLabels),
            fontSize = 24.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = style.clockFont,
            color = MaterialTheme.colorScheme.onSurface
        )
        Box(modifier = Modifier.weight(1f))
        // Without the cog the row keeps its height: the water's top line hangs off it.
        if (onSettings != null) SettingsButton(onSettings) else Spacer(Modifier.size(48.dp))
    }
}

@Composable
private fun SettingsButton(onSettings: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(onClick = onSettings, modifier = modifier) {
        Icon(
            painter = painterResource(R.drawable.ic_settings),
            contentDescription = stringResource(R.string.a11y_open_settings),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}


/**
 * The pond screen: the water fills everything above the controls, edge to edge and up under
 * the status bar; the header floats over the sky. The readout sits in the middle and changes
 * ink where the water crosses it. Semantics: one clickable node (pause /
 * resume) whose readout announces the time and state; the pond itself is decoration.
 */
@Composable
private fun PondScreen(
    s: TimerState,
    style: AppStyle,
    seed: Int,
    highContrast: Boolean,
    remaining: Long,
    running: Boolean,
    paused: Boolean,
    blocker: AlarmBlocker?,
    askingNotifications: Boolean,
    askAgain: Boolean,
    onDismissBlocker: () -> Unit,
    increments: List<Int>,
    context: android.content.Context,
    onSettings: () -> Unit,
    onFix: (AlarmBlocker) -> Unit,
    onToggle: () -> Unit,
    onIncrement: (Int) -> Unit,
    onCancel: () -> Unit,
    onStop: () -> Unit,
    onExtend: () -> Unit,
) {
    val dark = isSystemInDarkTheme()
    val scheme = MaterialTheme.colorScheme
    val colors = remember(seed, style, dark, highContrast, scheme) {
        PondColors.from(seed, style, dark, highContrast, scheme)
    }
    val sim = remember { PondSim() }
    val reduced = rememberReducedMotion()
    val press = remember { MutableInteractionSource() }
    LaunchedEffect(press) {
        press.interactions.collect { i ->
            if (i is PressInteraction.Press) sim.poke(i.pressPosition.x + sim.areaLeft)
        }
    }

    val pondArea: @Composable (Modifier, Boolean) -> Unit = { mod, besideControls ->
        Box(
            modifier = mod.onGloballyPositioned {
                val p = it.positionInRoot()
                sim.originX = p.x; sim.originY = p.y
            }
        ) {
            Pond(sim, s, colors, reduced, style.cardCorner, Modifier.matchParentSize(), squareBottomCorners = besideControls)
            Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
                Column(
                    modifier = Modifier.onGloballyPositioned {
                        sim.headerBottom = it.positionInRoot().y + it.size.height - sim.originY
                    }
                ) {
                    // Beside the controls the cog moves to the window's top end (over them).
                    Header(style, onSettings = if (besideControls) null else onSettings)
                    blocker?.let {
                        AlarmBlockedBanner(it, style, askingNotifications, askAgain, onFix = { onFix(it) }, onDismiss = onDismissBlocker)
                    }
                }
                // The press ripple is replaced by the pond's own splash, so keyboard focus
                // (Tab on a Googlebook) needs its own visible mark.
                val focused by press.collectIsFocusedAsState()
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .onGloballyPositioned { sim.areaLeft = it.positionInRoot().x - sim.originX }
                        .then(
                            if (focused) Modifier
                                .padding(6.dp)
                                .border(3.dp, colors.inkAbove, RoundedCornerShape(style.cardCorner))
                            else Modifier
                        )
                        .clickable(
                            interactionSource = press,
                            indication = null,
                            enabled = running || paused,
                            onClickLabel = stringResource(
                                if (paused) R.string.a11y_resume_timer else R.string.a11y_pause_timer
                            ),
                            role = Role.Button,
                            onClick = onToggle,
                        )
                ) {
                    PondReadout(sim, colors, style, remaining, running, paused, s.isRinging, context)
                }
            }
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val twoPane = maxWidth >= 600.dp && maxWidth > maxHeight
        if (twoPane) {
            // Tall panes (big tablets, desktop windows) get bigger buttons, so the controls
            // don't sit as a small cluster in a large empty half. Phone landscape is unchanged.
            val grow = controlsGrowth(maxHeight)
            Row(modifier = Modifier.fillMaxSize()) {
                pondArea(Modifier.weight(1f).fillMaxHeight(), true)
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .statusBarsPadding()
                        .navigationBarsPadding()
                        .padding(end = 8.dp)
                ) {
                    Column(modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth()) {
                        Controls(increments, running, paused, s, context, onIncrement, onCancel, onStop, onExtend, grow)
                    }
                    // The header's cog, at the window's top end: same row height and inset as
                    // the header (10 dp down, 22 dp in, less this pane's 8 dp end padding).
                    SettingsButton(
                        onSettings,
                        Modifier.align(Alignment.TopEnd).padding(top = 10.dp, end = 14.dp),
                    )
                }
            }
        } else {
            // Stacked on a wide window (an unfolded foldable held upright, a tablet in portrait):
            // the buttons grow with the width instead of sitting at phone size under a big pond.
            val grow = stackedGrowth(maxWidth)
            Column(modifier = Modifier.fillMaxSize()) {
                pondArea(Modifier.fillMaxWidth().weight(1f), false)
                Column(
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .widthIn(max = 520.dp + (STACKED_MAX_WIDTH - 520.dp) * grow)
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(top = 14.dp)
                ) {
                    Controls(increments, running, paused, s, context, onIncrement, onCancel, onStop, onExtend, grow)
                }
            }
        }
    }
}

/**
 * Time and hint, drawn twice: once in the sky's ink clipped above the water, once in the
 * water's ink clipped below it. Only the first copy carries semantics.
 */
@Composable
private fun PondReadout(
    sim: PondSim,
    colors: PondColors,
    style: AppStyle,
    remaining: Long,
    running: Boolean,
    paused: Boolean,
    ringing: Boolean,
    context: android.content.Context,
) {
    val spokenTime = when {
        paused -> stringResource(R.string.a11y_paused_remaining, TimerMath.speakClock(context, remaining))
        running -> stringResource(R.string.a11y_remaining, TimerMath.speakClock(context, remaining))
        ringing -> stringResource(R.string.a11y_ringing)
        else -> stringResource(R.string.a11y_no_timer)
    }
    val hint = stringResourceStyled(
        stringResource(
            when {
                paused -> R.string.hint_paused
                running -> R.string.hint_running
                ringing -> R.string.hint_ringing
                else -> R.string.hint_idle
            }
        ),
        style.upperCaseLabels
    )
    val clock = TimerMath.formatClock(remaining)
    Box(
        modifier = Modifier.onGloballyPositioned {
            val p = it.positionInRoot()
            sim.setReadoutBounds(p.x, p.y, it.size.width.toFloat(), it.size.height.toFloat())
        }
    ) {
        ReadoutLayer(clock, hint, style, colors.inkAbove, colors.inkAboveSoft, spokenTime,
            Modifier.pondInk(sim, above = true))
        ReadoutLayer(clock, hint, style, colors.inkUnder, colors.inkUnder, null,
            Modifier.pondInk(sim, above = false).clearAndSetSemantics { })
    }
}

@Composable
private fun ReadoutLayer(
    clock: String,
    hint: String,
    style: AppStyle,
    ink: Color,
    softInk: Color,
    spokenTime: String?,
    modifier: Modifier,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier) {
        // One line, as big as the pond allows up to the full size: a short window (split screen
        // beside a recipe) at large text squeezed the pond until a fixed-size clock was clipped
        // away. Weighted without fill, it gets the height the hint below leaves.
        BasicText(
            text = clock,
            // The theme's text style (letter spacing included), as Text applied before.
            style = LocalTextStyle.current.merge(
                TextStyle(color = ink, fontWeight = style.clockWeight, fontFamily = style.clockFont)
            ),
            maxLines = 1,
            softWrap = false,
            autoSize = TextAutoSize.StepBased(
                minFontSize = 20.sp,
                maxFontSize = if (style.clockFont == FontFamily.Monospace) 62.sp else 76.sp,
            ),
            modifier = Modifier.weight(1f, fill = false).then(
                if (spokenTime != null) Modifier.semantics { contentDescription = spokenTime }
                else Modifier
            )
        )
        Text(
            text = hint,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = style.clockFont,
            color = softInk,
        )
    }
}

