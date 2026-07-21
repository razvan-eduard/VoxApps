package com.voxapps.notes.receiver

import com.voxapps.datahygiene.SyncIdentity
import com.voxapps.datahygiene.planMerge
import com.voxapps.ipc.VoxResult
import com.voxapps.notes.data.CategoryPalette
import com.voxapps.notes.data.Note
import com.voxapps.notes.data.NotesRepository
import com.voxapps.notes.data.preferences.NotesSettingsRepository
import com.voxapps.notes.state.SessionManager
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject

/**
 * Vox Hub's peer-to-peer sync for this app (see [VoxIpc.OP_SYNC_EXPORT]/[VoxIpc.OP_SYNC_MERGE]) —
 * deliberately separate from [NotesExportImportHandler], which is a one-directional *restore* (wipe
 * pre-existing rows, insert a full snapshot verbatim). This is a *delta* merge: only entries changed
 * since a watermark, reconciled via [com.voxapps.datahygiene.planMerge]'s insert-if-new /
 * last-write-wins / delete-on-tombstone algorithm, never a blind overwrite. Categories travel by
 * name, not id (a local Room sequence has no meaning on another phone) — mirrors
 * [vox.expenses.receiver.ExpensesSyncHandler]'s identical shape.
 */
class NotesSyncHandler(
    private val settingsRepo: NotesSettingsRepository,
    private val sessionManager: SessionManager,
    private val notesRepo: NotesRepository
) {
    suspend fun export(since: Long, scopeNames: List<String>?): VoxResult {
        val settings = settingsRepo.getSnapshot()
        val locked = settings.isBiometricRequired &&
            !sessionManager.isSessionValid(settings.sessionTimeoutMinutes)
        if (locked) return VoxResult(ok = false, text = NotesReadResponder.LOCKED_MESSAGE)

        val categoryNameById = notesRepo.categories.first().associate { it.id to it.name }
        val scopeSet = scopeNames?.takeIf { it.isNotEmpty() }?.map { it.lowercase() }?.toSet()

        val changed = notesRepo.notesSnapshot()
            .filter { it.updatedAt > since }
            .filter { note ->
                if (scopeSet == null) return@filter true
                val name = note.categoryId?.let { categoryNameById[it] } ?: return@filter false
                name.lowercase() in scopeSet
            }
        val tombstones = notesRepo.tombstonesSince(since)

        val json = JSONObject()
        json.put("entries", JSONArray(changed.map { it.toSyncJson(it.categoryId?.let { id -> categoryNameById[id] }) }))
        json.put("tombstones", JSONArray(tombstones.map { JSONObject().put("uid", it.uid).put("deletedAt", it.deletedAt) }))
        return VoxResult(ok = true, text = json.toString())
    }

    suspend fun merge(deltaJson: String): VoxResult {
        val settings = settingsRepo.getSnapshot()
        val locked = settings.isBiometricRequired &&
            !sessionManager.isSessionValid(settings.sessionTimeoutMinutes)
        if (locked) return VoxResult(ok = false, text = NotesReadResponder.LOCKED_MESSAGE)

        val root = try {
            JSONObject(deltaJson)
        } catch (e: Exception) {
            return VoxResult(ok = false, text = "Invalid sync payload")
        }

        val existingCategories = notesRepo.categories.first().toMutableList()
        val nameToId = existingCategories.associate { it.name.lowercase() to it.id }.toMutableMap()

        val entriesJson = root.optJSONArray("entries") ?: JSONArray()
        val remoteEntries = (0 until entriesJson.length()).map { i ->
            val e = entriesJson.getJSONObject(i)
            val categoryName = e.optNullableString("categoryName")
            val categoryId = categoryName?.let { name ->
                nameToId[name.lowercase()] ?: run {
                    val newId = notesRepo.addCategory(
                        name,
                        CategoryPalette.unusedOrRandomColor(existingCategories.map { it.colorArgb }),
                        existingCategories.size,
                        System.currentTimeMillis()
                    )
                    if (newId > 0) nameToId[name.lowercase()] = newId
                    newId.takeIf { it > 0 }
                }
            }
            e.toNote(categoryId)
        }
        val tombstonesJson = root.optJSONArray("tombstones") ?: JSONArray()
        val remoteTombstoneUids = (0 until tombstonesJson.length())
            .map { tombstonesJson.getJSONObject(it).optString("uid") }
            .toSet()

        val local = notesRepo.notesSnapshot()
        val plan = NoteSyncIdentity.planMerge(local, remoteEntries, remoteTombstoneUids)

        for (note in plan.toInsert) notesRepo.insertSyncedNote(note)
        for (note in plan.toUpdate) {
            val localId = notesRepo.getIdByUid(note.uid) ?: continue
            notesRepo.updateSyncedNote(note.copy(id = localId))
        }
        for (uid in plan.toDeleteUids) notesRepo.deleteNoteByUid(uid)

        return VoxResult(
            ok = true,
            text = "${plan.toInsert.size} inserted, ${plan.toUpdate.size} updated, ${plan.toDeleteUids.size} deleted"
        )
    }
}

private object NoteSyncIdentity : SyncIdentity<Note> {
    override fun uidOf(record: Note): String = record.uid
    override fun updatedAtOf(record: Note): Long = record.updatedAt
}

private fun Note.toSyncJson(categoryName: String?): JSONObject = JSONObject().apply {
    put("uid", uid)
    put("title", title)
    put("text", text)
    put("categoryName", categoryName)
    put("createdAt", createdAt)
    put("updatedAt", updatedAt)
}

private fun JSONObject.toNote(categoryId: Long?): Note = Note(
    uid = optString("uid"),
    title = optNullableString("title"),
    text = optString("text"),
    categoryId = categoryId,
    createdAt = optLong("createdAt"),
    updatedAt = optLong("updatedAt")
)

private fun JSONObject.optNullableString(key: String): String? =
    if (has(key) && !isNull(key)) optString(key) else null
