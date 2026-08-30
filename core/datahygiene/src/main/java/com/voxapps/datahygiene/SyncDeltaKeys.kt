package com.voxapps.datahygiene

/**
 * JSON field names of the peer-to-peer sync delta — the envelope each app's `*SyncHandler` writes on
 * export and reads back on merge:
 *
 * ```
 * { "entries": [ { "uid": ..., "updatedAt": ... }, ... ],
 *   "tombstones": [ { "uid": ..., "deletedAt": ... }, ... ] }
 * ```
 *
 * These are a **wire contract between two devices**, not incidental strings: the writing side and the
 * reading side are separate code paths in separate apps, and until now both spelled every key as a
 * raw literal. A typo on one side produces no compile error and no runtime exception — the reader
 * simply finds nothing under the misspelled key and silently syncs zero records, which looks
 * identical to "the peer had no changes". Naming them here makes the two sides fail to compile
 * instead of failing quietly.
 *
 * vox-hub's `SyncOrchestrator` uses [EMPTY_DELTA] from here as well, so the empty case can't drift
 * from the populated one.
 */
object SyncDeltaKeys {
    /** Array of changed records. Each element's shape is app-specific beyond [UID]/[UPDATED_AT].
     *  Within an entry, a key carrying an explicit JSON null means "this field IS null" and
     *  overwrites on merge; an ABSENT key means "this exporter doesn't know the field" (an older
     *  build) and the merge keeps the local row's value — see each handler's field-preserve parse. */
    const val ENTRIES = "entries"

    /** Array of deletions to replay, so a delete propagates instead of the row being resurrected. */
    const val TOMBSTONES = "tombstones"

    /** Stable cross-device record identity — see [SyncIdentity]. Present on both entries and tombstones. */
    const val UID = "uid"

    /** Last-write-wins comparison key on an entry (see [planMerge]). */
    const val UPDATED_AT = "updatedAt"

    /** When the record was deleted, on a tombstone. */
    const val DELETED_AT = "deletedAt"

    /** Opaque continuation cursor (see [SyncPaging]) — present on a page that has more data after
     *  it; a page without it is the delta's last. The exporter mints it, only the same exporter
     *  reads it back; Hub just loops until it stops appearing. */
    const val NEXT_CURSOR = "nextCursor"

    /** Merge-result counters — every `OP_SYNC_MERGE` reply's [text] is `{"inserted":n,"updated":n,
     *  "deleted":n}` so Hub can sum a paged session into one per-app summary without scraping
     *  free-form prose. */
    const val INSERTED = "inserted"
    const val UPDATED = "updated"
    const val DELETED = "deleted"

    /** Scope-name sentinel for records that belong to no container — Expenses rows with no bank
     *  account (cash). Never shown to the user as-is: Hub's scope screen renders it under a
     *  localized label and sends the sentinel on the wire, so scope selections survive UI language
     *  changes. */
    const val SCOPE_NO_ACCOUNT = "__no_account__"

    /** A delta carrying no changes in either direction. */
    const val EMPTY_DELTA = """{"$ENTRIES":[],"$TOMBSTONES":[]}"""
}
