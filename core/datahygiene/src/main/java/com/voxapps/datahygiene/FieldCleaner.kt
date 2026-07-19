package com.voxapps.datahygiene

import com.voxapps.logging.Logger

private const val TAG = "FieldCleaner"

/**
 * The one shared predicate every satellite's LLM-reply parser and [RecordSanitizer] implementation
 * should route nullable/required string fields through, instead of reinventing it per app (which is
 * exactly how this module came to exist — three independent, inconsistent reimplementations of "is
 * this string actually garbage" were found across vox-expenses/vox-calendar/vox-notes, one of them
 * with no guard at all).
 *
 * Treats all of the following as "no real value":
 *  - blank/whitespace-only
 *  - the literal string "null" (case-insensitive) — models sometimes emit this instead of a real
 *    JSON null; a bare `org.json.JSONObject.optString()` call doesn't catch it either way, since a
 *    genuine JSON null round-trips through `JSONObject.NULL.toString()` as the string "null" too —
 *    see [optCleanString] for the JSON-specific half of this fix
 *  - pure punctuation/whitespace with no actual letters or digits (e.g. ".", ";", "-")
 *
 * [fieldName]/[recordLabel] are optional, log-only context (e.g. "vendor" / "Expense#123") — they
 * never affect the cleaning decision, only what gets logged when a value is actually discarded.
 */
object FieldCleaner {

    /** Returns a trimmed, cleaned value, or null if [value] was garbage (see class doc for the rule). */
    fun clean(value: String?, fieldName: String? = null, recordLabel: String? = null): String? {
        val trimmed = value?.trim()
        if (trimmed.isNullOrEmpty()) return null
        if (isGarbage(trimmed)) {
            logDiscard(value, fieldName, recordLabel)
            return null
        }
        return trimmed
    }

    /**
     * Same predicate as [clean], but for a non-nullable String field (e.g. a required title) that
     * can't just become null — coerces to [fallback] instead.
     */
    fun cleanRequired(value: String, fallback: String = "", fieldName: String? = null, recordLabel: String? = null): String {
        return clean(value, fieldName, recordLabel) ?: fallback
    }

    /**
     * True only when [value] has real, non-blank content that [clean]/[cleanRequired] would discard
     * as garbage — NOT true just because a field is empty (that's normal, not "dirty"), and NOT true
     * for mere leading/trailing whitespace (trimming is silent, harmless normalization, not something
     * worth interrupting a save for). This is the signal a manual-UI save handler checks to decide
     * whether a confirmation dialog is even warranted.
     */
    fun isDirty(value: String?): Boolean = dirtyValue(value) != null

    /**
     * The actual garbage content [value] would be cleaned to null/[fallback], or null if [value]
     * isn't dirty. Callers use this (rather than just the [isDirty] boolean) to show the user exactly
     * what's wrong — e.g. surfacing `null` (the literal text) or `.` in a confirmation dialog instead
     * of a generic "some fields need cleanup" message.
     */
    fun dirtyValue(value: String?): String? {
        val trimmed = value?.trim()
        if (trimmed.isNullOrEmpty() || !isGarbage(trimmed)) return null
        return trimmed
    }

    private fun isGarbage(trimmed: String): Boolean =
        trimmed.equals("null", ignoreCase = true) || trimmed.none { it.isLetterOrDigit() }

    private fun logDiscard(original: String?, fieldName: String?, recordLabel: String?) {
        if (fieldName == null && recordLabel == null) return
        Logger.d(TAG, "Discarded garbage value for ${recordLabel ?: "?"}.${fieldName ?: "?"}: \"$original\"")
    }
}
