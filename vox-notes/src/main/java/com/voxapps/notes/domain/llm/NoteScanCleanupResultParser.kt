package com.voxapps.notes.domain.llm

import org.json.JSONObject

/**
 * Parses the LLM's cleaned-up scan result. Tolerant of a missing/blank title/category — Notes
 * already auto-titles from note text when no title is supplied, and resolves a missing/unmatched
 * category via the same fallback/auto-create path voice notes use (see
 * VoiceCategoryResolver/addVoiceNote).
 */
object NoteScanCleanupResultParser {
    data class Cleaned(val title: String?, val category: String?, val text: String)

    fun parse(json: String): Cleaned? = try {
        val o = JSONObject(json)
        val text = o.optString("text").takeIf { it.isNotBlank() } ?: return null
        val title = o.optString("title").takeIf { it.isNotBlank() }
        val category = o.optString("category").takeIf { it.isNotBlank() }
        Cleaned(title = title, category = category, text = text)
    } catch (e: Exception) {
        null
    }
}
