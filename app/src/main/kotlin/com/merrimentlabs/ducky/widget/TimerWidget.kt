package com.merrimentlabs.ducky.widget

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.dp
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.glance.GlanceId
import androidx.glance.GlanceTheme
import androidx.glance.LocalSize
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.currentState
import androidx.glance.layout.height
import androidx.glance.layout.width
import androidx.glance.state.GlanceStateDefinition
import androidx.glance.text.FontFamily
import com.merrimentlabs.ducky.R
import com.merrimentlabs.ducky.TimerState
import com.merrimentlabs.ducky.TimerStore
import com.merrimentlabs.ducky.timerDataStore
import com.merrimentlabs.ducky.ui.AppStyle
import com.merrimentlabs.ducky.ui.Palette
import java.io.File

/**
 * Glance's update event reads the authoritative DataStore itself. This avoids
 * racing an independently collected flow or maintaining a second timer copy.
 * The location is a widget-owned placeholder: deleting a widget must NEVER
 * delete the shared timer/preferences file.
 */
internal object TimerWidgetState : GlanceStateDefinition<Preferences> {
    override fun getLocation(context: Context, fileKey: String): File =
        File(context.filesDir, "widget-display/$fileKey")

    override suspend fun getDataStore(context: Context, fileKey: String): DataStore<Preferences> =
        context.timerDataStore
}

/**
 * The picture in a widget's time box. Everything else (layouts, buttons, ring actions, the one
 * Chronometer, the tick chain) is shared, so the widget rules hold for every look by
 * construction. Every look but the tank draws a pond band at the bottom of the box, with the
 * clock above it on the sky.
 */
enum class WidgetLook {
    /** "Water tank": the water drains. */
    TANK,
    /** "Lily pads": the duck hops pad to pad. */
    LILY_PADS,
    /** "Bubbles": one bubble pops per slice of the timer. */
    BUBBLES,
    /** "Egg timer": one egg hatches per slice of the timer. */
    EGGS,
}

internal val LocalLook = staticCompositionLocalOf { WidgetLook.TANK }

open class TimerWidget(private val look: WidgetLook = WidgetLook.TANK) : GlanceAppWidget() {

    override val stateDefinition: GlanceStateDefinition<Preferences> = TimerWidgetState

    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            // Glance reloads its own state before recomposing an update. A separate
            // collectAsState flow can lag behind updateAll in a live launcher session.
            val snapshot = currentState<Preferences>()
            val state = TimerStore.stateOf(snapshot)
            val settings = TimerStore.settingsOf(snapshot)
            val colors = remember(
                settings.styleKey, settings.seedColorArgb, settings.highContrast
            ) {
                Palette.glance(settings)
            }
            val style = AppStyle.fromKey(settings.styleKey)
            GlanceTheme(colors = colors) {
              CompositionLocalProvider(LocalLook provides look) {
                val size = LocalSize.current
                when {
                    size.height >= 130.dp -> StackedWidget(
                        state, settings, style,
                        maxIncrements = if (size.width >= 200.dp) 3 else 2,
                        fontSp = if (size.width >= 200.dp) 13 else 12,
                    )
                    size.width >= 250.dp -> BarWidget(state, settings, style)
                    else -> CompactWidget(state, settings, style)
                }
              }
            }
        }
    }

    companion object {
        /** Every re-render request (tick, heartbeat, settings) reaches every widget. */
        suspend fun refreshAll(context: Context) =
            attemptEach(all().map { widget -> suspend { widget.updateAll(context) } })

        /** One provider's update may take this long before the next one gets its turn. */
        internal const val PROVIDER_TIMEOUT_MS = 5_000L

        /**
         * Runs every attempt, each bounded by [perAttemptMs]: a provider that throws or hangs
         * must not cost the others their update (one hung provider would otherwise use up the
         * whole render's time). Throws the first failure afterward, for the caller to log; cancellation of the
         * caller still cancels.
         */
        internal suspend fun attemptEach(attempts: List<suspend () -> Unit>, perAttemptMs: Long = PROVIDER_TIMEOUT_MS) {
            var failure: Throwable? = null
            for (attempt in attempts) {
                try {
                    if (kotlinx.coroutines.withTimeoutOrNull(perAttemptMs) { attempt() } == null) {
                        val e = IllegalStateException("widget update timed out")
                        if (failure == null) failure = e else failure.addSuppressed(e)
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (failure == null) failure = e else failure.addSuppressed(e)
                }
            }
            failure?.let { throw it } // the caller logs it (TimerController.refreshWidget)
        }

        /** One instance per widget provider, the tank first. */
        internal fun all(): List<TimerWidget> = listOf(TimerWidget(), PondWidget(), BubblesWidget(), EggTimerWidget())
    }
}

/** The same widget with the duck hopping lily pads in its time box. */
class PondWidget : TimerWidget(WidgetLook.LILY_PADS)

/** The same widget with a row of bubbles that pop one by one. */
class BubblesWidget : TimerWidget(WidgetLook.BUBBLES)

/** The same widget with a nest of eggs that hatch one by one. */
class EggTimerWidget : TimerWidget(WidgetLook.EGGS)

/** Log tag for the per-frame trace in [TimeContainer]. */
internal const val FRAME_TAG = "DuckyFrames"

/** Minimum touch target. Single-row controls grow together up to 72 dp. */
internal const val BUTTON_DP = 48

/** Compact's single control and Bar's clock slot: fixed, so the budgets are honest. */
internal const val COMPACT_BUTTON_DP = 48f
internal const val BAR_CLOCK_DP = 66f

/** Presentation states. RINGING is a real state here: "Time's up" + Stop / +1 min, never idle. */
internal enum class DisplayMode { RUNNING, PAUSED, RINGING, IDLE }

internal fun modeOf(state: TimerState): DisplayMode = when {
    state.isRinging -> DisplayMode.RINGING
    state.isRunning(System.currentTimeMillis()) -> DisplayMode.RUNNING
    state.isPaused -> DisplayMode.PAUSED
    else -> DisplayMode.IDLE
}

internal val DisplayMode.subtitleRes: Int
    get() = when (this) {
        DisplayMode.RUNNING -> R.string.widget_remaining
        DisplayMode.PAUSED -> R.string.widget_paused
        DisplayMode.RINGING -> R.string.widget_done
        DisplayMode.IDLE -> R.string.widget_tap_to_start
    }

internal fun AppStyle.glanceFont(): FontFamily? =
    if (clockFont == androidx.compose.ui.text.font.FontFamily.Monospace) FontFamily.Monospace
    else null

