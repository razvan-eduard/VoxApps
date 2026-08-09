package com.voxapps.commander.domain.localization

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.voxapps.logging.Logger
import com.voxapps.commander.utils.Strings
import java.io.InputStreamReader

class LanguageManager(private val context: Context) {
    private var translations: Map<String, String> = emptyMap()
    private val gson = Gson()

    fun loadLanguage(langCode: String) {
        try {
            val fileName = "${Strings.Translations.DIR}$langCode${Strings.Translations.JSON_EXTENSION}"
            val type = object : TypeToken<Map<String, String>>() {}.type
            // `use`, not close() after fromJson — malformed JSON throws out of fromJson and the old
            // form never reached close(), leaking the reader. Worse here than usual because the
            // catch below then opens a second one for the fallback language.
            translations = context.assets.open(fileName).use { inputStream ->
                InputStreamReader(inputStream).use { reader ->
                    gson.fromJson(reader, type)
                }
            }
        } catch (e: Exception) {
            Logger.log("Language load failed for '$langCode': ${e.message}", "LanguageManager")
            // Fallback to default English if loading fails
            if (langCode != Strings.Languages.DEFAULT) {
                loadLanguage(Strings.Languages.DEFAULT)
            }
        }
    }

    fun getString(key: String): String {
        return translations[key] ?: key
    }

    fun getAvailableLanguages(): List<String> {
        return try {
            val list = context.assets.list(Strings.Translations.DIR_LIST)
            list?.filter { it.endsWith(Strings.Translations.JSON_EXTENSION) }
                ?.map { (it as String).replace(Strings.Translations.JSON_EXTENSION, "") }
                ?.sorted() ?: listOf(Strings.Languages.DEFAULT)
        } catch (e: Exception) {
            listOf(Strings.Languages.DEFAULT)
        }
    }
}
