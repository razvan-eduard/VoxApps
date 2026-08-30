package com.voxapps.datahygiene

/**
 * How much of an app's data its `OP_SYNC_EXPORT` volunteers to a paired device — a per-app,
 * per-device setting each satellite stores itself and reads in its own sync handler. The level
 * governs only what this device SENDS; what it accepts on merge is unaffected.
 *
 * - [MANUAL]: nothing leaves on its own. Only records the user explicitly pushed (the forced-uid
 *   list a push queue carries into the export command) are exported, and no tombstones travel —
 *   a pushed copy belongs to the receiving device, so a later local deletion is not its business.
 * - [SHARED]: records inside the containers the user shares with that peer (bank accounts for
 *   Expenses, categories for Notes, calendars for Calendar — the export command's scope names)
 *   replicate continuously, tombstones included.
 * - [ALL]: the whole data set replicates continuously, tombstones included — the
 *   two-phones-one-person case.
 */
enum class SyncLevel {
    MANUAL,
    SHARED,
    ALL;

    companion object {
        /** Lenient read of a stored/wire value — anything unrecognized falls back to [MANUAL],
         *  the level that sends nothing unasked. */
        fun fromStored(value: String?): SyncLevel =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: MANUAL
    }
}
