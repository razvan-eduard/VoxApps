package com.voxapps.calendarapp.domain.llm

import java.time.LocalDate

/**
 * Language-agnostic semantic prompt builder for spoken/typed calendar requests.
 * Uses semantic role labeling (verb, object, temporal role, target) before mapping
 * to calendar fields. Relative-date resolution is left entirely to the LLM, given
 * today's date as context (no dedicated date-NLU utility exists in this repo).
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
            You are a semantic parser for spoken calendar requests. Do NOT guess dates or times.
            First decompose the sentence into its semantic roles, THEN map those roles to calendar
            fields. Meaning drives the resolution, not the other way around. Today is $today (YYYY-MM-DD).

            STEP 1 — SEMANTIC ROLE LABELING:
            For the sentence, identify these roles (language-agnostic):
              - PREDICATE (verb): the intended action (meet, call, remind, pay). Confirms an entry.
              - THEME (object): WHAT the entry is about — becomes the title.
              - TEMPORAL EXPRESSION: the time phrase, and CRUCIALLY its role:
                  * SCHEDULED (event): happens at a specific clock time or spans a duration.
                  * DUE (task): an obligation to complete by a date, with no specific time.
                and its ANCHOR:
                  * RELATIVE ("tomorrow", "in a week", "next Monday") — resolve against Today.
                  * ABSOLUTE ("March 3", "on the 15th") — take as stated.
                and its BOUNDEDNESS:
                  * POINT (single moment/day) vs INTERVAL (has an explicit end).
              - TARGET (optional): the calendar/layer explicitly named, if any.

            STEP 2 — MAP ROLES TO FIELDS:
              - THEME -> title
              - TEMPORAL EXPRESSION:
                  * SCHEDULED -> type = "EVENT", startTime set, allDay = false
                  * DUE       -> type = "TASK", time fields omitted, allDay = true
                  * RELATIVE  -> resolve against Today BEFORE emitting startDate (NEVER emit unresolved)
                  * INTERVAL  -> include endDate/endTime; POINT -> omit them
              - TARGET -> layer = named calendar copied verbatim (NEVER translate/re-spell), else null

            STEP 3 — TIME RESOLUTION (only after roles are fixed):
              - Resolve every relative phrase relative to Today ($today).
              - NEVER emit a time that was not spoken; DATE-ONLY implies allDay = true.

            STEP 4 — TAGGING & CALENDAR: $layersLine Copy an existing calendar name exactly if the user
              named one, else set layer to null. Also suggest 0-3 short cross-cutting tags
              (e.g. "Medical", "Bills") based on content, or an empty array if nothing fits.

            ABSTRACT REASONING PATTERN (schema, not a literal case):
              Given any utterance of the form:
                [PREDICATE] [THEME] [TEMPORAL-EXPRESSION] [TARGET?]
              Resolve roles independently of specific words:
                - If TEMPORAL-EXPRESSION carries a CLOCK-TIME or DURATION
                      => type := EVENT; startTime := resolved time; allDay := false.
                - Else (DATE-ONLY)
                      => type := TASK; allDay := true; time fields omitted.
                - If ANCHOR is RELATIVE => resolve against Today before emitting startDate.
                - If BOUNDEDNESS is INTERVAL => emit endDate/endTime; else omit.
              Invariant: an unspoken time is NEVER fabricated, and a RELATIVE date is NEVER emitted unresolved.

            TECHNICAL CONSTRAINTS:
            - Respond in language: "$languageCode" (title only; dates/times use the formats below).
            - Output: Return ONLY raw JSON. No text, no markdown, no role labels.
            - Format: {"title": "...", "type": "EVENT"|"TASK", "startDate": "YYYY-MM-DD",
              "startTime": "HH:mm"|null, "endDate": "YYYY-MM-DD"|null, "endTime": "HH:mm"|null,
              "allDay": true|false, "layer": "..."|null, "tags": ["..."]}

            INPUT TEXT:
            $rawText
        """.trimIndent()
    }
}