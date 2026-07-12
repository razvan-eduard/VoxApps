package com.voxapps.calendarapp.domain.llm

import java.time.LocalDate

/**
 * Builds the prompt sent to Commander's generic LLM hook to turn a raw spoken/typed utterance
 * (passed through verbatim by Commander's NLU as `text` — see the satellite's `nluHint`) into a
 * structured calendar entry. Mirrors vox-expenses' `ExpenseParsePromptBuilder` in shape. No dedicated
 * date-NLU utility exists anywhere in this repo — relative-date resolution ("in a week", "next
 * Monday") is left entirely to the LLM, given today's date as context, consistent with Commander's own
 * pure-LLM NLU philosophy.
 */
object CalendarEventParsePromptBuilder {
    fun build(
        rawText: String,
        existingLayers: List<String>,
        languageCode: String,
        today: LocalDate = LocalDate.now()
    ): String {
        val layersLine = if (existingLayers.isEmpty()) {
            "No calendars exist yet."
        } else {
            "Existing calendars: ${existingLayers.joinToString(", ")}."
        }
        return """
            The following text is a spoken or typed request to add something to a calendar, possibly
            containing recognition noise. Today's date is $today (YYYY-MM-DD). Extract it into a
            structured calendar entry: infer a short title, and decide whether it's an "EVENT" (has a
            specific time or duration) or a "TASK" (a to-do with a due date, no specific time).
            Resolve any relative date/time phrases ("tomorrow", "in a week", "next Monday", "at 3pm")
            into an absolute date and, if a time was mentioned, an absolute time, using today's date
            above as the reference point. If no time was mentioned, set "allDay" to true and omit the
            time fields. If an end date/time was mentioned, include "endDate"/"endTime"; otherwise omit
            them. $layersLine If the user explicitly names a target calendar (e.g. "add to my Work
            calendar", "pe calendarul de acasă"), copy that calendar's name verbatim,
            character-for-character — never invent a new spelling, translation, capitalization, or
            diacritics for it; set "layer" to null if no calendar was named, rather than guessing one.
            Also suggest 0-3 short cross-cutting tags based on the content (e.g. "Medical", "Bills") —
            return an empty array if nothing fits. Respond in the "$languageCode" language for the
            title only; keep all dates/times in the exact formats specified below. Return ONLY a JSON
            object of the shape {"title": "...", "type": "EVENT"|"TASK", "startDate": "YYYY-MM-DD",
            "startTime": "HH:mm"|null, "endDate": "YYYY-MM-DD"|null, "endTime": "HH:mm"|null,
            "allDay": true|false, "layer": "..."|null, "tags": ["..."]}, no prose, no markdown.

            Request: $rawText
        """.trimIndent()
    }
}
