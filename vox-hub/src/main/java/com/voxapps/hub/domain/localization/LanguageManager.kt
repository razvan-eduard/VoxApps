package com.voxapps.hub.domain.localization

import android.content.Context
import com.voxapps.hub.utils.Strings
import com.voxapps.logging.Logger
import org.json.JSONObject

/**
 * Ports vox-vision's/vox-notes' LanguageManager into Vox Hub: loads a flat key->text JSON from
 * `assets/translations/{lang}.json`, exposed via [com.voxapps.hub.ui.LocalLanguageManager].
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
            Logger.w("LanguageManager", "Language load failed for '$langCode': ${e.message}")
            if (langCode != Strings.Languages.DEFAULT) {
                loadLanguage(Strings.Languages.DEFAULT)
            }
        }
    }

    fun getString(key: String): String = translations[key] ?: key
}
