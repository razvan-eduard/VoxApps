package com.voxapps.notes.domain.llm

/** A note as sent to the dedup prompt — just enough to judge duplication, not the full editable model. */
data class NoteSummary(val id: Long, val title: String?, val text: String)

/** Notes' text bodies are truncated to this many characters in the prompt to bound its size. */
private const val MAX_TEXT_CHARS = 300

/**
 * Builds the full prompt text sent to Commander's generic LLM hook for the note-deduplication
 * feature. Pure function, no Android deps. Unlike [CategoryMergePromptBuilder], no language parameter
 * is needed — the expected output is purely numeric ids, with no natural-language field to steer.
 *
 * Every note in [notes] is included in one shot (same characteristic as the category-merge prompt) —
 * for accounts with very many or very long notes this can grow large; not addressed here.
 */
object NoteDeduplicationPromptBuilder {
    fun build(notes: List<NoteSummary>): String {
        val notesBlock = notes.joinToString("\n") { note ->
            val title = note.title?.takeIf { it.isNotBlank() }?.let { "$it: " } ?: ""
            val text = note.text.take(MAX_TEXT_CHARS)
            "id=${note.id} | $title$text"
        }
        return """
            Evaluate the following list of notes and identify groups of notes that are genuinely
            redundant or near-duplicate — the same content repeated, or an old draft clearly
            superseded by a more complete/more recent one. Do NOT group notes just because they share a
            topic or category; only flag them if their content is clearly redundant. When in doubt,
            omit the note entirely rather than guessing.

            For each group, pick the single best note to keep (the most complete or most recent
            version) and list the rest as duplicates to remove. Return ONLY a JSON object of the shape
            {"groups": [{"keep": <id>, "duplicates": [<id>, ...]}]}, using the exact numeric ids given
            below — never invent an id. Omit notes that have no duplicates. No prose, no markdown.

            Notes:
            $notesBlock
        """.trimIndent()
    }
}
