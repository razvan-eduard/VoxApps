package com.voxapps.datahygiene

/**
 * Contract each satellite's data layer implements once per entity type (Expense, CalendarEntry,
 * Note, ...) so [planMerge] can reconcile a local list against a peer-to-peer sync delta without
 * knowing anything about the concrete entity shape — same "one small interface per entity type,
 * shared scan logic underneath" convention as [DuplicateChecker].
 */
interface SyncIdentity<T> {
    /** Stable across devices (each entity's own `uid` field) — never the local Room `id`, which is
     *  a per-device auto-increment sequence with no meaning on another phone. */
    fun uidOf(record: T): String

    /** Bumped on every field-level edit — the last-write-wins tiebreaker on a uid collision. */
    fun updatedAtOf(record: T): Long
}

/** What a peer-to-peer sync merge decided to do with a `local` list, given a `remoteEntries` delta
 *  and a set of uids the peer tombstoned since the last sync. Callers own actually writing
 *  [toInsert]/[toUpdate]/[toDeleteUids] to their own Room DB — this module has no DB dependency. */
data class SyncMergePlan<T>(
    val toInsert: List<T>,
    val toUpdate: List<T>,
    val toDeleteUids: List<String>
)

/**
 * Insert-if-new / last-write-wins-by-updatedAt / delete-on-tombstone — the one merge algorithm every
 * satellite's `OP_SYNC_MERGE` handler runs, so Notes/Expenses/Calendar don't each reimplement the
 * same uid/updatedAt/tombstone logic. Pure and side-effect-free.
 *
 * - A remote entry whose uid isn't in [local] is a straight insert.
 * - A remote entry whose uid IS present locally only wins if its `updatedAt` is strictly newer — a
 *   tie or an older remote value is silently ignored (the peer will self-correct on its own next
 *   sync, since both sides exchange deltas symmetrically in the same session).
 * - A remote tombstone only deletes a uid that's still present locally — deleting something already
 *   gone (or never synced here at all) is a silent no-op, not an error.
 * - A tombstone wins over an update for the same uid within the same delta (an edit-after-delete
 *   race across two independent syncs is a known, accepted v1 limitation).
 */
fun <T> SyncIdentity<T>.planMerge(
    local: List<T>,
    remoteEntries: List<T>,
    remoteTombstoneUids: Set<String>
): SyncMergePlan<T> {
    val localByUid = local.associateBy { uidOf(it) }
    val toInsert = mutableListOf<T>()
    val toUpdate = mutableListOf<T>()
    for (remote in remoteEntries) {
        val uid = uidOf(remote)
        if (uid in remoteTombstoneUids) continue
        val existing = localByUid[uid]
        when {
            existing == null -> toInsert += remote
            updatedAtOf(remote) > updatedAtOf(existing) -> toUpdate += remote
        }
    }
    val toDeleteUids = remoteTombstoneUids.filter { it in localByUid.keys }
    return SyncMergePlan(toInsert, toUpdate, toDeleteUids)
}
