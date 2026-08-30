package com.voxapps.notes.receiver

import com.voxapps.datahygiene.SyncDeltaKeys
import com.voxapps.datahygiene.SyncIdentity
import com.voxapps.datahygiene.SyncLevel
import com.voxapps.datahygiene.SyncPaging
import com.voxapps.datahygiene.planMerge
import com.voxapps.ipc.VoxCommand
import com.voxapps.ipc.VoxResult
import com.voxapps.notes.data.Note
import com.voxapps.notes.data.NotesRepository
import com.voxapps.notes.data.preferences.NotesSettings
import com.voxapps.notes.data.preferences.NotesSettingsRepository
import com.voxapps.notes.state.SessionManager
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import com.voxapps.design.color.VoxColorPalette

/**
 * Vox Hub's peer-to-peer sync for this app (see [VoxIpc.OP_SYNC_EXPORT]/[VoxIpc.OP_SYNC_MERGE]) —
 * deliberately separate from [NotesExportImportHandler], which is a one-directional *restore*.
 * This is a *delta* merge: pages of entries changed since a watermark, reconciled via
 * [com.voxapps.datahygiene.planMerge]'s insert-if-new / last-write-wins / delete-on-tombstone
 * algorithm, never a blind overwrite.
 *
 * What the export volunteers is governed by [NotesSettings.syncLevel] plus the per-peer category
 * scope the command carries (see [SyncLevel] for the three rungs); records the user explicitly
 * pushed ([VoxCommand.uids]) travel at every rung. Deltas are paged ([SyncPaging]) so a large first
 * sync crosses the binder boundary in bounded pieces.
 *
 * On the wire, every field the entity has travels, and the category travels by NAME (a local Room
 * id has no meaning on another phone). A key holding an explicit JSON null means "this field IS
 * null" and overwrites; an ABSENT key means the sending build never knew the field, and the merge
 * keeps the local row's value — so an older peer's narrower delta can't blank fields it never
 * heard of.
 *
 * Rows a merge INSERTS are stamped with the sending device's identity
 * ([VoxCommand.sourceDeviceId]/[VoxCommand.sourceDeviceName]) as their provenance; an update never
 * rewrites an existing row's stamp — where a record came from doesn't change when it's edited.
 */
