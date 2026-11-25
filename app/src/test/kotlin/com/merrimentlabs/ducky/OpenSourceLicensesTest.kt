package com.merrimentlabs.ducky

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The licenses screen is only as good as the generated res/raw/aboutlibraries.json:
 * every bundled library must be listed with at least one license, and every license
 * must carry its full text (Apache-2.0 §4 asks for the text, not a link).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OpenSourceLicensesTest {

    /** A file at the repository root (tests run in app/). */
    private fun repoFile(name: String): java.io.File =
        listOf(java.io.File("../$name"), java.io.File(name)).first { it.exists() }

    @Test fun ownLicenseIsTheRepositorysLicenseAndNotice() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val own = OpenSourceLicenses.load(context).own
        assertEquals("Apache License 2.0", own?.name)
        assertEquals(repoFile("LICENSE").readText().trim(), own!!.text.trim())
        assertEquals(repoFile("NOTICE").readText().trim(), context.getString(R.string.licenses_own_notice))
        assertEquals("Apache License 2.0", context.getString(R.string.licenses_own_license))
        // The Settings symbols are credited too.
        val symbols = OpenSourceLicenses.load(context).libraries.single { it.id == OpenSourceLicenses.MATERIAL_SYMBOLS_ID }
        assertEquals("Apache License 2.0", symbols.licenses.single().name)
    }

    @Test fun generatedFileListsTheAppsLibrariesWithFullLicenseText() {
        val libs = OpenSourceLicenses.load(ApplicationProvider.getApplicationContext()).libraries
        val ids = libs.map { it.id }.toSet()
        for (expected in listOf(
            "androidx.core:core",
            "androidx.compose.material3:material3",
            "androidx.glance:glance-appwidget",
            "androidx.datastore:datastore-preferences",
            "org.jetbrains.kotlin:kotlin-stdlib",
        )) assertTrue("missing $expected", expected in ids)
        // Test-only libraries never reach the app, so they never reach the screen.
        assertFalse(ids.any { it.startsWith("junit:") || it.startsWith("org.robolectric") })
        for (lib in libs) {
            assertTrue("${lib.id} has no license", lib.licenses.isNotEmpty())
            for (l in lib.licenses) assertTrue("${lib.id}: ${l.id} has no text", l.text.length > 1000)
        }
        // The protobuf runtimes carry protobuf's own notice, copyright line included.
        val proto = libs.first { it.id == "androidx.datastore:datastore-preferences-external-protobuf" }
        assertTrue(proto.licenses.single().text.startsWith("Copyright 2008 Google Inc."))
    }

    @Test fun parseSortsByNameAndSkipsUnknownLicenseIds() {
        val json = """
            {"libraries":[
              {"uniqueId":"b:b","name":"beta","artifactVersion":"2","licenses":["MIT","nope"]},
              {"uniqueId":"a:a","name":"Alpha","artifactVersion":"1","licenses":["MIT"]}],
             "licenses":{"MIT":{"name":"MIT License","content":"text"}}}
        """.trimIndent()
        val libs = OpenSourceLicenses.parse(json)
        assertEquals(listOf("Alpha", "beta"), libs.map { it.name })
        assertEquals(listOf("MIT License"), libs[1].licenses.map { it.name })
    }

    @Test fun reflowJoinsWrappedLinesButKeepsParagraphsAndBullets() {
        val text = "Copyright 2008 Google Inc.\n\nRedistribution is\n  permitted:\n\n" +
            "    * first\ncondition.\n    * second\ncondition.\n"
        assertEquals(
            "Copyright 2008 Google Inc.\n\nRedistribution is permitted:\n\n* first condition.\n* second condition.",
            OpenSourceLicenses.reflow(text)
        )
    }
}
