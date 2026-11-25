package com.merrimentlabs.ducky

import android.provider.Settings
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.merrimentlabs.ducky.ui.pond.rememberReducedMotion
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The system "Remove animations" switch as the pond sees it (rememberReducedMotion). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PondReducedMotionTest {

    /** The recomposer's animation scale, as the platform provides it: live snapshot state. */
    private val motion = object : MotionDurationScale {
        override var scaleFactor by mutableFloatStateOf(1f)
    }

    @OptIn(ExperimentalTestApi::class)
    @get:Rule val rule = createComposeRule(motion)

    @Test fun `flipping Remove animations while the screen is up takes effect without a relaunch`() {
        rule.manualClock()
        rule.setContent { Text(if (rememberReducedMotion()) "still" else "moving") }
        rule.awaitNode { onNodeWithText("moving") }
        motion.scaleFactor = 0f
        rule.awaitNode { onNodeWithText("still") }
        motion.scaleFactor = 1f
        rule.awaitNode { onNodeWithText("moving") }
    }
}

/** Without the recomposer's scale (an older host), the platform setting decides. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PondReducedMotionSettingTest {

    @get:Rule val rule = createComposeRule()

    private val resolver get() = ApplicationProvider.getApplicationContext<android.content.Context>().contentResolver

    @After fun restore() {
        Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    }

    @Test fun `animator duration scale 0 reads as reduced motion`() {
        Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        rule.manualClock()
        rule.setContent { Text(if (rememberReducedMotion()) "still" else "moving") }
        rule.awaitNode { onNodeWithText("still") }
    }

    @Test fun `the default scale reads as motion`() {
        rule.manualClock()
        rule.setContent { Text(if (rememberReducedMotion()) "still" else "moving") }
        rule.awaitNode { onNodeWithText("moving") }
    }
}
