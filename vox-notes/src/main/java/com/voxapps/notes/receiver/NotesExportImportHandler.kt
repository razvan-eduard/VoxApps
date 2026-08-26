package com.voxapps.notes.receiver

import android.content.Context
import com.voxapps.attachments.AttachmentDao
import com.voxapps.attachments.restoreFromBackup
import com.voxapps.attachments.toBackupJson
import com.voxapps.backup.VoxBiometricGate
import com.voxapps.backup.VoxExportImportHandler
import com.voxapps.backup.mergeByName
import com.voxapps.backup.optStringOrNull
import com.voxapps.backup.VoxImportMode
import com.voxapps.backup.VoxSettingsRoundTrip
import com.voxapps.backup.VoxSnapshotReplaceImporter
import com.voxapps.notes.data.Category
import com.voxapps.notes.data.NotesAttachments
import com.voxapps.notes.data.NotesRepository
import com.voxapps.notes.data.preferences.NotesSettings
import com.voxapps.notes.data.preferences.NotesSettingsRepository
import com.voxapps.notes.state.SessionManager
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject

/**
 * Vox Hub's export/import for this app, extracted from the BroadcastReceiver so it's unit-testable
 * without Android (mirrors [NotesReadResponder]). Respects the same biometric-lock gate as reads —
 * an export/import request while the app is locked never touches the DB.
 */
class NotesExportImportHandler(
    context: Context,
    private val settingsRepo: NotesSettingsRepository,
    private val sessionManager: SessionManager,
    private val notesRepo: NotesRepository,
    private val attachmentDao: AttachmentDao,
    override val lockedMessage: String
) : VoxExportImportHandler(context, NotesAttachments.DIR, NotesAttachments.FILE_PROVIDER_AUTHORITY) {

    override suspend fun isLocked(): Boolean {
        val settings = settingsRepo.getSnapshot()
        return VoxBiometricGate.isLocked(
            settings.isBiometricRequired, settings.sessionTimeoutMinutes, sessionManager::isSessionValid
        )
    }

    override suspend fun exportSettings(): JSONObject = settingsRepo.getSnapshot().toJson()

    override suspend fun restoreSettings(settings: JSONObject) =
        settingsRepo.restoreSettings(settings.toNotesSettings())

    override suspend fun exportData(json: JSONObject): List<String> {
        val categories = notesRepo.categories.first()
        val notes = notesRepo.notesSnapshot()
        json.put("categories", JSONArray(categories.map { it.toJson() }))
        val allFileNames = mutableListOf<String>()
        json.put(
            "notes",
            JSONArray(
                notes.map { note ->
                    val attachments = attachmentDao.getFor(NotesAttachments.RECORD_TYPE, note.id)
                    allFileNames += attachments.map { it.fileName }
                    JSONObject().apply {
                        put("id", note.id)
                        put("title", note.title)
                        put("text", note.text)
                        put("textHtml", note.textHtml)
                        put("createdAt", note.createdAt)
                        put("categoryId", note.categoryId)
                        put("attachments", JSONArray(attachments.map { it.toBackupJson() }))
                    }
                }
            )
        )
        return allFileNames
    }

    override suspend fun importData(root: JSONObject, exportedAt: Long, mode: VoxImportMode): String {
        val importedCategories = root.optJSONArray("categories") ?: JSONArray()
        val merge = mergeByName(
            imported = importedCategories,
            existing = notesRepo.categories.first(),
            nameOf = { it.name },
            idOf = { it.id },
            importedNameOf = { it.optString("name") },
            create = { c, name ->
                notesRepo.addCategory(
                    name,
                    c.optLong("colorArgb"),
                    c.optInt("position"),
                    c.optLong("createdAt", System.currentTimeMillis())
                )
            }
        )
        val importedIdToLocalId = merge.idMap
        val categoriesCreated = merge.created
        // Which category is the fallback is a property of the set rather than of one row, so it is
        // restored after the merge, onto whichever local row the imported one turned out to be.
        (0 until importedCategories.length())
            .map { importedCategories.getJSONObject(it) }
            .firstOrNull { it.optBoolean("isDefault") }
            ?.let { importedIdToLocalId[it.optLong("id")] }
            ?.let { notesRepo.setDefaultCategory(it) }

        var notesCreated = 0
        if (root.has("notes")) {
            // Replace, not merge: importing a notes payload is a restore of that snapshot, not a
            // merge with whatever's already on this device — so instead of per-note duplicate
            // detection, snapshot the pre-existing notes, insert every imported note, then delete
            // only the pre-existing ones that plausibly existed when the export was taken
            // (createdAt <= exportedAt) — anything created on this device after the backup, but
            // before this import ran, is presumed unrelated to the restore and must survive.
            // Categories are untouched here (they already merge safely by name above).
            val preExistingNotes = notesRepo.notesSnapshot()
            val importedNotes = root.optJSONArray("notes") ?: JSONArray()

            notesCreated = VoxSnapshotReplaceImporter.restore(
                mode = mode,
                imported = (0 until importedNotes.length()).map { importedNotes.getJSONObject(it) },
                preExisting = preExistingNotes,
                exportedAt = exportedAt,
                createdAtOf = { it.createdAt },
                insert = insert@{ n ->
                    val text = n.optString("text")
                    val title = n.optStringOrNull("title")
                    if (text.isBlank() && title.isNullOrBlank()) return@insert 0L
                    val importedCategoryId = if (n.has("categoryId") && !n.isNull("categoryId")) n.optLong("categoryId") else null
                    val categoryId = importedCategoryId?.let { importedIdToLocalId[it] }
                    val newNoteId = notesRepo.addNote(
                        title = title,
                        text = text,
                        textHtml = n.optStringOrNull("textHtml"),
                        categoryId = categoryId,
                        // Preserved from the source device, never re-stamped to "now" — see the
                        // exportedAt comment above for why that would silently undo this fix.
                        createdAt = n.optLong("createdAt", System.currentTimeMillis())
                    )
                    if (newNoteId > 0) {
                        attachmentDao.restoreFromBackup(
                            NotesAttachments.RECORD_TYPE, newNoteId, n.optJSONArray("attachments") ?: JSONArray()
                        )
                    }
                    newNoteId
                },
                delete = { notesRepo.deleteNoteById(it.id) }
            )
        }

        return "$notesCreated notes imported, $categoriesCreated new categories " +
            "(${importedCategories.length() - categoriesCreated} matched existing)"
    }
}

// Gson reflection over the whole data class, not a hand-maintained field list — a manual allowlist
// silently falls behind every time a new setting is added (todayEffect*/notifications* were missing
// before this fix). onboardingCompleted is the one deliberate exclusion (device-local UI state, not
// portable user data — see its own doc comment); reset rather than omitted so import's
// Gson.fromJson always has every field present. Mirrors vox-commander's CommanderExportHandler, the
// only handler in this codebase that didn't suffer this drift.
private fun NotesSettings.toJson(): JSONObject =
    JSONObject(VoxSettingsRoundTrip.toJson(copy(onboardingCompleted = false)))

/** Returns Room/DataStore defaults for [NotesSettings] if [this] isn't valid JSON for it (e.g. a
 *  corrupt/foreign import file). */
private fun JSONObject.toNotesSettings(): NotesSettings =
    VoxSettingsRoundTrip.parseOrDefault(toString(), NotesSettings::class.java, NotesSettings())

private fun Category.toJson(): JSONObject = JSONObject().apply {
    put("id", id)
    put("name", name)
    put("colorArgb", colorArgb)
    put("position", position)
    put("createdAt", createdAt)
    put("isDefault", isDefault)
}

