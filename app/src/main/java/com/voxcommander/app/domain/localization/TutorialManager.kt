package com.voxcommander.app.domain.localization

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import com.voxcommander.app.utils.Logger

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
            val inputStream = context.assets.open("tutorial_steps.json")
            val reader = java.io.InputStreamReader(inputStream)
            val type = object : TypeToken<Map<String, JsonObject>>() {}.type
            tutorialData = gson.fromJson(reader, type)
            reader.close()
        } catch (e: Exception) {
            Logger.log("Tutorial load failed: ${e.message}", "TutorialManager")
            // Fallback to English
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