class NotesSyncHandler(
    private val settingsRepo: NotesSettingsRepository,
    private val sessionManager: SessionManager,
    private val notesRepo: NotesRepository,
    private val lockedMessage: String
) {
    suspend fun export(command: VoxCommand): VoxResult {
        val settings = settingsRepo.getSnapshot()
        val locked = settings.isBiometricRequired &&
            !sessionManager.isSessionValid(settings.sessionTimeoutMinutes)
        if (locked) return VoxResult(ok = false, text = lockedMessage)

        val level = NotesSettings.syncLevelOf(settings.syncLevel)
        val since = command.since ?: 0L
        // null = everything, empty = nothing — the wire contract; see VoxCommand.scopeNames.
        val scopeSet = command.scopeNames?.map { it.lowercase() }?.toSet()

        val categoryNameById = notesRepo.categories.first().associate { it.id to it.name }
        val all = notesRepo.notesSnapshot()
        val continuous = when (level) {
            SyncLevel.MANUAL -> emptyList()
            SyncLevel.ALL -> all.filter { it.updatedAt > since }
            SyncLevel.SHARED -> all.filter { note ->
                note.updatedAt > since && inCategoryScope(note, scopeSet, categoryNameById)
            }
        }
        val forcedUids = command.uids?.toSet().orEmpty()
        val forced = if (forcedUids.isEmpty()) emptyList() else all.filter { it.uid in forcedUids }
        val candidates = (continuous + forced).distinctBy { it.uid }
        // At MANUAL a pushed copy belongs to the receiving device — a later local deletion is not
        // its business, so no tombstones travel.
        val tombstones = if (level == SyncLevel.MANUAL) emptyList() else notesRepo.tombstonesSince(since)

        val page = SyncPaging.page(
            candidates, tombstones, command.cursor, command.limit,
            entryKey = { SyncPaging.Key(it.updatedAt, it.uid) },
            tombstoneKey = { SyncPaging.Key(it.deletedAt, it.uid) }
        )

        val json = JSONObject()
        json.put(
            SyncDeltaKeys.ENTRIES,
            JSONArray(page.entries.map { it.toSyncJson(it.categoryId?.let { id -> categoryNameById[id] }) })
        )
        json.put(SyncDeltaKeys.TOMBSTONES, JSONArray(page.tombstones.map {
            JSONObject().put(SyncDeltaKeys.UID, it.uid).put(SyncDeltaKeys.DELETED_AT, it.deletedAt)
        }))
        page.nextCursor?.let { json.put(SyncDeltaKeys.NEXT_CURSOR, it) }
        return VoxResult(ok = true, text = json.toString())
    }

    /** A note with no category belongs to no shareable container: it is in scope only when the
     *  scope is "everything" (null), never on an explicit scope list. */
    private fun inCategoryScope(
        note: Note,
        scopeSet: Set<String>?,
        categoryNameById: Map<Long, String>
    ): Boolean {
        if (scopeSet == null) return true
        val name = note.categoryId?.let { categoryNameById[it] } ?: return false
        return name.lowercase() in scopeSet
    }

    suspend fun merge(command: VoxCommand): VoxResult {
        val settings = settingsRepo.getSnapshot()
        val locked = settings.isBiometricRequired &&
            !sessionManager.isSessionValid(settings.sessionTimeoutMinutes)
        if (locked) return VoxResult(ok = false, text = lockedMessage)

        val root = try {
            JSONObject(command.text.orEmpty())
        } catch (e: Exception) {
            return VoxResult(ok = false, text = "Invalid sync payload")
        }

        val local = notesRepo.notesSnapshot()
        val localByUid = local.associateBy { it.uid }

        // Same auto-create-by-name convention NotesExportImportHandler.import() already uses.
        // Categories are fetched once per merge, not per-entry.
        val existingCategories = notesRepo.categories.first().toMutableList()
        val nameToId = existingCategories.associate { it.name.lowercase() to it.id }.toMutableMap()
        suspend fun categoryIdFor(name: String): Long? =
            nameToId[name.lowercase()] ?: run {
                val newId = notesRepo.addCategory(
                    name,
                    VoxColorPalette.unusedOrRandomColor(existingCategories.map { it.colorArgb }),
                    existingCategories.size,
                    System.currentTimeMillis()
                )
                if (newId > 0) nameToId[name.lowercase()] = newId
                newId.takeIf { it > 0 }
            }

        val entriesJson = root.optJSONArray(SyncDeltaKeys.ENTRIES) ?: JSONArray()
        val remoteEntries = mutableListOf<Note>()
        for (i in 0 until entriesJson.length()) {
            val e = entriesJson.getJSONObject(i)
            val localRow = localByUid[e.optString(SyncDeltaKeys.UID)]
            val categoryId = when {
                !e.has("categoryName") -> localRow?.categoryId
                e.isNull("categoryName") -> null
                else -> categoryIdFor(e.getString("categoryName"))
            }
            remoteEntries += e.toNote(localRow, categoryId).let {
                // Provenance is stamped exactly once, at insert; an update keeps the local stamp
                // (toNote already copied it from localRow).
                if (localRow == null) it.copy(
                    originDeviceId = command.sourceDeviceId,
                    originDeviceName = command.sourceDeviceName
                ) else it
            }
        }

        val tombstonesJson = root.optJSONArray(SyncDeltaKeys.TOMBSTONES) ?: JSONArray()
        val remoteTombstoneUids = (0 until tombstonesJson.length())
            .map { tombstonesJson.getJSONObject(it).optString(SyncDeltaKeys.UID) }
            .toSet()

        val plan = NoteSyncIdentity.planMerge(local, remoteEntries, remoteTombstoneUids)

        for (note in plan.toInsert) notesRepo.insertSyncedNote(note)
        for (note in plan.toUpdate) {
            val localId = notesRepo.getIdByUid(note.uid) ?: continue
            notesRepo.updateSyncedNote(note.copy(id = localId))
        }
        for (uid in plan.toDeleteUids) notesRepo.deleteNoteByUid(uid)

        return VoxResult(
            ok = true,
            text = JSONObject()
                .put(SyncDeltaKeys.INSERTED, plan.toInsert.size)
                .put(SyncDeltaKeys.UPDATED, plan.toUpdate.size)
                .put(SyncDeltaKeys.DELETED, plan.toDeleteUids.size)
                .toString()
        )
    }
}

private object NoteSyncIdentity : SyncIdentity<Note> {
    override fun uidOf(record: Note): String = record.uid
    override fun updatedAtOf(record: Note): Long = record.updatedAt
}

/** Every nullable field is written as an explicit JSON null rather than omitted — on this wire,
 *  null and absent mean different things (see the class doc comment). */
private fun Note.toSyncJson(categoryName: String?): JSONObject = JSONObject().apply {
    put(SyncDeltaKeys.UID, uid)
    putNullable("title", title)
    put("text", text)
    putNullable("textHtml", textHtml)
    putNullable("categoryName", categoryName)
    put("isStub", isStub)
    put("createdAt", createdAt)
    put(SyncDeltaKeys.UPDATED_AT, updatedAt)
}

/**
 * The entry as a full local entity: keys the delta carries overwrite, keys it lacks fall back to
 * [local]'s values (a fresh insert falls back to the entity's own defaults). The resolved category
 * id comes in from the caller because resolving it needs the repository.
 */
private fun JSONObject.toNote(local: Note?, categoryId: Long?): Note = Note(
    uid = optString(SyncDeltaKeys.UID),
    title = stringOr("title") { local?.title },
    text = if (has("text")) optString("text") else local?.text ?: "",
    textHtml = stringOr("textHtml") { local?.textHtml },
    createdAt = if (has("createdAt")) optLong("createdAt") else local?.createdAt ?: System.currentTimeMillis(),
    categoryId = categoryId,
    updatedAt = optLong(SyncDeltaKeys.UPDATED_AT),
    isStub = if (has("isStub")) optBoolean("isStub", false) else local?.isStub ?: false,
    originDeviceId = local?.originDeviceId,
    originDeviceName = local?.originDeviceName
)

private fun JSONObject.putNullable(key: String, value: Any?) {
    put(key, value ?: JSONObject.NULL)
}

private inline fun JSONObject.stringOr(key: String, fallback: () -> String?): String? = when {
    !has(key) -> fallback()
    isNull(key) -> null
    else -> optString(key)
}
