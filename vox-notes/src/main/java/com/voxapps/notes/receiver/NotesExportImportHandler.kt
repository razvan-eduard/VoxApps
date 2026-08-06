package com.voxapps.notes.receiver

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.google.gson.Gson
import com.voxapps.attachments.AttachmentDao
import com.voxapps.attachments.AttachmentEntity
import com.voxapps.attachments.AttachmentSource
import com.voxapps.ipc.VoxIpc
import com.voxapps.ipc.VoxResult
import com.voxapps.logging.Logger
import com.voxapps.notes.data.Category
import com.voxapps.notes.data.NotesAttachments
import com.voxapps.notes.data.NotesRepository
import com.voxapps.notes.data.preferences.NotesSettings
import com.voxapps.notes.data.preferences.NotesSettingsRepository
import com.voxapps.notes.state.SessionManager
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

private const val TAG = "NotesExportImportHandler"
private val gson = Gson()

/**
 * Vox Hub's export/import for this app, extracted from the BroadcastReceiver so it's unit-testable
 * without Android (mirrors [NotesReadResponder]). Respects the same biometric-lock gate as reads —
 * an export/import request while the app is locked never touches the DB.
 */
class NotesExportImportHandler(
    private val context: Context,
    private val settingsRepo: NotesSettingsRepository,
    private val sessionManager: SessionManager,
    private val notesRepo: NotesRepository,
    private val attachmentDao: AttachmentDao
) {
    suspend fun export(scope: String = VoxIpc.EXPORT_SCOPE_BOTH, includePhotos: Boolean = false): VoxResult {
        val settings = settingsRepo.getSnapshot()
        val locked = settings.isBiometricRequired &&
            !sessionManager.isSessionValid(settings.sessionTimeoutMinutes)
        if (locked) return VoxResult(ok = false, text = NotesReadResponder.LOCKED_MESSAGE)

        val json = JSONObject()
        var attachmentUri: String? = null
        if (scope != VoxIpc.EXPORT_SCOPE_DATA) {
            json.put("settings", settings.toJson())
        }
        if (scope != VoxIpc.EXPORT_SCOPE_SETTINGS) {
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
                            put("createdAt", note.createdAt)
                            put("categoryId", note.categoryId)
                            put("attachments", JSONArray(attachments.map { it.toJson() }))
                        }
                    }
                )
            )
            if (includePhotos) {
                attachmentUri = buildAttachmentsZip(allFileNames)?.toString()
            }
        }
        return VoxResult(ok = true, text = json.toString(), attachmentUri = attachmentUri)
    }

    /** Zips this export's attachment files (see :core:attachments) into a fresh file under cacheDir
     *  and grants Hub read access — mirrors vox-expenses' buildReceiptsZip. Best-effort: returns null
     *  (no attachment) on any failure or if there's nothing to bundle, never blocks the JSON export. */
    private fun buildAttachmentsZip(fileNames: List<String>): Uri? {
        if (fileNames.isEmpty()) return null
        val attachmentsDir = File(context.filesDir, NotesAttachments.DIR)
        return try {
            val exportsDir = File(context.cacheDir, "exports").apply { mkdirs() }
            val zipFile = File(exportsDir, "export_attachments_${UUID.randomUUID()}.zip")
            var wroteAny = false
            ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
                for (name in fileNames) {
                    val file = File(attachmentsDir, name)
                    if (file.exists()) {
                        zos.putNextEntry(ZipEntry(name))
                        file.inputStream().use { it.copyTo(zos) }
                        zos.closeEntry()
                        wroteAny = true
                    }
                }
            }
            if (!wroteAny) {
                zipFile.delete()
                return null
            }
            val uri = FileProvider.getUriForFile(context, NotesAttachments.FILE_PROVIDER_AUTHORITY, zipFile)
            context.grantUriPermission(VoxIpc.HUB_PACKAGE, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            uri
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to build attachments export zip", e)
            null
        }
    }

    /** Same shape as vox-expenses' extractReceiptsZip — every entry name flattened to its bare
     *  filename (zip-slip defense). */
    private fun extractAttachmentsZip(uri: Uri) {
        val attachmentsDir = File(context.filesDir, NotesAttachments.DIR).apply { mkdirs() }
        context.contentResolver.openInputStream(uri)?.use { input ->
            ZipInputStream(input).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val safeName = File(entry.name).name
                        if (safeName.isNotBlank()) {
                            FileOutputStream(File(attachmentsDir, safeName)).use { fos -> zis.copyTo(fos) }
                        }
                    }
                    entry = zis.nextEntry
                }
            }
        }
    }

    suspend fun import(payloadJson: String): VoxResult {
        val settings = settingsRepo.getSnapshot()
        val locked = settings.isBiometricRequired &&
            !sessionManager.isSessionValid(settings.sessionTimeoutMinutes)
        if (locked) return VoxResult(ok = false, text = NotesReadResponder.LOCKED_MESSAGE)

        val root = try {
            JSONObject(payloadJson)
        } catch (e: Exception) {
            return VoxResult(ok = false, text = "Invalid import payload")
        }

        root.optJSONObject("settings")?.let { settingsRepo.restoreSettings(it.toNotesSettings()) }

        // Injected by Hub's ExportImportUtil.parseImportDocument() from the outer export document's
        // timestamp. Defaults to 0L (never true against any real createdAt) so a payload missing
        // this field fails safe by deleting nothing, rather than reverting to "delete everything
        // that existed at import time".
        val exportedAt = root.optLong("exported_at", 0L)

        // Stage any bundled attachment photos before the note-insert loop below references them by
        // filename, so thumbnails resolve immediately once the import completes. Best-effort: a
        // failure here never fails the rest of the import, it just means photos are missing.
        root.optStringOrNull("attachmentsZipUri")?.let { uriString ->
            try {
                extractAttachmentsZip(Uri.parse(uriString))
            } catch (e: Exception) {
                Logger.w(TAG, "Failed to import attachment photos from $uriString — continuing without them", e)
            }
        }

        val existingCategories = notesRepo.categories.first()
        val nameToId = existingCategories.associate { it.name.lowercase() to it.id }.toMutableMap()
        val importedIdToLocalId = mutableMapOf<Long, Long?>()
        val importedCategories = root.optJSONArray("categories") ?: JSONArray()
        var categoriesCreated = 0
        for (i in 0 until importedCategories.length()) {
            val c = importedCategories.getJSONObject(i)
            val name = c.optString("name").trim()
            if (name.isEmpty()) continue
            val importedId = c.optLong("id")
            val localId = nameToId[name.lowercase()] ?: run {
                val newId = notesRepo.addCategory(
                    name,
                    c.optLong("colorArgb"),
                    c.optInt("position"),
                    c.optLong("createdAt", System.currentTimeMillis())
                )
                if (newId > 0) {
                    categoriesCreated++
                    nameToId[name.lowercase()] = newId
                }
                newId.takeIf { it > 0 }
            }
            importedIdToLocalId[importedId] = localId
        }

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
            for (i in 0 until importedNotes.length()) {
                val n = importedNotes.getJSONObject(i)
                val text = n.optString("text")
                val title = n.optStringOrNull("title")
                if (text.isBlank() && title.isNullOrBlank()) continue
                val importedCategoryId = if (n.has("categoryId") && !n.isNull("categoryId")) n.optLong("categoryId") else null
                val categoryId = importedCategoryId?.let { importedIdToLocalId[it] }
                val newNoteId = notesRepo.addNote(
                    title = title,
                    text = text,
                    categoryId = categoryId,
                    // Preserved from the source device, never re-stamped to "now" — see the
                    // exportedAt comment above for why that would silently undo this fix.
                    createdAt = n.optLong("createdAt", System.currentTimeMillis())
                )
                notesCreated++

                if (newNoteId > 0) {
                    val importedAttachments = n.optJSONArray("attachments") ?: JSONArray()
                    for (j in 0 until importedAttachments.length()) {
                        val a = importedAttachments.getJSONObject(j)
                        val fileName = a.optString("fileName").takeIf { it.isNotBlank() } ?: continue
                        attachmentDao.insert(
                            AttachmentEntity(
                                recordType = NotesAttachments.RECORD_TYPE,
                                recordId = newNoteId,
                                fileName = fileName,
                                source = a.optString("source", AttachmentSource.MANUAL),
                                createdAt = a.optLong("createdAt", System.currentTimeMillis()),
                                groupId = a.optStringOrNull("groupId"),
                                groupOrder = a.optInt("groupOrder", 0)
                            )
                        )
                    }
                }
            }

            preExistingNotes.filter { it.createdAt <= exportedAt }.forEach { notesRepo.deleteNoteById(it.id) }
        }

        return VoxResult(
            ok = true,
            text = "$notesCreated notes imported, $categoriesCreated new categories " +
                "(${importedCategories.length() - categoriesCreated} matched existing)"
        )
    }
}

