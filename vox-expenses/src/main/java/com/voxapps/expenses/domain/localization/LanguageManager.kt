package com.voxapps.expenses.domain.localization

import android.content.Context
import com.voxapps.expenses.utils.Strings
import com.voxapps.logging.Logger
import org.json.JSONObject

/**
 * Ports vox-notes' LanguageManager into Vox Expenses: loads a flat key->text JSON from
 * `assets/translations/{lang}.json`, exposed via `LocalLanguageManager`.
 */
class LanguageManager(private val context: Context) {

    private var translations: Map<String, String> = emptyMap()
    private var language: String = Strings.Languages.DEFAULT

    fun loadLanguage(langCode: String) {
        language = langCode
        try {
            val fileName = "${Strings.Translations.DIR}$langCode${Strings.Translations.JSON_EXTENSION}"
            val json = context.assets.open(fileName).bufferedReader().use { it.readText() }
            val obj = JSONObject(json)
            translations = obj.keys().asSequence().associateWith { obj.optString(it) }
        } catch (e: Exception) {
            Logger.w("LanguageManager", "Language load failed for '$langCode': ${e.message}")
            if (langCode != Strings.Languages.DEFAULT) {
                loadLanguage(Strings.Languages.DEFAULT)
            }
        }
    }

    fun getString(key: String): String = translations[key] ?: key

    /**
     * A line that carries a count, in the form that count takes.
     *
     * Looks for `key_one` at one and `key_many` where the language has a separate form for larger
     * numbers, falling back to [key] for everything else — so a string only needs the forms its
     * language actually distinguishes, and adding a language adds no code.
     *
     * Romanian is why this exists rather than a `%d` and a shrug: it counts in three, and "1
     * înregistrări" or "20 înregistrări" is wrong in a way a person reads as carelessness — in a
     * dialog that is about to destroy their records, of all places.
     */
    fun counted(key: String, count: Int): String {
        val suffixed = when {
            count == 1 -> "${key}_one"
            usesLargeNumberForm && (count == 0 || count >= 20) -> "${key}_many"
            else -> key
        }
        return String.format(translations[suffixed] ?: translations[key] ?: key, count)
    }

    /** Languages whose larger numbers take a form of their own — Romanian's "20 de înregistrări". */
    private val usesLargeNumberForm: Boolean get() = language.take(2) == "ro"

    fun getAvailableLanguages(): List<String> = try {
        context.assets.list(Strings.Translations.DIR_LIST)
            ?.filter { it.endsWith(Strings.Translations.JSON_EXTENSION) }
            ?.map { it.replace(Strings.Translations.JSON_EXTENSION, "") }
            ?.sorted() ?: listOf(Strings.Languages.DEFAULT)
    } catch (e: Exception) {
        listOf(Strings.Languages.DEFAULT)
    }
}
