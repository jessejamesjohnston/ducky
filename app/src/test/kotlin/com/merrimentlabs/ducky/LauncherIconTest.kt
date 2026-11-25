package com.merrimentlabs.ducky

import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Every color alias LauncherIcon toggles must be a component the manifest actually declares,
 * or setComponentEnabledSetting throws on a real device and a color change crashes the app.
 * The app id and the code namespace are the same name today; the class half must still
 * come from the code (MainActivity's package), never from the app id.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LauncherIconTest {
    @Test fun everyAliasIsDeclared() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val pm = context.packageManager
        for (i in 0 until LauncherIcon.aliasCount) {
            val c = LauncherIcon.aliasComponent(context.packageName, i)
            val info = pm.getActivityInfo(c, PackageManager.MATCH_DISABLED_COMPONENTS)
            assertEquals(c.className, info.name)
        }
    }

    @Test fun aliasClassComesFromTheCodeNotTheAppId() {
        val code = MainActivity::class.java.name.substringBeforeLast('.')
        for (i in 0 until LauncherIcon.aliasCount) {
            val c = LauncherIcon.aliasComponent("org.example.other", i)
            assertEquals("org.example.other", c.packageName)
            assertTrue(c.className, c.className.startsWith("$code.Launcher"))
        }
    }
}
