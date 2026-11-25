package com.merrimentlabs.ducky

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import com.merrimentlabs.ducky.ui.PRESET_SEEDS
import com.merrimentlabs.ducky.ui.Palette
import kotlin.math.abs
import kotlin.math.min

/**
 * Keeps the launcher icon on the user's accent color by swapping between the
 * 12 color-matched activity-aliases in the manifest (Android can't recolor a
 * manifest icon at runtime). Android 17 derives the notification icon-circle
 * color from the app icon, so this also themes the lock-screen notification.
 * Custom wheel colors map to the nearest preset hue.
 */
object LauncherIcon {

    /** Alias suffixes, parallel to PRESET_SEEDS in ui/TimerTheme.kt. */
    private val ALIASES = listOf(
        "Red", "Orange", "Amber", "Olive", "Green", "Teal",
        "Cyan", "Blue", "Indigo", "Purple", "Pink", "Brown",
    )
    private const val DEFAULT_INDEX = 7 // Blue: the manifest-enabled default

    // One swap at a time: two interleaved swaps each disable the other's alias, leaving none.
    @Synchronized
    fun apply(context: Context, seedArgb: Int) {
        val pm = context.packageManager
        val pkg = context.packageName
        val target = PRESET_SEEDS.indexOf(seedArgb).let {
            if (it >= 0) it else nearestByHue(seedArgb)
        }
        // Enable the new alias BEFORE disabling the old one: launchers are
        // likelier to keep a home-screen shortcut across the swap that way.
        setAlias(pm, pkg, target, enabled = true)
        ALIASES.indices.filter { it != target }
            .forEach { setAlias(pm, pkg, it, enabled = false) }
    }

    /**
     * The alias for preset [index]. The package half is the installed app id; the class half
     * is the code namespace the manifest's ".LauncherX" names resolve against. Today the
     * two are the same name, but they are different things (a build could add an id suffix),
     * so neither stands in for the other.
     */
    internal fun aliasComponent(pkg: String, index: Int): ComponentName =
        ComponentName(pkg, "$CODE_PACKAGE.Launcher${ALIASES[index]}")

    internal val aliasCount: Int get() = ALIASES.size

    /**
     * The manifest namespace, read from a manifest component: R8 keeps activity names, but it
     * renames and repackages helper classes like this one (LauncherIcon becomes something like
     * `b5.p` in a release build), so this object's own name would point at no alias at all.
     */
    private val CODE_PACKAGE: String = MainActivity::class.java.name.substringBeforeLast('.')

    /** The launcher icon in use now (the enabled alias's), for the About header. */
    fun currentIcon(context: Context): android.graphics.drawable.Drawable? {
        val pm = context.packageManager
        val pkg = context.packageName
        val index = ALIASES.indices.firstOrNull { i ->
            when (pm.getComponentEnabledSetting(aliasComponent(pkg, i))) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED -> false
                else -> i == DEFAULT_INDEX
            }
        } ?: DEFAULT_INDEX
        return runCatching { pm.getActivityIcon(aliasComponent(pkg, index)) }.getOrNull()
            ?: runCatching { pm.getApplicationIcon(pkg) }.getOrNull()
    }

    private fun setAlias(pm: PackageManager, pkg: String, index: Int, enabled: Boolean) {
        val component = aliasComponent(pkg, index)
        val current = pm.getComponentEnabledSetting(component)
        val currentlyEnabled = when (current) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED -> false
            else -> index == DEFAULT_INDEX // manifest default
        }
        if (currentlyEnabled == enabled) return
        pm.setComponentEnabledSetting(
            component,
            if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP
        )
    }

    private fun nearestByHue(argb: Int): Int {
        val hue = Palette.hueOf(argb)
        return PRESET_SEEDS.indices.minBy {
            val d = abs(Palette.hueOf(PRESET_SEEDS[it]) - hue)
            min(d, 360f - d)
        }
    }
}
