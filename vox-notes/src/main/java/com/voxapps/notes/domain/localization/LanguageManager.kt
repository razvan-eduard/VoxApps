package com.voxapps.notes.domain.localization

import android.content.Context
import android.util.Log
import com.voxapps.notes.utils.Strings
import org.json.JSONObject

/**
 * Ports vox-commander's LanguageManager into Vox Notes: loads a flat key->text JSON from
 * `assets/translations/{lang}.json`, exposed via [LocalLanguageManager]. Uses org.json (not Gson,
 * which Notes doesn't otherwise depend on) since it's just a flat Map<String, String> load.
 */
class LanguageManager(private val context: Context) {

    private var translations: Map<String, String> = emptyMap()

    fun loadLanguage(langCode: String) {
        try {
            val fileName = "${Strings.Translations.DIR}$langCode${Strings.Translations.JSON_EXTENSION}"
            val json = context.assets.open(fileName).bufferedReader().use { it.readText() }
            val obj = JSONObject(json)
            translations = obj.keys().asSequence().associateWith { obj.optString(it) }
        } catch (e: Exception) {
            Log.w("LanguageManager", "Language load failed for '$langCode': ${e.message}")
            if (langCode != Strings.Languages.DEFAULT) {
                loadLanguage(Strings.Languages.DEFAULT)
            }
        }
    }

    fun getString(key: String): String = translations[key] ?: key

    fun getAvailableLanguages(): List<String> = try {
        context.assets.list(Strings.Translations.DIR_LIST)
            ?.filter { it.endsWith(Strings.Translations.JSON_EXTENSION) }
            ?.map { it.replace(Strings.Translations.JSON_EXTENSION, "") }
            ?.sorted() ?: listOf(Strings.Languages.DEFAULT)
    } catch (e: Exception) {
        listOf(Strings.Languages.DEFAULT)
    }
}
