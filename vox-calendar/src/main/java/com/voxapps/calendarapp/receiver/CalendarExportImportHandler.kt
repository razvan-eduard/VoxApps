package com.voxapps.calendarapp.receiver

import android.content.Context
import com.voxapps.attachments.AttachmentDao
import com.voxapps.attachments.restoreFromBackup
import com.voxapps.attachments.toBackupJson
import com.voxapps.attachments.AttachmentEntity
import com.voxapps.backup.VoxBiometricGate
import com.voxapps.backup.VoxExportImportHandler
import com.voxapps.backup.mergeByName
import com.voxapps.backup.optStringOrNull
import com.voxapps.backup.VoxImportMode
import com.voxapps.backup.VoxSettingsRoundTrip
import com.voxapps.backup.VoxSnapshotReplaceImporter
import com.voxapps.calendarapp.data.CalendarAttachments
import com.voxapps.calendarapp.data.CalendarEntry
import com.voxapps.calendarapp.data.CalendarEntryType
import com.voxapps.calendarapp.data.CalendarEntryWithTags
import com.voxapps.calendarapp.data.CalendarLayer
import com.voxapps.calendarapp.data.CalendarLayerKind
import com.voxapps.calendarapp.data.CalendarRepository
import com.voxapps.calendarapp.data.RecurrenceFrequency
import com.voxapps.calendarapp.data.ReminderOffsetsCodec
import com.voxapps.calendarapp.data.ToDoList
import com.voxapps.calendarapp.data.ToDoListDao
import com.voxapps.calendarapp.data.preferences.CalendarSettings
import com.voxapps.calendarapp.data.preferences.CalendarSettingsRepository
import com.voxapps.calendarapp.state.SessionManager
import com.voxapps.design.toEnumOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

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
    context: Context,
    private val settingsRepo: CalendarSettingsRepository,
    private val sessionManager: SessionManager,
    private val calendarRepo: CalendarRepository,
    private val attachmentDao: AttachmentDao,
    private val toDoListDao: ToDoListDao,
    override val lockedMessage: String
) : VoxExportImportHandler(context, CalendarAttachments.DIR, CalendarAttachments.FILE_PROVIDER_AUTHORITY) {

    override suspend fun isLocked(): Boolean {
        val settings = settingsRepo.getSnapshot()
        return VoxBiometricGate.isLocked(
            settings.isBiometricRequired, settings.sessionTimeoutMinutes, sessionManager::isSessionValid
        )
    }

    override suspend fun exportSettings(): JSONObject = settingsRepo.getSnapshot().toJson()

    override suspend fun restoreSettings(settings: JSONObject) =
        settingsRepo.restoreSettings(settings.toCalendarSettings())

    override suspend fun exportData(json: JSONObject): List<String> {
            val layers = calendarRepo.layersSnapshot()
            val toDoLists = toDoListDao.getAll()
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
                    // The entry's own INDIVIDUAL preference, not whatever's currently effectively
                    // scheduled — the two only differ while a calendar-level override is active (see
                    // CalendarLayer's doc comment), and it's the individual choice that should survive
                    // an export/import round-trip unchanged.
                    val reminders = ReminderOffsetsCodec.decode(entryWithTags.entry.individualReminderOffsetsMinutes)
                    entryWithTags.toJson(attachments, reminders)
                }
            )
            json.put("events", entriesToJson(events))
            json.put("todoItems", entriesToJson(todoItems))
        return allFileNames
    }

    override suspend fun importData(root: JSONObject, exportedAt: Long, mode: VoxImportMode): String {
        val existingLayers = calendarRepo.layersSnapshot()
        val defaultLocalLayerId = existingLayers.firstOrNull { it.isDefault }?.id ?: existingLayers.firstOrNull()?.id

        val importedLayers = root.optJSONArray("layers") ?: JSONArray()
        val layerMerge = mergeByName(
            imported = importedLayers,
            existing = existingLayers,
            nameOf = { it.name },
            idOf = { it.id },
            importedNameOf = { it.optStringOrNull("name") ?: "" },
            create = { l, name ->
                calendarRepo.addLayerFromBackup(
                    name = name,
                    colorArgb = l.optLong("colorArgb"),
                    position = l.optInt("position"),
                    kind = l.optStringOrNull("kind").toEnumOrNull<CalendarLayerKind>() ?: CalendarLayerKind.LOCAL,
                    subscriptionUrl = l.optStringOrNull("subscriptionUrl"),
                    lastSyncedAt = if (l.has("lastSyncedAt") && !l.isNull("lastSyncedAt")) l.optLong("lastSyncedAt") else null,
                    lastSyncError = l.optStringOrNull("lastSyncError"),
                    reminderOffsetsMinutes = l.optStringOrNull("reminderOffsetsMinutes") ?: ""
                )
            }
        )
        val importedIdToLocalId = layerMerge.idMap
        val layersCreated = layerMerge.created

        // Merged after layers, not with them: a list's layerId is remapped through the layer merge
        // that just completed, so the two passes are ordered, not interchangeable.
        val importedLists = root.optJSONArray("todoLists") ?: JSONArray()
        val listMerge = mergeByName(
            imported = importedLists,
            existing = toDoListDao.getAll(),
            nameOf = { it.title },
            idOf = { it.id },
            importedNameOf = { it.optStringOrNull("title") ?: "" },
            create = { l, title ->
                val now = System.currentTimeMillis()
                toDoListDao.insert(
                    ToDoList(
                        uid = l.optStringOrNull("uid") ?: UUID.randomUUID().toString(),
                        title = title,
                        colorArgb = l.optLong("colorArgb"),
                        layerId = importedIdToLocalId[l.optLong("layerId")] ?: defaultLocalLayerId ?: 0,
                        createdAt = l.optLong("createdAt", now),
                        updatedAt = l.optLong("updatedAt", now),
                        routineDaysMask = l.optInt("routineDaysMask", 0),
                        routineLastResetDay = l.optLong("routineLastResetDay", 0)
                    )
                )
            }
        )
        val importedListIdToLocalId = listMerge.idMap
        val listsCreated = listMerge.created

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
                type = e.optStringOrNull("type").toEnumOrNull<CalendarEntryType>()
                    ?: CalendarEntryType.EVENT,
                title = e.optStringOrNull("title") ?: "",
                description = e.optStringOrNull("description"),
                location = e.optStringOrNull("location"),
                startMillis = if (e.has("startMillis") && !e.isNull("startMillis")) e.optLong("startMillis") else null,
                endMillis = if (e.has("endMillis") && !e.isNull("endMillis")) e.optLong("endMillis") else null,
                allDay = e.optBoolean("allDay", false),
                completed = e.optBoolean("completed", false),
                isImportant = e.optBoolean("isImportant", false),
                recurrenceFrequency = e.optStringOrNull("recurrenceFrequency")
                    .toEnumOrNull<RecurrenceFrequency>()
                    ?: RecurrenceFrequency.NONE,
                // Preserved from the source device, never re-stamped to "now" — same reasoning as
                // vox-expenses' addExpense createdAt param: re-stamping would make this row
                // permanently immune to the exportedAt-gated delete pass below on a later re-import.
                now = e.optLong("createdAt", System.currentTimeMillis()),
                recurrenceInterval = if (e.has("recurrenceInterval")) e.optInt("recurrenceInterval", 1) else 1,
                recurrenceUntilMillis = if (e.has("recurrenceUntilMillis") && !e.isNull("recurrenceUntilMillis")) {
                    e.optLong("recurrenceUntilMillis")
                } else {
                    null
                },
                recurrenceDaysMask = e.optInt("recurrenceDaysMask", 0),
                layerId = layerId,
                tags = tags,
                reminderOffsetsMinutes = reminders,
                listId = listId,
                position = e.optInt("position", 0),
                colorArgb = if (e.has("colorArgb") && !e.isNull("colorArgb")) e.optLong("colorArgb") else null,
                comments = e.optStringOrNull("comments"),
                // Identity and provenance survive the round trip — a backup from before they
                // existed reads as edited when it was created, which is the truest value it has.
                updatedAtOverride = e.optLong("updatedAt").takeIf { e.has("updatedAt") },
                originDeviceId = e.optStringOrNull("originDeviceId"),
                originDeviceName = e.optStringOrNull("originDeviceName")
            )
        }

        // Rows the backup claims by uid are replaced IN PLACE below: deleting them in the reconcile
        // pass would mint tombstones a later device sync faithfully replays against every paired
        // phone, erasing the very records the restore just wrote. In-place replacement goes through
        // the sync-merge write (whole row + tags), then reminders/attachments restore onto the
        // surviving local id.
        suspend fun replaceInPlace(e: JSONObject, existing: CalendarEntryWithTags, layerId: Long, listId: Long?): Long {
            val tagsArray = e.optJSONArray("tags") ?: JSONArray()
            val tags = (0 until tagsArray.length()).map { tagsArray.optString(it) }
            val remindersArray = e.optJSONArray("reminders") ?: JSONArray()
            val reminders = (0 until remindersArray.length()).map { remindersArray.optInt(it) }
            calendarRepo.updateSyncedEntry(
                existing.entry.copy(
                    type = e.optStringOrNull("type").toEnumOrNull<CalendarEntryType>() ?: CalendarEntryType.EVENT,
                    title = e.optStringOrNull("title") ?: "",
                    description = e.optStringOrNull("description"),
                    location = e.optStringOrNull("location"),
                    startMillis = if (e.has("startMillis") && !e.isNull("startMillis")) e.optLong("startMillis") else null,
                    endMillis = if (e.has("endMillis") && !e.isNull("endMillis")) e.optLong("endMillis") else null,
                    allDay = e.optBoolean("allDay", false),
                    completed = e.optBoolean("completed", false),
                    isImportant = e.optBoolean("isImportant", false),
                    recurrenceFrequency = e.optStringOrNull("recurrenceFrequency")
                        .toEnumOrNull<RecurrenceFrequency>() ?: RecurrenceFrequency.NONE,
                    recurrenceInterval = if (e.has("recurrenceInterval")) e.optInt("recurrenceInterval", 1) else 1,
                    recurrenceUntilMillis = if (e.has("recurrenceUntilMillis") && !e.isNull("recurrenceUntilMillis")) {
                        e.optLong("recurrenceUntilMillis")
                    } else {
                        null
                    },
                    recurrenceDaysMask = e.optInt("recurrenceDaysMask", 0),
                    layerId = layerId,
                    listId = listId,
                    position = e.optInt("position", 0),
                    colorArgb = if (e.has("colorArgb") && !e.isNull("colorArgb")) e.optLong("colorArgb") else null,
                    comments = e.optStringOrNull("comments"),
                    createdAt = e.optLong("createdAt", existing.entry.createdAt),
                    updatedAt = e.optLong("updatedAt", existing.entry.updatedAt),
                    individualReminderOffsetsMinutes = ReminderOffsetsCodec.encode(reminders),
                    originDeviceId = e.optStringOrNull("originDeviceId") ?: existing.entry.originDeviceId,
                    originDeviceName = e.optStringOrNull("originDeviceName") ?: existing.entry.originDeviceName
                ),
                tags,
                reminderOffsetsMinutes = reminders
            )
            return existing.entry.id
        }

        suspend fun restoreAttachments(newEntryId: Long, e: JSONObject) {
            attachmentDao.restoreFromBackup(
                CalendarAttachments.RECORD_TYPE, newEntryId, e.optJSONArray("attachments") ?: JSONArray()
            )
        }

        // Entries reconcile per the user's chosen import mode (see VoxSnapshotReplaceImporter):
        // snapshot pre-existing entries, insert every imported one, then reconcile pre-existing rows.
        var entriesCreated = 0
        if (root.has("events")) {
            // Same to-do exclusion as export — a to-do-flavored entry was never part of this backup,
            // so it must never be wiped by this import's reconciliation pass either.
            val preExistingEvents = calendarRepo.entriesSnapshot().filter { it.entry.listId == null }
            val preExistingByUid = preExistingEvents.associateBy { it.entry.uid }
            val importedEntries = root.optJSONArray("events") ?: JSONArray()
            val importedList = (0 until importedEntries.length()).map { importedEntries.getJSONObject(it) }
            val importedUids = importedList
                .mapNotNull { it.optStringOrNull("uid")?.takeIf { u -> u.isNotBlank() } }
                .toSet()

            entriesCreated = VoxSnapshotReplaceImporter.restore(
                mode = mode,
                imported = importedList,
                preExisting = preExistingEvents,
                exportedAt = exportedAt,
                createdAtOf = { it.entry.createdAt },
                insert = insert@{ e ->
                    val importedLayerId = e.optLong("layerId")
                    val layerId = importedIdToLocalId[importedLayerId] ?: defaultLocalLayerId ?: return@insert 0L
                    val existing = e.optStringOrNull("uid")?.takeIf { it.isNotBlank() }?.let { preExistingByUid[it] }
                    val entryId = if (existing != null) {
                        replaceInPlace(e, existing, layerId, listId = null)
                    } else {
                        restoreEntry(e, layerId, listId = null)
                    }
                    if (entryId > 0) restoreAttachments(entryId, e)
                    entryId
                },
                // A row claimed by uid was replaced in place — it IS the restored data now.
                delete = { if (it.entry.uid !in importedUids) calendarRepo.deleteEntryById(it.entry.id) }
            )
        }

        // Same reconciliation shape as "events" just above, filtered to to-do-flavored rows instead
        // (listId != null) — the two never touch each other's snapshot.
        var itemsCreated = 0
        if (root.has("todoItems")) {
            val preExistingItems = calendarRepo.entriesSnapshot().filter { it.entry.listId != null }
            val preExistingByUid = preExistingItems.associateBy { it.entry.uid }
            val importedItems = root.optJSONArray("todoItems") ?: JSONArray()
            val importedList = (0 until importedItems.length()).map { importedItems.getJSONObject(it) }
            val importedUids = importedList
                .mapNotNull { it.optStringOrNull("uid")?.takeIf { u -> u.isNotBlank() } }
                .toSet()

            itemsCreated = VoxSnapshotReplaceImporter.restore(
                mode = mode,
                imported = importedList,
                preExisting = preExistingItems,
                exportedAt = exportedAt,
                createdAtOf = { it.entry.createdAt },
                insert = insert@{ e ->
                    val importedLayerId = e.optLong("layerId")
                    val layerId = importedIdToLocalId[importedLayerId] ?: defaultLocalLayerId ?: return@insert 0L
                    val listId = importedListIdToLocalId[e.optLong("listId")] ?: return@insert 0L
                    val existing = e.optStringOrNull("uid")?.takeIf { it.isNotBlank() }?.let { preExistingByUid[it] }
                    val entryId = if (existing != null) {
                        replaceInPlace(e, existing, layerId, listId)
                    } else {
                        restoreEntry(e, layerId, listId)
                    }
                    if (entryId > 0) restoreAttachments(entryId, e)
                    entryId
                },
                delete = { if (it.entry.uid !in importedUids) calendarRepo.deleteEntryById(it.entry.id) }
            )
        }

        return "$entriesCreated entries and $itemsCreated to-do items imported, " +
            "$layersCreated new calendars (${importedLayers.length() - layersCreated} matched existing), " +
            "$listsCreated new to-do lists (${importedLists.length() - listsCreated} matched existing)"
    }
}

