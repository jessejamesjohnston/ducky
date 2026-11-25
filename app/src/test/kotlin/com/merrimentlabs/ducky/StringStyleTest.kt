package com.merrimentlabs.ducky

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The wording rules every string in res/values/strings.xml follows, checked on the text a
 * person sees (XML entities and Android escapes decoded), across <string>, <plurals> and
 * <string-array>:
 *
 * - A single sentence has no closing period. A block of two or more sentences keeps them.
 * - A trailing ellipsis only marks something in progress ("Ringing…"), never a label that
 *   opens a dialog or a screen.
 * - Two parts are joined with a middle dot ( · ) or a comma, never an em dash, an en dash or
 *   a semicolon.
 * - American spelling.
 * - Natural contractions ("can't", "it's"), unless the words sit inside quotation marks.
 * - Lists of three or more take a serial comma. That check only prints a warning: it cannot
 *   tell a list from a phrase that happens to contain "and".
 *
 * Sentence case and plain wording are left to review. An entry may break a rule only through
 * [ALLOWED], and every entry there says why.
 */
class StringStyleTest {

    /** One translatable piece of text: a <string>, or one item of a <plurals> or <string-array>. */
    data class Entry(val name: String, val text: String)

    private enum class Rule { SINGLE_SENTENCE_PERIOD, ELLIPSIS, DASH_OR_SEMICOLON_JOIN, UK_SPELLING, UNCONTRACTED }

    private companion object {
        /** Exceptions, keyed by rule and resource name. Each value is the reason. */
        val ALLOWED: Map<Pair<Rule, String>, String> = mapOf(
            (Rule.SINGLE_SENTENCE_PERIOD to "app_tagline") to
                "The tagline is quoted word for word, period included, wherever Ducky is described, " +
                "so the About screen shows it the same way.",
        )

        val UK_SPELLINGS = Regex(
            "\\b(cancell(ed|ing)|colour(s|ed|ful|ing)?|favourites?|behaviours?|" +
                "centre[sd]?|organis(e|ed|es|ing|ation)|recognis(e|ed|es|ing|able))\\b",
            RegexOption.IGNORE_CASE,
        )
        val UNCONTRACTED = Regex(
            "\\b(do not|cannot|will not|you are|it is|does not|is not)\\b",
            RegexOption.IGNORE_CASE,
        )
        /** Text inside straight or curly double quotes, which may be someone else's words. */
        val QUOTED = Regex("\"[^\"]*\"|“[^”]*”")
        val SENTENCE_END = Regex("[.!?](?=\\s|$)")
        val SERIAL_COMMA_MISSING = Regex("\\w[^,.;:·]*, [^,.;:·]+ (and|or) \\w")
    }

    /** Undoes Android's string escapes and its optional surrounding double quotes. */
    private fun decodeAndroid(raw: String): String {
        var s = raw.trim()
        if (s.length >= 2 && s.first() == '"' && s.last() == '"') s = s.substring(1, s.length - 1)
        s = Regex("\\\\u([0-9a-fA-F]{4})").replace(s) { it.groupValues[1].toInt(16).toChar().toString() }
        val out = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                out.append(
                    when (val n = s[i + 1]) {
                        'n' -> '\n'
                        't' -> '\t'
                        else -> n
                    },
                )
                i += 2
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }

