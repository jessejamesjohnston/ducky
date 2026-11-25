package com.merrimentlabs.ducky

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.ColorDrawable
import androidx.test.core.app.ApplicationProvider
import com.merrimentlabs.ducky.ui.PRESET_SEEDS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The accent swap: exactly one launcher alias is effectively enabled, the one matching the accent
 * (a custom color takes the nearest preset hue). "Effectively": an alias never written keeps the
 * manifest default, which is enabled only for Blue (index 7), so LauncherIcon writes only the
 * aliases whose state actually changes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LauncherIconSwapTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val pm = context.packageManager
    private val blue = 7

    private fun setting(i: Int) = pm.getComponentEnabledSetting(LauncherIcon.aliasComponent(context.packageName, i))

    private fun effectivelyEnabled(i: Int) = when (setting(i)) {
        PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
        PackageManager.COMPONENT_ENABLED_STATE_DISABLED -> false
        else -> i == blue
    }

    private fun enabledAliases() = (0 until LauncherIcon.aliasCount).filter(::effectivelyEnabled)

    @Test
    fun `there is one alias per preset accent, Blue the default`() {
        assertEquals(PRESET_SEEDS.size, LauncherIcon.aliasCount)
        assertEquals(listOf(blue), enabledAliases())
    }

    @Test
    fun `the default accent on a fresh install writes no component state`() {
        LauncherIcon.apply(context, PRESET_SEEDS[blue])
        for (i in 0 until LauncherIcon.aliasCount) {
            assertEquals("alias $i", PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, setting(i))
        }
    }

    @Test
    fun `every preset accent enables its own alias and only that one`() {
        for ((i, seed) in PRESET_SEEDS.withIndex()) {
            LauncherIcon.apply(context, seed)
            assertEquals("accent $i", listOf(i), enabledAliases())
            if (i != blue) {
                assertEquals("accent $i", PackageManager.COMPONENT_ENABLED_STATE_ENABLED, setting(i))
                assertEquals("accent $i", PackageManager.COMPONENT_ENABLED_STATE_DISABLED, setting(blue))
            }
        }
        // Back to Blue: written ENABLED now, since it was explicitly disabled before.
        LauncherIcon.apply(context, PRESET_SEEDS[blue])
        assertEquals(listOf(blue), enabledAliases())
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_ENABLED, setting(blue))
    }

    @Test
    fun `a custom accent takes the alias of the nearest preset hue`() {
        // A slightly shifted red, and a hue just below 360 that wraps around to red.
        LauncherIcon.apply(context, 0xFFD0342C.toInt())
        assertEquals(listOf(0), enabledAliases())
        LauncherIcon.apply(context, 0xFFE01828.toInt())
        assertEquals(listOf(0), enabledAliases())
        // A near-blue custom color goes back to the default alias.
        LauncherIcon.apply(context, 0xFF1F86E0.toInt())
        assertEquals(listOf(blue), enabledAliases())
    }

    @Test
    fun `currentIcon is the enabled alias's icon`() {
        val shadow = shadowOf(pm)
        val icons = (0 until LauncherIcon.aliasCount).map { ColorDrawable(0xFF000000.toInt() or it) }
        icons.forEachIndexed { i, d -> shadow.addActivityIcon(LauncherIcon.aliasComponent(context.packageName, i), d) }
        // Fresh install: the manifest default (Blue).
        assertSame(icons[blue], LauncherIcon.currentIcon(context))
        LauncherIcon.apply(context, PRESET_SEEDS[2])
        assertSame(icons[2], LauncherIcon.currentIcon(context))
        LauncherIcon.apply(context, PRESET_SEEDS[11])
        assertSame(icons[11], LauncherIcon.currentIcon(context))
    }

    @Test
    fun `currentIcon falls back to the app icon when the alias has none`() {
        val shadow = shadowOf(pm)
        val appIcon = ColorDrawable(0xFF123456.toInt())
        shadow.setApplicationIcon(context.packageName, appIcon)
        shadow.removeActivity(LauncherIcon.aliasComponent(context.packageName, blue))
        assertSame(appIcon, LauncherIcon.currentIcon(context))
    }

    @Test
    fun `currentIcon with every alias disabled falls back to Blue's icon`() {
        val shadow = shadowOf(pm)
        val blueIcon = ColorDrawable(0xFF0000FF.toInt())
        shadow.addActivityIcon(LauncherIcon.aliasComponent(context.packageName, blue), blueIcon)
        for (i in 0 until LauncherIcon.aliasCount) {
            pm.setComponentEnabledSetting(
                LauncherIcon.aliasComponent(context.packageName, i),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP,
            )
        }
        assertNotNull(LauncherIcon.currentIcon(context))
        assertSame(blueIcon, LauncherIcon.currentIcon(context))
    }

    @Test
    fun `two overlapping swaps always leave exactly one icon`() {
        // Two exits close together (onStop and an update, or two quick visits) can swap at once.
        val red = PRESET_SEEDS[0]
        val green = PRESET_SEEDS[4]
        repeat(300) { round ->
            val barrier = java.util.concurrent.CyclicBarrier(2)
            val a = Thread { barrier.await(); LauncherIcon.apply(context, red) }
            val b = Thread { barrier.await(); LauncherIcon.apply(context, green) }
            a.start(); b.start(); a.join(); b.join()
            assertEquals("round $round", 1, enabledAliases().size)
        }
    }
}
