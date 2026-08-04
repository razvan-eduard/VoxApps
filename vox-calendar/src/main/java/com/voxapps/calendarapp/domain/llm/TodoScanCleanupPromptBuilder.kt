package com.voxapps.calendarapp.domain.llm

/**
 * Builds the prompt sent to Commander's generic LLM hook after Vision hands back raw OCR text from a
 * to-do list's own "Scan" action (see [LlmTasks.TODO_SCAN_CLEANUP]). The target list is already known
 * from UI context (baked into the task string), so — unlike [CalendarScanCleanupPromptBuilder] —
 * there's no calendar-layer or to-do-list-name question to ask; the reply still parses with
 * [CalendarEventParseResultParser] (with `kind` hardcoded to `"TODO"` here) so no second parser is
 * needed. One item per scan (v1) — a multi-item checklist photo isn't parsed into several records.
 */
object TodoScanCleanupPromptBuilder {
    fun build(rawText: String, languageCode: String): String = """
        The following text was extracted via OCR from a scanned to-do/checklist note — a handwritten
        reminder, a shopping list, a sticky note, or similar — and may contain formatting noise,
        line-break artifacts, misrecognized characters, or short garbled fragments picked up from
        clutter around the note. Identify the note's single main item and DISCARD anything that
        clearly isn't part of it.

        Extract exactly one short checklist item title from the note — if the note lists several
        things, pick the single most prominent one, not a combined summary of all of them.

        TECHNICAL CONSTRAINTS:
        - Respond in language: "$languageCode".
        - Output: Return ONLY raw JSON. No text, no markdown.
        - Format: {"title": "...", "kind": "TODO"}

        OCR text: $rawText
    """.trimIndent()
}
