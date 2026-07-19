package com.voxapps.calendarapp.domain.llm

import java.time.LocalDate

/**
 * Builds the prompt sent to Commander's generic LLM hook after Vision hands back raw OCR text from
 * the "Scan an event" flow. Mirrors [CalendarEventParsePromptBuilder]'s field-mapping rules and JSON
 * output shape (so the reply still parses with [CalendarEventParseResultParser]), but the framing is
 * for a noisy scanned document — a flyer, printed invitation, appointment card, ticket — rather than
 * a clean spoken sentence (same "OCR noise" framing vox-notes'/vox-expenses' scan-cleanup builders
 * use for their own OCR text). A document with no date reference anywhere must omit "startDate",
 * which [CalendarEventParseResultParser] then discards the whole record for — this is the enforcement
 * point for "a calendar entry needs at least a date reference, direct or indirect," same as voice.
 */
object CalendarScanCleanupPromptBuilder {
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
            The following text was extracted via OCR from a scanned document — an event flyer,
            printed invitation, appointment card, ticket, or similar — and may contain formatting
            noise, line-break artifacts, misrecognized characters, or short garbled fragments picked
            up from clutter around the document. Identify the document's actual content and DISCARD
            anything that clearly isn't part of it, rather than trying to incorporate or make sense
            of it. Today is $today (YYYY-MM-DD).

            Extract a calendar entry from the document:
              - title: a short descriptive title for what the document is about.
              - type: "EVENT" if it has a specific time/date it happens at, "TASK" if it's a due-by
                obligation with no specific time.
              - startDate (YYYY-MM-DD): the date the document refers to. If the date is relative
                (e.g. printed alongside a known reference like "this Friday") resolve it against
                today's date; if the date is absolute (e.g. "March 3" or "15.03.2026"), take it as
                printed. CRITICAL: if the document contains NO date reference anywhere — direct
                (an explicit date) or indirect (a resolvable relative phrase) — omit "startDate"
                entirely (do not guess or fabricate one); a document with no date cannot become a
                calendar entry.
              - startTime (HH:mm, 24h) if a specific time is printed, else null. A date with no time
                printed implies allDay = true.
              - endDate/endTime if the document states an explicit end/duration, else null.
              - allDay: true if no specific time is printed for the start, false otherwise.
              - layer: $layersLine If one of the existing calendars fits the document's content, copy
                that name verbatim, character-for-character — never invent a new spelling,
                translation, capitalization, or diacritics for it. Otherwise null.
              - tags: 0-3 short cross-cutting tags (e.g. "Medical", "Travel") based on content, or an
                empty array if nothing fits.

            TECHNICAL CONSTRAINTS:
            - Respond in language: "$languageCode" (title only; dates/times use the formats above).
            - Output: Return ONLY raw JSON. No text, no markdown.
            - Format: {"title": "...", "type": "EVENT"|"TASK", "startDate": "YYYY-MM-DD"|omitted,
              "startTime": "HH:mm"|null, "endDate": "YYYY-MM-DD"|null, "endTime": "HH:mm"|null,
              "allDay": true|false, "layer": "..."|null, "tags": ["..."]}

            OCR text: $rawText
        """.trimIndent()
    }
}
