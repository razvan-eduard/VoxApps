package com.voxapps.calendarapp.receiver

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.voxapps.attachments.AttachmentDao
import com.voxapps.attachments.AttachmentEntity
import com.voxapps.attachments.AttachmentSource
import com.voxapps.calendarapp.data.CalendarAttachments
import com.voxapps.calendarapp.data.CalendarEntry
import com.voxapps.calendarapp.data.CalendarEntryType
import com.voxapps.calendarapp.data.CalendarEntryWithTags
import com.voxapps.calendarapp.data.CalendarLayer
import com.voxapps.calendarapp.data.CalendarRepository
import com.voxapps.calendarapp.data.RecurrenceFrequency
import com.voxapps.calendarapp.data.preferences.CalendarSettings
import com.voxapps.calendarapp.data.preferences.CalendarSettingsRepository
import com.voxapps.calendarapp.state.SessionManager
import com.voxapps.ipc.VoxIpc
import com.voxapps.ipc.VoxResult
import com.voxapps.logging.Logger
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

private const val TAG = "CalendarExportImportHandler"

/**
 * Vox Hub's export/import for this app, extracted from the BroadcastReceiver so it's unit-testable
 * without Android (mirrors vox-expenses' `ExpensesExportImportHandler`). Respects the same
 * biometric-lock gate as reads — an export/import request while the app is locked never touches the
 * DB. Fully independent of the ICS import/export screen (Phase 5) — different format, different path.
 */