// Gson reflection over the whole data class, not a hand-maintained field list — a manual allowlist
// silently falls behind every time a new setting is added (todayEffect*/notifications* were missing
// before this fix). onboardingCompleted is the one deliberate exclusion (device-local UI state, not
// portable user data — see its own doc comment); reset rather than omitted so import's
// Gson.fromJson always has every field present. Mirrors vox-commander's CommanderExportHandler, the
// only handler in this codebase that didn't suffer this drift.
private fun NotesSettings.toJson(): JSONObject =
    JSONObject(gson.toJson(copy(onboardingCompleted = false)))

/** Returns Room/DataStore defaults for [NotesSettings] if [this] isn't valid JSON for it (e.g. a
 *  corrupt/foreign import file). */
private fun JSONObject.toNotesSettings(): NotesSettings =
    gson.fromJson(toString(), NotesSettings::class.java) ?: NotesSettings()

private fun Category.toJson(): JSONObject = JSONObject().apply {
    put("id", id)
    put("name", name)
    put("colorArgb", colorArgb)
    put("position", position)
    put("createdAt", createdAt)
}

private fun AttachmentEntity.toJson(): JSONObject = JSONObject().apply {
    put("fileName", fileName)
    put("source", source)
    put("createdAt", createdAt)
    put("groupId", groupId)
    put("groupOrder", groupOrder)
}

private fun JSONObject.optStringOrNull(key: String): String? =
    if (has(key) && !isNull(key)) optString(key) else null
