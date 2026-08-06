package com.voxapps.calendarapp.receiver

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.google.gson.Gson
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
import com.voxapps.calendarapp.data.ToDoList
import com.voxapps.calendarapp.data.ToDoListDao
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
private val gson = Gson()

/**
 * Vox Hub's export/import for this app, extracted from the BroadcastReceiver so it's unit-testable
 * without Android (mirrors vox-expenses' `ExpensesExportImportHandler`). Respects the same
 * biometric-lock gate as reads — an export/import request while the app is locked never touches the
 * DB. Fully independent of the ICS import/export screen (Phase 5) — different format, different path.
 *
 * [toDoListDao] is injected directly (same convention as [attachmentDao]) rather than routed through
 * [com.voxapps.calendarapp.data.ToDoRepository] — this handler already calls [calendarRepo]'s
 * low-level methods directly rather than any UI-facing wrapper, and a to-do item is restored through
 * [CalendarRepository.addEntry] (the single write point for entries/tags/reminders) exactly like a
 * plain event, just with its to-do fields populated.
 */
class CalendarExportImportHandler(
    private val context: Context,
    private val settingsRepo: CalendarSettingsRepository,
    private val sessionManager: SessionManager,
    private val calendarRepo: CalendarRepository,
    private val attachmentDao: AttachmentDao,
    private val toDoListDao: ToDoListDao
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
            val toDoLists = toDoListDao.observeAll().first()
            val allEntries = calendarRepo.entriesSnapshot()
            // A to-do checklist item (CalendarEntry.listId != null) is exported separately as
            // "todoItems" rather than folded into "events" — its listId needs its own id-remapping
            // pass against "todoLists" on import (see import()'s doc comment), same reasoning
            // "events" already remaps layerId against "layers".
            val events = allEntries.filter { it.entry.listId == null }
            val todoItems = allEntries.filter { it.entry.listId != null }
            json.put("layers", JSONArray(layers.map { it.toJson() }))
            json.put("todoLists", JSONArray(toDoLists.map { it.toJson() }))
            val allFileNames = mutableListOf<String>()
            suspend fun entriesToJson(entries: List<CalendarEntryWithTags>): JSONArray = JSONArray(
                entries.map { entryWithTags ->
                    val attachments = attachmentDao.getFor(CalendarAttachments.RECORD_TYPE, entryWithTags.entry.id)
                    allFileNames += attachments.map { it.fileName }
                    val reminders = calendarRepo.getRemindersForEntry(entryWithTags.entry.id)
                    entryWithTags.toJson(attachments, reminders.map { it.offsetMinutesBefore })
                }
            )
            json.put("events", entriesToJson(events))
            json.put("todoItems", entriesToJson(todoItems))
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

        // ToDoLists merge by title (mirrors layers merging by name above) — todoItems reference a
        // list by id, so wholesale-replacing lists would orphan any untouched item's listId.
        val existingLists = toDoListDao.observeAll().first()
        val listNameToId = existingLists.associate { it.title.lowercase() to it.id }.toMutableMap()
        val importedListIdToLocalId = mutableMapOf<Long, Long?>()
        val importedLists = root.optJSONArray("todoLists") ?: JSONArray()
        var listsCreated = 0
        for (i in 0 until importedLists.length()) {
            val l = importedLists.getJSONObject(i)
            val title = l.optString("title").trim()
            if (title.isEmpty()) continue
            val importedId = l.optLong("id")
            val now = System.currentTimeMillis()
            val localId = listNameToId[title.lowercase()] ?: run {
                val newId = toDoListDao.insert(
                    ToDoList(
                        uid = l.optStringOrNull("uid") ?: UUID.randomUUID().toString(),
                        title = title,
                        colorArgb = l.optLong("colorArgb"),
                        layerId = importedIdToLocalId[l.optLong("layerId")] ?: defaultLocalLayerId ?: 0,
                        createdAt = l.optLong("createdAt", now),
                        updatedAt = l.optLong("updatedAt", now)
                    )
                )
                if (newId > 0) {
                    listsCreated++
                    listNameToId[title.lowercase()] = newId
                }
                newId.takeIf { it > 0 }
            }
            importedListIdToLocalId[importedId] = localId
        }

        // Shared by both "events" and "todoItems" below — the only difference between the two is
        // which pre-existing snapshot they're replacing and whether listId resolves against
        // importedListIdToLocalId (see each loop's own comment).
        suspend fun restoreEntry(e: JSONObject, layerId: Long, listId: Long?): Long {
            val tagsArray = e.optJSONArray("tags") ?: JSONArray()
            val tags = (0 until tagsArray.length()).map { tagsArray.optString(it) }
            val remindersArray = e.optJSONArray("reminders") ?: JSONArray()
            val reminders = (0 until remindersArray.length()).map { remindersArray.optInt(it) }

            return calendarRepo.addEntry(
                uid = e.optStringOrNull("uid") ?: UUID.randomUUID().toString(),
                type = e.optStringOrNull("type")?.let { runCatching { CalendarEntryType.valueOf(it) }.getOrNull() }
                    ?: CalendarEntryType.EVENT,
                title = e.optString("title"),
                description = e.optStringOrNull("description"),
                location = e.optStringOrNull("location"),
                startMillis = if (e.has("startMillis") && !e.isNull("startMillis")) e.optLong("startMillis") else null,
                endMillis = if (e.has("endMillis") && !e.isNull("endMillis")) e.optLong("endMillis") else null,
                allDay = e.optBoolean("allDay", false),
                completed = e.optBoolean("completed", false),
                isImportant = e.optBoolean("isImportant", false),
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
                tags = tags,
                reminderOffsetsMinutes = reminders,
                listId = listId,
                position = e.optInt("position", 0),
                colorArgb = if (e.has("colorArgb") && !e.isNull("colorArgb")) e.optLong("colorArgb") else null,
                comments = e.optStringOrNull("comments")
            )
        }

        suspend fun restoreAttachments(newEntryId: Long, e: JSONObject) {
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

                val newEntryId = restoreEntry(e, layerId, listId = null)
                entriesCreated++
                if (newEntryId > 0) restoreAttachments(newEntryId, e)
            }

            preExistingIds.forEach { calendarRepo.deleteEntryById(it) }
        }

        // Same snapshot-then-replace shape as "events" just above, filtered to to-do-flavored rows
        // instead (listId != null) — the two never touch each other's snapshot.
        var itemsCreated = 0
        if (root.has("todoItems")) {
            val preExistingItemIds = calendarRepo.entriesSnapshot().filter { it.entry.listId != null }.map { it.entry.id }

            val importedItems = root.optJSONArray("todoItems") ?: JSONArray()
            for (i in 0 until importedItems.length()) {
                val e = importedItems.getJSONObject(i)
                val importedLayerId = e.optLong("layerId")
                val layerId = importedIdToLocalId[importedLayerId] ?: defaultLocalLayerId ?: continue
                val listId = importedListIdToLocalId[e.optLong("listId")] ?: continue

                val newEntryId = restoreEntry(e, layerId, listId)
                itemsCreated++
                if (newEntryId > 0) restoreAttachments(newEntryId, e)
            }

            preExistingItemIds.forEach { calendarRepo.deleteEntryById(it) }
        }

        return VoxResult(
            ok = true,
            text = "$entriesCreated entries and $itemsCreated to-do items imported, " +
                "$layersCreated new calendars (${importedLayers.length() - layersCreated} matched existing), " +
                "$listsCreated new to-do lists (${importedLists.length() - listsCreated} matched existing)"
        )
    }
}

