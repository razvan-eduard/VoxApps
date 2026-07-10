package com.voxapps.vision.domain.llm

/**
 * Builds the full prompt text sent to Commander's generic LLM hook for the OCR-cleanup feature.
 * Pure function, no Android deps — mirrors vox-notes' CategoryMergePromptBuilder shape.
 */
object OcrCleanupPromptBuilder {
    fun build(rawText: String, languageCode: String): String = """
        The following text was extracted via OCR from a scanned document or receipt and may contain
        formatting noise, line-break artifacts, or misrecognized characters. Clean it up into a
        well-formatted note: infer a short descriptive title, and reformat the body as clear
        paragraphs or bullet points where appropriate, without inventing information that isn't
        present in the source text. Respond in the "$languageCode" language. Return ONLY a JSON
        object of the shape {"title": "...", "text": "..."}, no prose, no markdown.

        OCR text: $rawText
    """.trimIndent()
}