// Gson reflection over the whole data class, not a hand-maintained field list — a manual allowlist
// silently falls behind every time a new setting is added (this one was 8 of 25 fields before this
// fix). onboardingCompleted is the one deliberate exclusion (device-local UI state, not portable
// user data — see its own doc comment); reset to false on export rather than omitted from the JSON
// so import's Gson.fromJson always has every field present. Mirrors vox-commander's
// CommanderExportHandler, the only handler in this codebase that didn't suffer this drift.
private fun CalendarSettings.toJson(): JSONObject =
    JSONObject(VoxSettingsRoundTrip.toJson(copy(onboardingCompleted = false)))

/** Returns Room/DataStore defaults for [CalendarSettings] if [this] isn't valid JSON for it (e.g. a
 *  corrupt/foreign import file) — Gson's reflection deserializer can't throw its way past a
 *  structurally-valid-but-wrong-shape JSON object, so this fails safe to the all-defaults instance
 *  rather than a partially-null one. [scanLlmLevel]/[voiceLlmLevel] get an extra null-coalesce —
 *  Gson leaves them genuinely null when an older payload is missing the key entirely (see
 *  [VoxSettingsRoundTrip]'s doc), and restore writes them into DataStore non-null. */
private fun JSONObject.toCalendarSettings(): CalendarSettings =
    VoxSettingsRoundTrip.parseOrDefault(toString(), CalendarSettings::class.java, CalendarSettings()) { parsed ->
        parsed.copy(
            scanLlmLevel = parsed.scanLlmLevel ?: CalendarSettings().scanLlmLevel,
            voiceLlmLevel = parsed.voiceLlmLevel ?: CalendarSettings().voiceLlmLevel
        )
    }