    /** Every translatable entry in a strings file, decoded. XML entities are decoded by the parser. */
    private fun entries(file: java.io.File): List<Entry> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val out = mutableListOf<Entry>()
        val children = doc.documentElement.childNodes
        for (i in 0 until children.length) {
            val el = children.item(i) as? Element ?: continue
            if (el.getAttribute("translatable") == "false") continue
            val name = el.getAttribute("name")
            when (el.tagName) {
                "string" -> out += Entry(name, decodeAndroid(el.textContent))
                "plurals", "string-array" -> {
                    val items = el.getElementsByTagName("item")
                    for (j in 0 until items.length) {
                        val item = items.item(j) as Element
                        val key = item.getAttribute("quantity").ifEmpty { j.toString() }
                        out += Entry("$name[$key]", decodeAndroid(item.textContent))
                    }
                }
            }
        }
        return out
    }

    /** The rules [text] breaks, with a short note for each. */
    private fun violations(text: String): Map<Rule, String> {
        val found = linkedMapOf<Rule, String>()
        val t = text.trim()
        val sentences = SENTENCE_END.findAll(t).count()
        if (t.endsWith(".") && !t.endsWith("...") && sentences == 1) {
            found[Rule.SINGLE_SENTENCE_PERIOD] = "a single sentence ends in a period"
        }
        val ellipsis = when {
            t.endsWith("…") -> t.dropLast(1)
            t.endsWith("...") -> t.dropLast(3)
            else -> null
        }
        if (ellipsis != null && !ellipsis.trimEnd().substringAfterLast(' ').endsWith("ing", ignoreCase = true)) {
            found[Rule.ELLIPSIS] = "a trailing ellipsis on something that is not in progress"
        }
        if ('—' in t || '–' in t || Regex(";\\s").containsMatchIn(t)) {
            found[Rule.DASH_OR_SEMICOLON_JOIN] = "an em dash, en dash or semicolon join (use · or a comma)"
        }
        UK_SPELLINGS.find(t)?.let { found[Rule.UK_SPELLING] = "UK spelling \"${it.value}\"" }
        val unquoted = QUOTED.replace(t.replace('’', '\''), "")
        UNCONTRACTED.find(unquoted)?.let { found[Rule.UNCONTRACTED] = "\"${it.value}\" reads better contracted" }
        return found
    }

    private fun stringsFile(): java.io.File =
        listOf(
            java.io.File("src/main/res/values/strings.xml"),
            java.io.File("app/src/main/res/values/strings.xml"),
        ).first { it.exists() }

    @Test fun everyStringFollowsTheWordingRules() {
        val all = entries(stringsFile())
        assertTrue("strings.xml parsed to ${all.size} entries", all.size > 100)
        assertTrue("plurals items are read", all.any { it.name.startsWith("duration_minutes[") })
        assertTrue("string-array items are read", all.any { it.name.startsWith("preset_color_names[") })

        val problems = mutableListOf<String>()
        val used = mutableSetOf<Pair<Rule, String>>()
        for (e in all) {
            for ((rule, note) in violations(e.text)) {
                val key = rule to e.name
                if (key in ALLOWED) used += key else problems += "${e.name}: $note: \"${e.text}\""
            }
            if (SERIAL_COMMA_MISSING.containsMatchIn(e.text)) {
                println("WARNING ${e.name}: a list of three or more may be missing its serial comma: \"${e.text}\"")
            }
        }
        val stale = ALLOWED.keys - used
        if (stale.isNotEmpty()) problems += "allowlist entries no string needs any more: $stale"
        if (problems.isNotEmpty()) fail("Strings that break the wording rules:\n" + problems.joinToString("\n"))
    }

    @Test fun everyAllowlistEntryNamesAStringAndSaysWhy() {
        val names = entries(stringsFile()).map { it.name }.toSet()
        for ((key, reason) in ALLOWED) {
            assertTrue("$key names a string in strings.xml", key.second in names)
            assertTrue("$key gives a reason", reason.isNotBlank())
        }
    }

    @Test fun theRulesCatchWhatTheyShould() {
        fun rules(text: String) = violations(text).keys
        assertEquals(setOf(Rule.SINGLE_SENTENCE_PERIOD), rules("Allow notifications so the alarm can show."))
        assertEquals(setOf(Rule.ELLIPSIS), rules("Custom color…"))
        assertEquals(setOf(Rule.ELLIPSIS), rules("Edit name..."))
        assertEquals(setOf(Rule.DASH_OR_SEMICOLON_JOIN), rules("Working offline — using saved data"))
        assertEquals(setOf(Rule.DASH_OR_SEMICOLON_JOIN), rules("Paused – tap to resume"))
        assertEquals(setOf(Rule.DASH_OR_SEMICOLON_JOIN), rules("Paused; tap to resume"))
        for (uk in listOf("Sign-in cancelled", "Pick a colour", "Favourites", "Odd behaviour", "Centre it", "Organise", "Recognised")) {
            assertEquals(uk, setOf(Rule.UK_SPELLING), rules(uk))
        }
        for (long in listOf("Do not stop", "You cannot miss it", "It will not ring", "You are set", "It is set", "It does not ring", "This is not set")) {
            assertEquals(long, setOf(Rule.UNCONTRACTED), rules(long))
        }
    }

    @Test fun theRulesLeaveGoodTextAlone() {
        for (good in listOf(
            "Ringing…",
            "Syncing...",
            "Open-source notices",
            "Apache License 2.0",
            "Custom color · tap to change",
            "Pick a look. Every Ducky widget shows the same timer.",
            "Copyright © 2026 Jesse Johnston. Published by Merriment Labs.",
            "Add the %1\$s widget. %2\$s.",
            "Pick a color",
            "Colors",
            "Canceled",
            "Don't stop, it's set, you're done",
            "The sign says \"Do not enter\"",
            "The sign says “It is closed”",
        )) {
            assertEquals(good, emptyMap<Rule, String>(), violations(good))
        }
    }

    @Test fun decodingUndoesAndroidEscapes() {
        assertEquals("Time's up", decodeAndroid("Time\\'s up"))
        assertEquals("a\nb", decodeAndroid("a\\nb"))
        assertEquals("…", decodeAndroid("\\u2026"))
        assertEquals("  padded  ", decodeAndroid("\"  padded  \""))
        assertFalse(decodeAndroid("\\\"quoted\\\"").contains('\\'))
    }
}
