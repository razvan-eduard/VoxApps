package com.voxapps.vision.domain.llm

import org.json.JSONObject

/**
 * Parses the LLM's cleaned-up OCR result. Tolerant of a missing/blank title — Vox Notes already
 * auto-titles from the note text when no title is supplied.
 */
object OcrCleanupResultParser {
    data class Cleaned(val title: String?, val text: String)

    fun parse(json: String): Cleaned? = try {
        val o = JSONObject(json)
        val text = o.optString("text").takeIf { it.isNotBlank() } ?: return null
        val title = o.optString("title").takeIf { it.isNotBlank() }
        Cleaned(title = title, text = text)
    } catch (e: Exception) {
        null
    }
}