private fun CalendarLayer.toJson(): JSONObject = JSONObject().apply {
    put("id", id)
    put("name", name)
    put("colorArgb", colorArgb)
    put("visible", visible)
    put("isDefault", isDefault)
    put("position", position)
    put("createdAt", createdAt)
    put("kind", kind.name)
    put("subscriptionUrl", subscriptionUrl)
    put("lastSyncedAt", lastSyncedAt)
    put("lastSyncError", lastSyncError)
    put("reminderOffsetsMinutes", reminderOffsetsMinutes)
}

private fun ToDoList.toJson(): JSONObject = JSONObject().apply {
    put("id", id)
    put("uid", uid)
    put("title", title)
    put("colorArgb", colorArgb)
    put("layerId", layerId)
    put("createdAt", createdAt)
    put("updatedAt", updatedAt)
    put("routineDaysMask", routineDaysMask)
    put("routineLastResetDay", routineLastResetDay)
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
    put("recurrenceDaysMask", e.recurrenceDaysMask)
    put("layerId", e.layerId)
    put("isImportant", e.isImportant)
    put("comments", e.comments)
    put("listId", e.listId)
    put("position", e.position)
    put("colorArgb", e.colorArgb)
    put("createdAt", e.createdAt)
    // The row's own edit timestamp and provenance ride along so a restore keeps the rows
    // comparable to (and recognizable by) a later device sync — see importData()'s uid
    // reconciliation.
    put("updatedAt", e.updatedAt)
    e.originDeviceId?.let { put("originDeviceId", it) }
    e.originDeviceName?.let { put("originDeviceName", it) }
    put("tags", JSONArray(tagNames))
    put("attachments", JSONArray(attachments.map { it.toBackupJson() }))
    put("reminders", JSONArray(reminderOffsetsMinutes))
}

