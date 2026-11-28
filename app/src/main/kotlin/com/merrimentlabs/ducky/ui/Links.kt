package com.merrimentlabs.ducky.ui

import android.content.ClipboardManager
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.browser.customtabs.CustomTabsClient
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.net.toUri

/** Where a link that leaves Ducky opens. */
internal enum class LinkTarget {
    /** The default browser, as a normal tab of its own. */
    BROWSER,

    /** An in-app browser tab (a Custom Tab), shown by a browser that provides them. */
    CUSTOM_TAB,
}

/**
 * The one rule for where links open. On a laptop-class device (one that reports
 * [PackageManager.FEATURE_PC], as Chromebooks and Googlebooks do) Ducky is one window among
 * several, so a link opens in the browser as a normal tab. Everywhere else it opens in a Custom
 * Tab, unless no installed browser provides Custom Tabs.
 */
internal fun linkTargetFor(isLaptopClass: Boolean, hasCustomTabsProvider: Boolean): LinkTarget =
    if (!isLaptopClass && hasCustomTabsProvider) LinkTarget.CUSTOM_TAB else LinkTarget.BROWSER

/**
 * Opens [url] the way [linkTargetFor] decides for this device. Ducky itself never goes online:
 * the browser loads the page, in its own tab or in a Custom Tab over Ducky. Nothing happens when
 * no app can open the link.
 */
internal fun openLink(context: Context, url: String) {
    val uri = url.toUri()
    val isLaptopClass = context.packageManager.hasSystemFeature(PackageManager.FEATURE_PC)
    // Only looked up where it can be used. Finding a provider on Android 11 and newer needs the
    // Custom Tabs service in the manifest's <queries>.
    val provider = if (isLaptopClass) null else CustomTabsClient.getPackageName(context, null)
    if (linkTargetFor(isLaptopClass, provider != null) == LinkTarget.CUSTOM_TAB) {
        val tab = CustomTabsIntent.Builder().build().apply { intent.setPackage(provider) }
        if (runCatching { tab.launchUrl(context, uri) }.isSuccess) return
    }
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
}

/**
 * Opens a new email to [address] with [subject] in the person's mail app. With no mail app, the
 * address goes on the clipboard instead, under [label] (Android confirms the copy itself).
 */
internal fun openEmail(context: Context, address: String, subject: String, label: String) {
    val intent = Intent(Intent.ACTION_SENDTO, "mailto:".toUri()).apply {
        putExtra(Intent.EXTRA_EMAIL, arrayOf(address))
        putExtra(Intent.EXTRA_SUBJECT, subject)
    }
    runCatching { context.startActivity(intent) }.onFailure {
        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(label, address))
    }
}
