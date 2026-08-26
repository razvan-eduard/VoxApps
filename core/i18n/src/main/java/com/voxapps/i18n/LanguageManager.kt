package com.voxapps.i18n

import android.content.Context
import com.voxapps.logging.Logger
import org.json.JSONObject

/** Where every app's translations live: `assets/translations/{lang}.json`, flat key → text. */
object Translations {
    const val DIR = "translations/"
    const val DIR_LIST = "translations"
    const val JSON_EXTENSION = ".json"
    const val DEFAULT_LANGUAGE = "en"
}

/**
 * The one translation loader every Vox app uses: a flat key→text JSON from
 * `assets/translations/{lang}.json`, exposed to composables through each app's
 * `LocalLanguageManager`. A key with no entry comes back as itself, so a missing translation shows
 * up on screen instead of vanishing.
 */
class LanguageManager(private val context: Context) {

    private var translations: Map<String, String> = emptyMap()
    private var language: String = Translations.DEFAULT_LANGUAGE

    fun loadLanguage(langCode: String) {
        language = langCode
        try {
            val fileName = "${Translations.DIR}$langCode${Translations.JSON_EXTENSION}"
            val json = context.assets.open(fileName).bufferedReader().use { it.readText() }
            loadFromJson(json, langCode)
        } catch (e: Exception) {
            Logger.w("LanguageManager", "Language load failed for '$langCode': ${e.message}")
            if (langCode != Translations.DEFAULT_LANGUAGE) {
                loadLanguage(Translations.DEFAULT_LANGUAGE)
            }
        }
    }

    /** The parse step of [loadLanguage], separated so tests can feed JSON without an asset dir. */
    internal fun loadFromJson(json: String, langCode: String) {
        language = langCode
        val obj = JSONObject(json)
        translations = obj.keys().asSequence().associateWith { obj.optString(it) }
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
        val suffixed = countedKey(key, count, usesLargeNumberForm)
        return String.format(translations[suffixed] ?: translations[key] ?: key, count)
    }

    /** Languages whose larger numbers take a form of their own — Romanian's "20 de înregistrări". */
    private val usesLargeNumberForm: Boolean get() = language.take(2) == "ro"

    companion object {
        /** [counted]'s form selection, pure so the plural rules are testable on their own. */
        internal fun countedKey(key: String, count: Int, usesLargeNumberForm: Boolean): String = when {
            count == 1 -> "${key}_one"
            usesLargeNumberForm && (count == 0 || count >= 20) -> "${key}_many"
            else -> key
        }
    }

    fun getAvailableLanguages(): List<String> = try {
        context.assets.list(Translations.DIR_LIST)
            ?.filter { it.endsWith(Translations.JSON_EXTENSION) }
            ?.map { it.replace(Translations.JSON_EXTENSION, "") }
            ?.sorted() ?: listOf(Translations.DEFAULT_LANGUAGE)
    } catch (e: Exception) {
        listOf(Translations.DEFAULT_LANGUAGE)
    }
}
