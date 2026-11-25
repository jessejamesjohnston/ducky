package com.merrimentlabs.ducky

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.preferencesOf
import androidx.glance.appwidget.compose
import androidx.test.core.app.ApplicationProvider
import com.merrimentlabs.ducky.widget.TimerWidget
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WidgetSmokeTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun texts(v: View): List<String> = when (v) {
        is TextView -> listOf(v.text.toString())
        is ViewGroup -> (0 until v.childCount).flatMap { texts(v.getChildAt(it)) }
        else -> emptyList()
    }

    @Test
    fun `an idle tank widget renders its hint`() = runBlocking {
        val state = preferencesOf(longPreferencesKey("total_millis") to 0L)
        val views = TimerWidget().compose(context, size = DpSize(390.dp, 100.dp), state = state)
        val host = FrameLayout(context)
        val root = views.apply(context, host)
        val all = texts(root)
        println(all)
        assertTrue(all.toString(), all.any { it.contains("Tap + to start", ignoreCase = true) })
    }
}
