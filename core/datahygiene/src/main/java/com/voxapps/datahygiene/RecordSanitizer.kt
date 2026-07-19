package com.voxapps.datahygiene

/** A single garbage field found on a record: which field, and the actual offending text — surfaced
 *  so a confirmation dialog can show the user exactly what's wrong instead of a generic message. */
data class DirtyField(val fieldKey: String, val value: String)

/**
 * Contract each satellite's data layer implements once per entity type (Expense, CalendarEntry,
 * Note, ...): given a record, know how to clean it and which fields (if any) need cleaning. This is
 * the "virtual class every DB-operation class implements" — the only per-app code is which fields
 * a given entity has and what to call it in logs; the actual cleaning predicate underneath
 * ([FieldCleaner]) and the source-based branching ([decideForSave]) are shared and identical
 * everywhere.
 */
interface RecordSanitizer<T> {
    /** Returns a cleaned copy of [record] (garbage fields nulled/coerced via [FieldCleaner]). */
    fun sanitize(record: T): T

    /** Every field on [record] that [FieldCleaner] would treat as garbage, with its raw offending
     *  value. Empty means [record] is clean (or blank fields only — blank isn't dirty). */
    fun dirtyFields(record: T): List<DirtyField>
}

/** Where a record about to be saved came from — determines whether/how it gets cleaned. */
enum class RecordSource {
    /** Voice, notification capture, receipt/document scan, or any other LLM-derived extraction.
     *  Always silently auto-cleaned before insert. */
    LLM,

    /** Vox Hub backup/restore. NEVER cleaned — this is another VoxApps install's already-validated
     *  data; silently rewriting it on import would be a data-integrity bug of its own, and would
     *  hide real problems in the source rather than surface them. */
    HUB_IMPORT,

    /** A human typed this into an edit screen. Never auto-cleaned silently — if it looks dirty, the
     *  UI layer must ask via [SaveDecision.ConfirmCleanup] rather than silently rewriting what the
     *  user typed. */
    MANUAL_UI
}

/** What a caller should do with a record before actually saving it. */
sealed interface SaveDecision<T> {
    /** Save [record] as-is (already clean, already sanitized, or explicitly exempt like import). */
    data class Proceed<T>(val record: T) : SaveDecision<T>

    /** [original] has one or more fields that look like garbage (see [dirtyFields]) — the caller
     *  must ask the user before saving: accept auto-clean (call [RecordSanitizer.sanitize] and save
     *  that), or cancel and let them fix it manually. */
    data class ConfirmCleanup<T>(val original: T, val dirtyFields: List<DirtyField>) : SaveDecision<T>
}

/**
 * The one shared wrapper embodying "auto-clean if LLM, do nothing if Hub import, confirm if manual
 * UI" — so this three-way branch isn't reimplemented per app, only the [RecordSanitizer] itself is.
 */
fun <T> RecordSanitizer<T>.decideForSave(record: T, source: RecordSource): SaveDecision<T> = when (source) {
    RecordSource.LLM -> SaveDecision.Proceed(sanitize(record))
    RecordSource.HUB_IMPORT -> SaveDecision.Proceed(record)
    RecordSource.MANUAL_UI -> {
        val dirty = dirtyFields(record)
        if (dirty.isNotEmpty()) SaveDecision.ConfirmCleanup(record, dirty) else SaveDecision.Proceed(record)
    }
}