// Gson reflection over the whole data class, not a hand-maintained field list — a manual allowlist
// silently falls behind every time a new setting is added (this one was 8 of 25 fields before this
// fix). onboardingCompleted is the one deliberate exclusion (device-local UI state, not portable
// user data — see its own doc comment); reset to false on export rather than omitted from the JSON
// so import's Gson.fromJson always has every field present. Mirrors vox-commander's
// CommanderExportHandler, the only handler in this codebase that didn't suffer this drift.
private fun CalendarSettings.toJson(): JSONObject =
    JSONObject(gson.toJson(copy(onboardingCompleted = false)))

/** Returns Room/DataStore defaults for [CalendarSettings] if [this] isn't valid JSON for it (e.g. a
 *  corrupt/foreign import file) — Gson's reflection deserializer can't throw its way past a
 *  structurally-valid-but-wrong-shape JSON object, so this fails safe to the all-defaults instance
 *  rather than a partially-null one. */
private fun JSONObject.toCalendarSettings(): CalendarSettings =
    gson.fromJson(toString(), CalendarSettings::class.java) ?: CalendarSettings()

private fun CalendarLayer.toJson(): JSONObject = JSONObject().apply {
    put("id", id)
    put("name", name)
    put("colorArgb", colorArgb)
    put("visible", visible)
    put("isDefault", isDefault)
    put("position", position)
    put("createdAt", createdAt)
}

private fun ToDoList.toJson(): JSONObject = JSONObject().apply {
    put("id", id)
    put("uid", uid)
    put("title", title)
    put("colorArgb", colorArgb)
    put("layerId", layerId)
    put("createdAt", createdAt)
    put("updatedAt", updatedAt)
}

private fun CalendarEntryWithTags.toJson(
    attachments: List<AttachmentEntity> = emptyList(),
    reminderOffsetsMinutes: List<Int> = emptyList()
): JSONObject = JSONObject().apply {
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
    put("isImportant", e.isImportant)
    put("comments", e.comments)
    put("listId", e.listId)
    put("position", e.position)
    put("colorArgb", e.colorArgb)
    put("tags", JSONArray(tagNames))
    put("attachments", JSONArray(attachments.map { it.toJson() }))
    put("reminders", JSONArray(reminderOffsetsMinutes))
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
