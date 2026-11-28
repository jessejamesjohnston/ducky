package com.merrimentlabs.ducky

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import com.merrimentlabs.ducky.ui.LinkTarget
import com.merrimentlabs.ducky.ui.linkTargetFor
import com.merrimentlabs.ducky.ui.openLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/** Where a link that leaves Ducky opens: laptop-class devices, phones and tablets, no provider. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LinksTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val url = "https://merrimentlabs.com/support/ducky"
    private val browser = "com.example.browser"

    /** A default browser for web links that also offers the Custom Tabs service. */
    private fun installCustomTabsBrowser() {
        val pm = shadowOf(context.packageManager)
        val activity = ComponentName(browser, "$browser.Browser")
        pm.addActivityIfNotPresent(activity)
        pm.addIntentFilterForActivity(
            activity,
            IntentFilter(Intent.ACTION_VIEW).apply {
                addCategory(Intent.CATEGORY_DEFAULT)
                addCategory(Intent.CATEGORY_BROWSABLE)
                addDataScheme("http")
                addDataScheme("https")
            },
        )
        val service = ComponentName(browser, "$browser.CustomTabs")
        pm.addServiceIfNotPresent(service)
        pm.addIntentFilterForService(service, IntentFilter(CUSTOM_TABS_SERVICE))
    }

    private fun laptopClass(on: Boolean) =
        shadowOf(context.packageManager).setSystemFeature(PackageManager.FEATURE_PC, on)

    /** Opens the link from an Activity, as the About screen does, and returns what started. */
    private fun opened(): Intent {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        openLink(activity, url)
        return shadowOf(activity).nextStartedActivity
    }

    @Test fun theRuleSendsOnlyPhonesAndTabletsWithAProviderToACustomTab() {
        assertEquals(LinkTarget.CUSTOM_TAB, linkTargetFor(isLaptopClass = false, hasCustomTabsProvider = true))
        assertEquals(LinkTarget.BROWSER, linkTargetFor(isLaptopClass = false, hasCustomTabsProvider = false))
        assertEquals(LinkTarget.BROWSER, linkTargetFor(isLaptopClass = true, hasCustomTabsProvider = true))
        assertEquals(LinkTarget.BROWSER, linkTargetFor(isLaptopClass = true, hasCustomTabsProvider = false))
    }

    @Test fun onAPhoneOrTabletALinkOpensInACustomTab() {
        installCustomTabsBrowser()
        laptopClass(false)
        val intent = opened()
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(Uri.parse(url), intent.data)
        assertEquals(browser, intent.`package`)
        assertTrue("a Custom Tab carries the session extra", intent.hasExtra(SESSION_EXTRA))
    }

    @Test fun onALaptopClassDeviceALinkOpensAsANormalBrowserTab() {
        installCustomTabsBrowser()
        laptopClass(true)
        val intent = opened()
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(Uri.parse(url), intent.data)
        assertNull("the system's default browser picks it up", intent.`package`)
        assertFalse("not a Custom Tab", intent.hasExtra(SESSION_EXTRA))
    }

    @Test fun withNoCustomTabsProviderALinkFallsBackToTheBrowser() {
        laptopClass(false)
        val intent = opened()
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(Uri.parse(url), intent.data)
        assertNull(intent.`package`)
        assertFalse("not a Custom Tab", intent.hasExtra(SESSION_EXTRA))
    }

    /**
     * The ways a web link can leave the app, as they appear in source: a view intent however it
     * is built, the browser selector, Custom Tabs, Compose's URI handler, a WebView, auto-linked
     * text, and HTML links (fromHtml, URLSpan, LinkMovementMethod, an href in a string).
     */
    private fun webLinkMarkers(source: String): List<String> = listOf(
        "ACTION_VIEW", "android.intent.action.VIEW", "parseUri", "CustomTabs", "UriHandler",
        "openUri", "WebView", "loadUrl", "Linkify", "LinkAnnotation", "autoLink",
        "fromHtml", "URLSpan", "LinkMovementMethod", "href=", "CATEGORY_APP_BROWSER",
        "ACTION_SENDTO", "mailto:",
    ).filter { it in source }

    @Test fun theLinkGuardCatchesEveryWayAWebLinkCanLeaveTheApp() {
        val leaves = listOf(
            "context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))",
            "startActivity(Intent().setAction(ACTION_VIEW).setData(uri))",
            "val i = Intent(\"android.intent.action.VIEW\", uri)",
            "startActivity(Intent.parseUri(url, 0))",
            "CustomTabsIntent.Builder().build().launchUrl(context, uri)",
            "LocalUriHandler.current.openUri(url)",
            "val handler: UriHandler = LocalUriHandler.current",
            "WebView(context).loadUrl(url)",
            "Linkify.addLinks(text, Linkify.WEB_URLS)",
            "withLink(LinkAnnotation.Url(url)) { append(label) }",
            "<TextView android:autoLink=\"web\" />",
            "Text(AnnotatedString.fromHtml(stringResource(R.string.help_html)))",
            "view.text = Html.fromHtml(html, 0); view.movementMethod = LinkMovementMethod.getInstance()",
            "builder.setSpan(URLSpan(url), 0, n, 0)",
            "Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_BROWSER)",
            "Intent(Intent.ACTION_SENDTO, \"mailto:\".toUri())",
        )
        for (code in leaves) assertTrue(code, webLinkMarkers(code).isNotEmpty())
        val stays = listOf(
            "startActivity(Intent(context, MainActivity::class.java))",
            "Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)",
            "val preview = previewImage",
        )
        for (code in stays) assertEquals(code, emptyList<String>(), webLinkMarkers(code))
    }

    @Test fun everyWebLinkInTheAppGoesThroughOpenLink() {
        val src = listOf(File("src"), File("app/src")).first { File(it, "main").exists() }
        val sources = listOf("main", "debug").map { File(src, it) }.filter { it.exists() }
            .flatMap { root -> root.walk().filter { it.isFile && (it.extension == "kt" || it.extension == "xml") }.toList() }
            // Links.kt is the one opener; the manifest's <queries> entry names the Custom Tabs
            // service it looks up, and opens nothing.
            .filter { it.name != "Links.kt" && it.name != "AndroidManifest.xml" }
        assertTrue("found the app's sources", sources.any { it.name == "AboutSection.kt" })
        val others = sources.associate { it.path to webLinkMarkers(it.readText()) }.filterValues { it.isNotEmpty() }
        assertEquals("files that open a web link without openLink", emptyMap<String, List<String>>(), others)
    }

    @Test fun whenTheInAppTabFailsToOpenTheLinkFallsBackToTheBrowser() {
        installCustomTabsBrowser()
        laptopClass(false)
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val started = mutableListOf<Intent>()
        // A Custom Tab provider that is found but refuses the launch (disabled, or gone since).
        val refusing = object : ContextWrapper(activity) {
            override fun startActivity(intent: Intent, options: Bundle?) {
                if (intent.`package` != null) throw ActivityNotFoundException("no such tab")
                started += intent
            }
            override fun startActivity(intent: Intent) = startActivity(intent, null)
        }
        openLink(refusing, url)
        val intent = started.single()
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(Uri.parse(url), intent.data)
        assertNull(intent.`package`)
        assertFalse("not a Custom Tab", intent.hasExtra(SESSION_EXTRA))
    }

    private companion object {
        const val CUSTOM_TABS_SERVICE = "android.support.customtabs.action.CustomTabsService"
        const val SESSION_EXTRA = "android.support.customtabs.extra.SESSION"
    }
}
