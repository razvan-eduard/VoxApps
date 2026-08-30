package com.voxapps.notes.domain.llm

/**
 * Builds the prompt sent to Commander's generic LLM hook after Vision hands back raw OCR text from
 * the "Scan a note" flow. Mirrors CategoryMergePromptBuilder's shape. The suggested category is
 * resolved through the exact same path voice notes already use
 * (NotesRepository.addVoiceNote -> VoiceCategoryResolver) — the LLM's job here is only to guess a
 * name, not to decide the final category.
 */
object NoteScanCleanupPromptBuilder {
    /** The same question with the text left out, for the transport that supplies it — the voice
     *  flow's promptTemplate. [build] is this with the text put in, so the two can never drift. */
    fun buildTemplate(existingCategories: List<String>, languageCode: String): String {
        val categoriesLine = if (existingCategories.isEmpty()) {
            "No categories exist yet."
        } else {
            "Existing categories: ${existingCategories.joinToString(", ")}."
        }
        return """
            The following text was extracted via OCR from a scanned document or receipt and may
            contain formatting noise, line-break artifacts, or misrecognized characters. It may also
            contain short, garbled, or out-of-context fragments picked up from clutter behind or
            around the document (stray words, random letters, unrelated labels) — identify the main
            document's actual content and DISCARD anything that clearly isn't part of it, rather than
            trying to incorporate or make sense of it. Clean it up into a well-formatted note: infer a
            short descriptive title, and reformat the body as clear paragraphs or bullet points where
            appropriate, without inventing information that isn't present in the source text. Also
            suggest a category for this note based on its content (e.g. a receipt -> a shopping-related
            category). $categoriesLine If one of the existing categories fits, copy that name verbatim,
            character-for-character — never invent a new spelling, translation, capitalization, or
            diacritics for it. Only suggest a new category name if none of the existing ones fit.
            Respond in the "$languageCode" language. Return ONLY a JSON object of the shape
            {"title": "...", "category": "...", "text": "..."}, no prose, no markdown.

            OCR text: ${com.voxapps.ipc.VoxSatelliteSchema.INPUT_PLACEHOLDER}
        """.trimIndent()
    }

    fun build(rawText: String, existingCategories: List<String>, languageCode: String): String =
        buildTemplate(existingCategories, languageCode)
            .replace(com.voxapps.ipc.VoxSatelliteSchema.INPUT_PLACEHOLDER, rawText)
}
