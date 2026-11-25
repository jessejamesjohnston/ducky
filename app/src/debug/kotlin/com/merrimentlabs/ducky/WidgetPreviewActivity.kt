package com.merrimentlabs.ducky

import android.annotation.SuppressLint
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.preferencesOf
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.compose
import androidx.lifecycle.lifecycleScope
import androidx.datastore.preferences.core.intPreferencesKey
import com.merrimentlabs.ducky.widget.BubblesWidget
import com.merrimentlabs.ducky.widget.EggTimerWidget
import com.merrimentlabs.ducky.widget.PondWidget
import com.merrimentlabs.ducky.widget.TimerWidget
import kotlinx.coroutines.launch

/** Debug-only gallery for checking every widget bucket without rearranging a launcher. */
class WidgetPreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 120, 16, 32)
        }
        setContentView(ScrollView(this).apply { addView(column) })
        lifecycleScope.launch {
            val mode = intent.getStringExtra("mode") ?: "running"
            val now = System.currentTimeMillis()
            val remaining = intent.getLongExtra("remaining", 90_000L)
            val total = intent.getLongExtra("total", maxOf(180_000L, remaining * 2))
            val state = preferencesOf(
                longPreferencesKey("end_time_millis") to if (mode == "running") now + remaining else 0L,
                longPreferencesKey("total_millis") to total,
                longPreferencesKey("paused_remaining_millis") to if (mode == "paused") remaining else 0L,
                longPreferencesKey("ringing_since_millis") to if (mode == "ringing") now else 0L,
                stringPreferencesKey("style") to (intent.getStringExtra("style") ?: "mountain_view"),
                booleanPreferencesKey("high_contrast") to intent.getBooleanExtra("hc", false),
                intPreferencesKey("seed_color") to intent.getIntExtra("seed", TimerStore.DEFAULT_SEED),
            )
            // --ez pond true renders the "Lily pads" widget instead of the tank one;
            // --es look bubbles|eggs renders the "Bubbles" or "Egg timer" widget.
            val widget = when {
                intent.getBooleanExtra("pond", false) -> PondWidget()
                intent.getStringExtra("look") == "bubbles" -> BubblesWidget()
                intent.getStringExtra("look") == "eggs" -> EggTimerWidget()
                else -> TimerWidget()
            }
            val sizes = if (intent.hasExtra("width")) {
                listOf(intent.getIntExtra("width", 390) to intent.getIntExtra("height", 60))
            } else listOf(390 to 100, 320 to 70, 250 to 70, 200 to 140, 130 to 130, 130 to 60)
            for ((width, height) in sizes) {
                // A size caption for the developer, in a debug-only screen: not user-facing text.
                @SuppressLint("SetTextI18n")
                val caption = TextView(this@WidgetPreviewActivity).apply { text = "$width × $height · $mode" }
                column.addView(caption)
                val views = widget.compose(this@WidgetPreviewActivity,
                    size = DpSize(width.dp, height.dp), state = state)
                column.addView(views.apply(this@WidgetPreviewActivity, column),
                    LinearLayout.LayoutParams((width * resources.displayMetrics.density).toInt(),
                        (height * resources.displayMetrics.density).toInt()))
            }
        }
    }
}
