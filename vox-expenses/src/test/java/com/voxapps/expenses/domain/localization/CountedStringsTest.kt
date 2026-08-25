package com.voxapps.expenses.domain.localization

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * The count-carrying strings, checked against the files themselves rather than against a mock: the
 * failure this guards is a missing form in one language out of four, and only the real assets can
 * show that.
 */
class CountedStringsTest {

    private val languages = listOf("en", "ro", "de", "fr")

    private fun strings(language: String): Map<String, String> {
        val json = JSONObject(File("src/main/assets/translations/$language.json").readText())
        return json.keys().asSequence().associateWith { json.optString(it) }
    }

    private val counted = listOf(
        "selection_mode_count", "bulk_edit_title", "bulk_edit_done",
        "archive_confirm_title", "archive_done", "delete_forever_confirm_title"
    )

    @Test
    fun `every counted string has a singular in every language`() {
        for (language in languages) {
            val strings = strings(language)
            for (key in counted) {
                assertEquals("$language is missing $key", true, strings.containsKey(key))
                assertEquals("$language is missing ${key}_one", true, strings.containsKey("${key}_one"))
            }
        }
    }

    /** Romanian counts in three; the large-number form is not optional there. */
    @Test
    fun `romanian carries its large-number form too`() {
        val strings = strings("ro")
        for (key in counted) {
            assertEquals("ro is missing ${key}_many", true, strings.containsKey("${key}_many"))
        }
    }

    @Test
    fun `each form takes exactly one number`() {
        for (language in languages) {
            val strings = strings(language)
            for (key in counted) {
                for (form in listOf(key, "${key}_one", "${key}_many")) {
                    val text = strings[form] ?: continue
                    assertEquals("$language/$form", 1, Regex("%d").findAll(text).count())
                }
            }
        }
    }
}