class CalendarExportImportHandler(
    private val context: Context,
    private val settingsRepo: CalendarSettingsRepository,
    private val sessionManager: SessionManager,
    private val calendarRepo: CalendarRepository,
    private val attachmentDao: AttachmentDao
) {
    suspend fun export(scope: String = VoxIpc.EXPORT_SCOPE_BOTH, includePhotos: Boolean = false): VoxResult {
        val settings = settingsRepo.getSnapshot()
        val locked = settings.isBiometricRequired &&
            !sessionManager.isSessionValid(settings.sessionTimeoutMinutes)
        if (locked) return VoxResult(ok = false, text = CalendarReadResponder.LOCKED_MESSAGE)

        val json = JSONObject()
        var attachmentUri: String? = null
        if (scope != VoxIpc.EXPORT_SCOPE_DATA) {
            json.put("settings", settings.toJson())
        }
        if (scope != VoxIpc.EXPORT_SCOPE_SETTINGS) {
            val layers = calendarRepo.layersSnapshot()
            // Excludes to-do-flavored entries (CalendarEntry.listId != null) — a to-do checklist item
            // isn't a Hub-backup concept and is never round-tripped through this JSON export/import.
            val entries = calendarRepo.entriesSnapshot().filter { it.entry.listId == null }
            json.put("layers", JSONArray(layers.map { it.toJson() }))
            val allFileNames = mutableListOf<String>()
            json.put(
                "events",
                JSONArray(
                    entries.map { entryWithTags ->
                        val attachments = attachmentDao.getFor(CalendarAttachments.RECORD_TYPE, entryWithTags.entry.id)
                        allFileNames += attachments.map { it.fileName }
                        entryWithTags.toJson(attachments)
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
     *  and grants Hub read access — mirrors vox-expenses' buildReceiptsZip/vox-notes'
     *  buildAttachmentsZip. Best-effort: returns null on any failure or if there's nothing to bundle. */
    private fun buildAttachmentsZip(fileNames: List<String>): Uri? {
        if (fileNames.isEmpty()) return null
        val attachmentsDir = File(context.filesDir, CalendarAttachments.DIR)
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
            val uri = FileProvider.getUriForFile(context, CalendarAttachments.FILE_PROVIDER_AUTHORITY, zipFile)
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
        val attachmentsDir = File(context.filesDir, CalendarAttachments.DIR).apply { mkdirs() }
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
        if (locked) return VoxResult(ok = false, text = CalendarReadResponder.LOCKED_MESSAGE)

        val root = try {
            JSONObject(payloadJson)
        } catch (e: Exception) {
            return VoxResult(ok = false, text = "Invalid import payload")
        }

        root.optJSONObject("settings")?.let { settingsRepo.restoreSettings(it.toCalendarSettings()) }

        // Stage any bundled attachment photos before the entry-insert loop below references them by
        // filename, so thumbnails resolve immediately once the import completes. Best-effort: a
        // failure here never fails the rest of the import, it just means photos are missing.
        root.optStringOrNull("attachmentsZipUri")?.let { uriString ->
            try {
                extractAttachmentsZip(Uri.parse(uriString))
            } catch (e: Exception) {
                Logger.w(TAG, "Failed to import attachment photos from $uriString — continuing without them", e)
            }
        }

        // Layers merge by name (mirrors categories in vox-expenses) — entries reference layers by id,
        // so wholesale-replacing layers would orphan any untouched records pointing at old layer ids.
        val existingLayers = calendarRepo.layersSnapshot()
        val nameToId = existingLayers.associate { it.name.lowercase() to it.id }.toMutableMap()
        val importedIdToLocalId = mutableMapOf<Long, Long?>()
        val defaultLocalLayerId = existingLayers.firstOrNull { it.isDefault }?.id ?: existingLayers.firstOrNull()?.id
        val importedLayers = root.optJSONArray("layers") ?: JSONArray()
        var layersCreated = 0
        for (i in 0 until importedLayers.length()) {
            val l = importedLayers.getJSONObject(i)
            val name = l.optString("name").trim()
            if (name.isEmpty()) continue
            val importedId = l.optLong("id")
            val localId = nameToId[name.lowercase()] ?: run {
                val newId = calendarRepo.addLayer(
                    name = name,
                    colorArgb = l.optLong("colorArgb"),
                    position = l.optInt("position")
                )
                if (newId > 0) {
                    layersCreated++
                    nameToId[name.lowercase()] = newId
                }
                newId.takeIf { it > 0 }
            }
            importedIdToLocalId[importedId] = localId
        }

        // Entries use snapshot-then-replace semantics (mirrors vox-expenses' expenses): snapshot
        // pre-existing entries, insert every imported one, then delete exactly what existed before.
        var entriesCreated = 0
        if (root.has("events")) {
            // Same to-do exclusion as export — a to-do-flavored entry was never part of this backup,
            // so it must never be wiped by this import's snapshot-then-replace pass either.
            val preExistingIds = calendarRepo.entriesSnapshot().filter { it.entry.listId == null }.map { it.entry.id }

            val importedEntries = root.optJSONArray("events") ?: JSONArray()
            for (i in 0 until importedEntries.length()) {
                val e = importedEntries.getJSONObject(i)
                val importedLayerId = e.optLong("layerId")
                val layerId = importedIdToLocalId[importedLayerId] ?: defaultLocalLayerId ?: continue
                val tagsArray = e.optJSONArray("tags") ?: JSONArray()
                val tags = (0 until tagsArray.length()).map { tagsArray.optString(it) }

                val newEntryId = calendarRepo.addEntry(
                    uid = e.optStringOrNull("uid") ?: UUID.randomUUID().toString(),
                    type = e.optStringOrNull("type")?.let { runCatching { CalendarEntryType.valueOf(it) }.getOrNull() }
                        ?: CalendarEntryType.EVENT,
                    title = e.optString("title"),
                    description = e.optStringOrNull("description"),
                    location = e.optStringOrNull("location"),
                    startMillis = e.optLong("startMillis"),
                    endMillis = if (e.has("endMillis") && !e.isNull("endMillis")) e.optLong("endMillis") else null,
                    allDay = e.optBoolean("allDay", false),
                    completed = e.optBoolean("completed", false),
                    recurrenceFrequency = e.optStringOrNull("recurrenceFrequency")
                        ?.let { runCatching { RecurrenceFrequency.valueOf(it) }.getOrNull() }
                        ?: RecurrenceFrequency.NONE,
                    recurrenceInterval = if (e.has("recurrenceInterval")) e.optInt("recurrenceInterval", 1) else 1,
                    recurrenceUntilMillis = if (e.has("recurrenceUntilMillis") && !e.isNull("recurrenceUntilMillis")) {
                        e.optLong("recurrenceUntilMillis")
                    } else {
                        null
                    },
                    layerId = layerId,
                    tags = tags
                )
                entriesCreated++

                if (newEntryId > 0) {
                    val importedAttachments = e.optJSONArray("attachments") ?: JSONArray()
                    for (j in 0 until importedAttachments.length()) {
                        val a = importedAttachments.getJSONObject(j)
                        val fileName = a.optString("fileName").takeIf { it.isNotBlank() } ?: continue
                        attachmentDao.insert(
                            AttachmentEntity(
                                recordType = CalendarAttachments.RECORD_TYPE,
                                recordId = newEntryId,
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

            preExistingIds.forEach { calendarRepo.deleteEntryById(it) }
        }

        return VoxResult(
            ok = true,
            text = "$entriesCreated entries imported, $layersCreated new calendars " +
                "(${importedLayers.length() - layersCreated} matched existing)"
        )
    }
}

private fun CalendarSettings.toJson(): JSONObject = JSONObject().apply {
    put("isBiometricRequired", isBiometricRequired)
    put("sessionTimeoutMinutes", sessionTimeoutMinutes)
    put("language", language)
    put("defaultLayerId", defaultLayerId)
    put("autoCreateLayer", autoCreateLayer)
    put("debugLoggingEnabled", debugLoggingEnabled)
    put("themeDarkMode", themeDarkMode)
    put("themeColored", themeColored)
}

private fun JSONObject.toCalendarSettings(): CalendarSettings = CalendarSettings(
    isBiometricRequired = optBoolean("isBiometricRequired", false),
    sessionTimeoutMinutes = optInt("sessionTimeoutMinutes", CalendarSettings.TIMEOUT_30M),
    language = optString("language", CalendarSettings.DEFAULT_LANGUAGE),
    defaultLayerId = if (has("defaultLayerId") && !isNull("defaultLayerId")) optLong("defaultLayerId") else null,
    autoCreateLayer = optBoolean("autoCreateLayer", false),
    debugLoggingEnabled = optBoolean("debugLoggingEnabled", false),
    themeDarkMode = optString("themeDarkMode", CalendarSettings.THEME_SYSTEM),
    themeColored = optBoolean("themeColored", true)
)

private fun CalendarLayer.toJson(): JSONObject = JSONObject().apply {
    put("id", id)
    put("name", name)
    put("colorArgb", colorArgb)
    put("visible", visible)
    put("isDefault", isDefault)
    put("position", position)
    put("createdAt", createdAt)
}

private fun CalendarEntryWithTags.toJson(attachments: List<AttachmentEntity> = emptyList()): JSONObject = JSONObject().apply {
    val e: CalendarEntry = entry
    put("id", e.id)
    put("uid", e.uid)
    put("type", e.type.name)
    put("title", e.title)
    put("description", e.description)
    put("location", e.location)
    put("startMillis", e.startMillis)
    put("endMillis", e.endMillis)
    put("allDay", e.allDay)
    put("completed", e.completed)
    put("recurrenceFrequency", e.recurrenceFrequency.name)
    put("recurrenceInterval", e.recurrenceInterval)
    put("recurrenceUntilMillis", e.recurrenceUntilMillis)
    put("layerId", e.layerId)
    put("tags", JSONArray(tagNames))
    put("attachments", JSONArray(attachments.map { it.toJson() }))
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
