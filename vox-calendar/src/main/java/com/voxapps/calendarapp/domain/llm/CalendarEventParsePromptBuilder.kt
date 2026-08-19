package com.voxapps.calendarapp.domain.llm

import com.voxapps.ipc.VoxSatelliteSchema
import java.time.LocalDate

/**
 * Language-agnostic semantic prompt builder for spoken/typed calendar requests.
 * Uses semantic role labeling (verb, object, temporal role, target) before mapping
 * to calendar fields. Relative-date resolution is left entirely to the LLM, given
 * today's date as context (no dedicated date-NLU utility exists in this repo).
 */
object CalendarEventParsePromptBuilder {
    /**
     * The question, with [VoxSatelliteSchema.INPUT_PLACEHOLDER] where the utterance goes.
     *
     * One shape for both routes: [com.voxapps.ipc.VoxIpc.OP_GET_SCHEMA] hands it to Commander to
     * cache and fill in locally per command, and when this app is handed the words instead its own
     * flow substitutes them the same way.
     */
    fun buildTemplate(
        existingLayers: List<String>,
        existingTodoLists: List<String>,
        languageCode: String,
        today: LocalDate = LocalDate.now()
    ): String = buildPrompt(VoxSatelliteSchema.INPUT_PLACEHOLDER, existingLayers, existingTodoLists, languageCode, today)

    private fun buildPrompt(
        rawText: String,
        existingLayers: List<String>,
        existingTodoLists: List<String>,
        languageCode: String,
        today: LocalDate
    ): String {
        val layersLine = if (existingLayers.isEmpty()) {
            "No calendars exist yet."
        } else {
            "Existing calendars: ${existingLayers.joinToString(", ")}."
        }
        val todoListsLine = if (existingTodoLists.isEmpty()) {
            "No to-do lists exist yet."
        } else {
            "Existing to-do lists: ${existingTodoLists.joinToString(", ")}."
        }

        return """
            You are a semantic parser for spoken calendar/to-do requests. Do NOT guess dates or times.
            First decompose the sentence into its semantic roles, THEN map those roles to fields.
            Meaning drives the resolution, not the other way around. Today is $today (YYYY-MM-DD).

            STEP 1 — SEMANTIC ROLE LABELING:
            For the sentence, identify these roles (language-agnostic):
              - PREDICATE (verb): the intended action (meet, call, remind, pay, add). Confirms a record.
              - THEME (object): WHAT the record is about — becomes the title.
              - TEMPORAL EXPRESSION: the time phrase, and CRUCIALLY its role:
                  * SCHEDULED (event): happens at a specific clock time or spans a duration.
                  * DUE (task): an obligation to complete by a date, with no specific time.
                  * UNSCHEDULED (to-do/checklist item): NO date reference at all, direct or relative —
                    an open-ended thing to do eventually, not tied to any particular day (distinct from
                    DUE, which still names a deadline date even without a clock time).
                and, for SCHEDULED/DUE, its ANCHOR:
                  * RELATIVE ("tomorrow", "in a week", "next Monday") — resolve against Today.
                  * ABSOLUTE ("March 3", "on the 15th") — take as stated.
                and its BOUNDEDNESS:
                  * POINT (single moment/day) vs INTERVAL (has an explicit end).
              - TARGET (optional): the calendar/layer explicitly named, if any.
              - LIST (optional): the to-do list explicitly named, if any (only relevant for UNSCHEDULED).

            STEP 2 — MAP ROLES TO FIELDS:
              - THEME -> title
              - TEMPORAL EXPRESSION:
                  * SCHEDULED    -> kind = "EVENT", startTime set, allDay = false
                  * DUE          -> kind = "TASK", time fields omitted, allDay = true
                  * UNSCHEDULED  -> kind = "TODO", startDate omitted entirely (do not guess one)
                  * RELATIVE (SCHEDULED/DUE only) -> resolve against Today BEFORE emitting startDate
                    (NEVER emit unresolved)
                  * INTERVAL -> include endDate/endTime; POINT -> omit them
              - TARGET -> layer = named calendar copied verbatim (NEVER translate/re-spell), else null
              - LIST -> listName = named to-do list copied verbatim (NEVER translate/re-spell), else null

            STEP 3 — TIME RESOLUTION (only after roles are fixed):
              - Resolve every relative phrase relative to Today ($today).
              - NEVER emit a time that was not spoken; DATE-ONLY implies allDay = true.

            STEP 4 — TAGGING & TARGETS: $layersLine $todoListsLine Copy an existing calendar/to-do-list
              name exactly if the user named one, else set that field to null. Also suggest 0-3 short
              cross-cutting tags (e.g. "Medical", "Bills") based on content, or an empty array if
              nothing fits.

            ABSTRACT REASONING PATTERN (schema, not a literal case):
              Given any utterance of the form:
                [PREDICATE] [THEME] [TEMPORAL-EXPRESSION?] [TARGET?] [LIST?]
              Resolve roles independently of specific words:
                - If TEMPORAL-EXPRESSION carries a CLOCK-TIME or DURATION
                      => kind := EVENT; startTime := resolved time; allDay := false.
                - Else if TEMPORAL-EXPRESSION carries a DATE with no clock time
                      => kind := TASK; allDay := true; time fields omitted.
                - Else (no date reference of any kind)
                      => kind := TODO; startDate omitted entirely.
                - If ANCHOR is RELATIVE => resolve against Today before emitting startDate.
                - If BOUNDEDNESS is INTERVAL => emit endDate/endTime; else omit.
              Invariant: an unspoken time is NEVER fabricated, and a RELATIVE date is NEVER emitted unresolved.

            TECHNICAL CONSTRAINTS:
            - Respond in language: "$languageCode" (title only; dates/times use the formats below).
            - Output: Return ONLY raw JSON. No text, no markdown, no role labels.
            - Format: {"title": "...", "kind": "EVENT"|"TASK"|"TODO", "startDate": "YYYY-MM-DD"|omitted,
              "startTime": "HH:mm"|null, "endDate": "YYYY-MM-DD"|null, "endTime": "HH:mm"|null,
              "allDay": true|false, "layer": "..."|null, "listName": "..."|null, "tags": ["..."]}
              ("startDate" may be omitted entirely when kind is "TODO" and no date was said.)

            INPUT TEXT:
            $rawText
        """.trimIndent()
    }
}