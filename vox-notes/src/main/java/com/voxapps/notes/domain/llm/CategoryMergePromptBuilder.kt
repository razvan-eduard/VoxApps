package com.voxapps.notes.domain.llm

/**
 * Builds the full prompt text sent to Commander's generic LLM hook for the category-merge feature.
 * Pure function, no Android deps — Commander only ever sees this composed [promptText], never the
 * raw category list separately (that's included in the request's `data` field only for logging/debug).
 */
object CategoryMergePromptBuilder {
    fun build(categoryNames: List<String>, languageCode: String): String = """
        Evaluate the following category list and return a flat JSON object mapping each redundant/
        duplicate category name directly to a single primary (canonical) category name — one key per
        duplicate, e.g. if the list contains "kumparaturi" and "Cumparaturi", return
        {"kumparaturi": "Cumparaturi"}. Do NOT group duplicates into arrays. The canonical name on the
        right-hand side MUST be copied verbatim, character-for-character, from the provided category
        list below — never invent a new spelling, translation, capitalization, or diacritics for it,
        even if you respond in a different language. Only include entries that should be merged — omit
        categories that have no duplicates. Respond in the "$languageCode" language. Return ONLY a
        JSON object, no prose, no markdown.

        Categories: ${categoryNames.joinToString(", ")}
    """.trimIndent()
}
