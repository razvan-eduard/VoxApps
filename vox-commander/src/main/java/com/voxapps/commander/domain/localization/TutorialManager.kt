package com.voxapps.commander.domain.localization

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import com.voxapps.logging.Logger

/**
 * Loads and provides tutorial content from tutorial_steps.json in assets.
 * Tutorial content is organized by language code, then by section, each with
 * title, optional paragraphs, and optional steps (element + title + description).
 */
class TutorialManager(private val context: Context) {
    private val gson = Gson()
    private var tutorialData: Map<String, JsonObject> = emptyMap()

    data class TutorialStep(
        val element: String,
        val title: String,
        val description: String
    )

    data class TutorialSection(
        val key: String,
        val title: String,
        val paragraphs: List<String> = emptyList(),
        val steps: List<TutorialStep> = emptyList()
    )

    fun load(langCode: String) {
        try {
            val type = object : TypeToken<Map<String, JsonObject>>() {}.type
            // `use`, not close() after fromJson — malformed JSON threw straight past close() and
            // leaked the reader (see LanguageManager for the same fix).
            tutorialData = context.assets.open("tutorial_steps.json").use { inputStream ->
                java.io.InputStreamReader(inputStream).use { reader ->
                    gson.fromJson(reader, type)
                }
            }
        } catch (e: Exception) {
            Logger.log("Tutorial load failed: ${e.message}", "TutorialManager")
            // Note: unlike LanguageManager, every language lives in this one file, so retrying as
            // "en" re-reads the exact same asset and fails identically — it only costs one extra
            // attempt, and is kept solely so behavior doesn't change alongside the leak fix.
            if (langCode != "en") load("en")
        }
    }

    fun getSections(langCode: String): List<TutorialSection> {
        val langData = tutorialData[langCode] ?: tutorialData["en"] ?: return emptyList()
        val sections = mutableListOf<TutorialSection>()

        for ((key, sectionJson) in langData.entrySet()) {
            val sectionObj = sectionJson.asJsonObject
            val title = sectionObj.get("title")?.asString ?: key
            val paragraphs = mutableListOf<String>()
            sectionObj.get("paragraphs")?.asJsonArray?.forEach { paragraphs.add(it.asString) }
            val steps = mutableListOf<TutorialStep>()
            sectionObj.get("steps")?.asJsonArray?.forEach { stepJson ->
                val stepObj = stepJson.asJsonObject
                steps.add(TutorialStep(
                    element = stepObj.get("element")?.asString ?: "",
                    title = stepObj.get("title")?.asString ?: "",
                    description = stepObj.get("description")?.asString ?: ""
                ))
            }
            sections.add(TutorialSection(key, title, paragraphs, steps))
        }
        return sections
    }
}
