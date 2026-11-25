package com.merrimentlabs.ducky

import android.content.Context
import org.json.JSONObject

/**
 * The libraries bundled in this build and their licenses, read from
 * `res/raw/aboutlibraries.json`. The AboutLibraries Gradle plugin writes that file
 * for each variant from the runtime classpath (app/build.gradle.kts), so a release
 * build lists exactly what ships. Parsed with the platform's org.json: no reflection,
 * nothing for R8 to strip, and the direct `R.raw` reference keeps resource shrinking
 * from dropping the file.
 */
object OpenSourceLicenses {

    data class License(val id: String, val name: String, val text: String)

    data class Library(
        val id: String,
        val name: String,
        val version: String,
        val licenses: List<License>,
    )

    /** What the licenses screen shows: Ducky's own license, then the bundled libraries. */
    class Notices(val own: License?, val libraries: List<Library>)

    /** Ducky's own license: Apache-2.0, the same text as the repository's LICENSE file. */
    const val OWN_LICENSE_ID = "Apache-2.0"

    fun load(context: Context): Notices {
        val json = context.resources.openRawResource(R.raw.aboutlibraries)
            .bufferedReader().use { it.readText() }
        val apache = parseLicenses(json)[OWN_LICENSE_ID]
        // Not a Gradle dependency, so the plugin doesn't list it: the Settings symbols (help,
        // mail, shield, document, person, code, gavel, new releases, open in new) are Material
        // Symbols drawables, and back, settings and the notification's timer are Material Icons
        // from the same repository, all Apache-2.0.
        val symbols = Library(
            id = MATERIAL_SYMBOLS_ID, name = "Material Symbols", version = "",
            licenses = listOfNotNull(apache),
        )
        val libs = (parse(json) + symbols).sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
        return Notices(apache, libs)
    }

    const val MATERIAL_SYMBOLS_ID = "github.com/google/material-design-icons"

    /** Every license text in the file, by id (the bundled libraries' Apache-2.0 is Ducky's too). */
    fun parseLicenses(json: String): Map<String, License> {
        val licenseObjects = JSONObject(json).optJSONObject("licenses") ?: JSONObject()
        return buildMap {
            for (id in licenseObjects.keys()) {
                val l = licenseObjects.getJSONObject(id)
                put(id, License(id, l.optString("name", id), l.optString("content", "")))
            }
        }
    }

    /** Libraries sorted by name; license ids the file doesn't define are skipped. */
    fun parse(json: String): List<Library> {
        val root = JSONObject(json)
        val licenses = parseLicenses(json)
        val libs = root.optJSONArray("libraries") ?: return emptyList()
        return (0 until libs.length()).map { i ->
            val lib = libs.getJSONObject(i)
            val ids = lib.optJSONArray("licenses")
            val id = lib.getString("uniqueId")
            Library(
                id = id,
                name = lib.optString("name").ifBlank { id },
                version = lib.optString("artifactVersion"),
                licenses = (0 until (ids?.length() ?: 0)).mapNotNull { licenses[ids!!.getString(it)] },
            )
        }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    }

    /**
     * License files are hard-wrapped at ~78 columns, which wraps raggedly on a phone.
     * Joins the lines of each paragraph (bullets keep their own line) and keeps the
     * blank lines between paragraphs. Words and their order are unchanged.
     */
    fun reflow(text: String): String =
        text.replace("\r\n", "\n")
            .split(Regex("\n\\s*\n"))
            .map { paragraph ->
                paragraph.lines().map(String::trim).filter(String::isNotEmpty)
                    .fold(StringBuilder()) { out, line ->
                        if (out.isNotEmpty()) {
                            out.append(if (line.startsWith("* ") || line.startsWith("- ")) "\n" else " ")
                        }
                        out.append(line)
                    }.toString()
            }
            .filter(String::isNotEmpty)
            .joinToString("\n\n")
}
