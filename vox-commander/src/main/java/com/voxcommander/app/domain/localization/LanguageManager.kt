package com.voxcommander.app.domain.localization

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.voxcommander.app.utils.Logger
import com.voxcommander.app.utils.Strings
import java.io.InputStreamReader

class LanguageManager(private val context: Context) {
    private var translations: Map<String, String> = emptyMap()
    private val gson = Gson()

    fun loadLanguage(langCode: String) {
        try {
            val fileName = "${Strings.Translations.DIR}$langCode${Strings.Translations.JSON_EXTENSION}"
            val inputStream = context.assets.open(fileName)
            val reader = InputStreamReader(inputStream)
            val type = object : TypeToken<Map<String, String>>() {}.type
            translations = gson.fromJson(reader, type)
            reader.close()
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
