package com.voxapps.backup

/**
 * How an import reconciles imported rows against what's already on this device, for entity types
 * that support snapshot-replace semantics (leaf records like notes/expenses/calendar entries,
 * and Commander's FastMapRule). Reference-table entity types (categories/layers/todoLists) merge by
 * name unconditionally regardless of this setting — they're additive by construction and never went
 * through this importer at all.
 */
enum class VoxImportMode {
    /** Deletes every pre-existing row unconditionally, regardless of when it was created — the
     *  imported file becomes the complete truth for this entity type. */
    FULL_OVERRIDE,

    /** Deletes only pre-existing rows created at or before the export's own timestamp — the
     *  long-standing default. Anything created on this device after the backup was taken, but
     *  before this import ran, survives untouched. */
    MERGE,

    /** Never deletes anything — every imported row is inserted on top of whatever's already there.
     *  Can produce duplicates if the same file (or overlapping files) is imported more than once;
     *  that's an accepted trade-off of choosing this mode explicitly. */
    ADDITIVE;

    /** The reverse of [fromWireValue] — what to persist in a settings field or send over IPC. */
    val wireValue: String
        get() = when (this) {
            FULL_OVERRIDE -> "full_override"
            MERGE -> "merge"
            ADDITIVE -> "additive"
        }

    companion object {
        /**
         * Maps [com.voxapps.ipc.VoxCommand.importMode]'s wire string to this enum. Duplicates
         * `VoxIpc.IMPORT_MODE_*`'s literal values here rather than taking a dependency on
         * `:core:ipc` from this module — same reasoning as `HubSettings.IMPORT_MODE_*` duplicating
         * them too. Unrecognized or null values default to [MERGE], the long-standing behavior
         * before this field existed.
         */
        fun fromWireValue(value: String?): VoxImportMode = when (value) {
            "full_override" -> FULL_OVERRIDE
            "additive" -> ADDITIVE
            else -> MERGE
        }
    }
}

/**
 * The "restore a full-table snapshot" idiom shared by Notes/Expenses/Calendar/Commander's imports.
 * Deliberately does NOT cover merge-by-name-insert-only (categories/layers/todoLists),
 * merge-by-name-with-field-update (DuplicateRuleEntity), or upsert-by-key (MerchantCategoryMemory)
 * — those are genuinely different strategies, always additive regardless of [VoxImportMode], and
 * stay hand-written per app.
 */
object VoxSnapshotReplaceImporter {

    /**
     * Inserts every item in [imported] via [insert], then deletes a subset of [preExisting] (a
     * snapshot taken by the caller BEFORE this runs) per [mode] via [delete]:
     *  - [VoxImportMode.FULL_OVERRIDE]: every pre-existing item.
     *  - [VoxImportMode.MERGE]: only items whose [createdAtOf] is <= [exportedAt].
     *  - [VoxImportMode.ADDITIVE]: nothing.
     * Returns the count of items actually inserted ([insert] returning <= 0 counts as skipped,
     * matching every handler's existing "continue on invalid item" contract).
     */
    suspend fun <TImported, TExisting> restore(
        mode: VoxImportMode,
        imported: List<TImported>,
        preExisting: List<TExisting>,
        exportedAt: Long = 0L,
        createdAtOf: (TExisting) -> Long = { 0L },
        insert: suspend (TImported) -> Long,
        delete: suspend (TExisting) -> Unit
    ): Int {
        var count = 0
        for (item in imported) if (insert(item) > 0) count++
        val toDelete = when (mode) {
            VoxImportMode.FULL_OVERRIDE -> preExisting
            VoxImportMode.MERGE -> preExisting.filter { createdAtOf(it) <= exportedAt }
            VoxImportMode.ADDITIVE -> emptyList()
        }
        toDelete.forEach { delete(it) }
        return count
    }
}
