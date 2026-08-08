package com.voxapps.calendarapp.receiver

import com.voxapps.calendarapp.data.CalendarEntry
import com.voxapps.calendarapp.data.CalendarEntryType
import com.voxapps.calendarapp.data.CalendarRepository
import com.voxapps.calendarapp.data.RecurrenceFrequency
import com.voxapps.calendarapp.data.preferences.CalendarSettingsRepository
import com.voxapps.calendarapp.state.SessionManager
import com.voxapps.datahygiene.SyncDeltaKeys
import com.voxapps.datahygiene.SyncIdentity
import com.voxapps.datahygiene.planMerge
import com.voxapps.design.toEnumOrNull
import com.voxapps.ipc.VoxResult
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import com.voxapps.design.color.VoxColorPalette

/**
 * Vox Hub's peer-to-peer sync for this app (see [VoxIpc.OP_SYNC_EXPORT]/[VoxIpc.OP_SYNC_MERGE]) —
 * deliberately separate from [CalendarExportImportHandler], which is a one-directional *restore*
 * (wipe pre-existing entries, insert a full snapshot verbatim). This is a *delta* merge: only entries
 * changed since a watermark, reconciled via [com.voxapps.datahygiene.planMerge]'s insert-if-new /
 * last-write-wins / delete-on-tombstone algorithm, never a blind overwrite. Layers travel by name, not
 * id (a local Room sequence has no meaning on another phone) — mirrors
 * [com.voxapps.expenses.receiver.ExpensesSyncHandler]'s identical shape; "scope" here means layer
 * names rather than category names, since layers are Calendar's organizing dimension.
 */
class CalendarSyncHandler(
    private val settingsRepo: CalendarSettingsRepository,
    private val sessionManager: SessionManager,
    private val calendarRepo: CalendarRepository
) {
    suspend fun export(since: Long, scopeNames: List<String>?): VoxResult {
        val settings = settingsRepo.getSnapshot()
        val locked = settings.isBiometricRequired &&
            !sessionManager.isSessionValid(settings.sessionTimeoutMinutes)
        if (locked) return VoxResult(ok = false, text = CalendarReadResponder.LOCKED_MESSAGE)

        val layerNameById = calendarRepo.layers.first().associate { it.id to it.name }
        val scopeSet = scopeNames?.takeIf { it.isNotEmpty() }?.map { it.lowercase() }?.toSet()

        val changed = calendarRepo.entriesSnapshot()
            .filter { it.entry.updatedAt > since }
            .filter { withTags ->
                if (scopeSet == null) return@filter true
                val name = layerNameById[withTags.entry.layerId] ?: return@filter false
                name.lowercase() in scopeSet
            }
        val tombstones = calendarRepo.tombstonesSince(since)

        val json = JSONObject()
        json.put(
            SyncDeltaKeys.ENTRIES,
            JSONArray(changed.map { it.entry.toSyncJson(layerNameById[it.entry.layerId], it.tagNames) })
        )
        json.put(SyncDeltaKeys.TOMBSTONES, JSONArray(tombstones.map { JSONObject().put(SyncDeltaKeys.UID, it.uid).put(SyncDeltaKeys.DELETED_AT, it.deletedAt) }))
        return VoxResult(ok = true, text = json.toString())
    }

    suspend fun merge(deltaJson: String): VoxResult {
        val settings = settingsRepo.getSnapshot()
        val locked = settings.isBiometricRequired &&
            !sessionManager.isSessionValid(settings.sessionTimeoutMinutes)
        if (locked) return VoxResult(ok = false, text = CalendarReadResponder.LOCKED_MESSAGE)

        val root = try {
            JSONObject(deltaJson)
        } catch (e: Exception) {
            return VoxResult(ok = false, text = "Invalid sync payload")
        }

        val existingLayers = calendarRepo.layers.first().toMutableList()
        val nameToId = existingLayers.associate { it.name.lowercase() to it.id }.toMutableMap()
        val defaultLayerId = existingLayers.firstOrNull { it.isDefault }?.id ?: existingLayers.firstOrNull()?.id

        val entriesJson = root.optJSONArray(SyncDeltaKeys.ENTRIES) ?: JSONArray()
        val remoteTagsByUid = mutableMapOf<String, List<String>>()
        val remoteEntries = (0 until entriesJson.length()).map { i ->
            val e = entriesJson.getJSONObject(i)
            val layerName = e.optNullableString("layerName")
            val layerId = layerName?.let { name ->
                nameToId[name.lowercase()] ?: run {
                    val newId = calendarRepo.addLayer(
                        name,
                        VoxColorPalette.unusedOrRandomColor(existingLayers.map { it.colorArgb }),
                        existingLayers.size
                    )
                    if (newId > 0) nameToId[name.lowercase()] = newId
                    newId.takeIf { it > 0 }
                }
            } ?: defaultLayerId ?: -1
            val tagsArray = e.optJSONArray("tags") ?: JSONArray()
            val tags = (0 until tagsArray.length()).map { tagsArray.optString(it) }
            val entry = e.toCalendarEntry(layerId)
            remoteTagsByUid[entry.uid] = tags
            entry
        }
        val tombstonesJson = root.optJSONArray(SyncDeltaKeys.TOMBSTONES) ?: JSONArray()
        val remoteTombstoneUids = (0 until tombstonesJson.length())
            .map { tombstonesJson.getJSONObject(it).optString(SyncDeltaKeys.UID) }
            .toSet()

        val local = calendarRepo.entriesSnapshot().map { it.entry }
        val plan = CalendarEntrySyncIdentity.planMerge(local, remoteEntries, remoteTombstoneUids)

        for (entry in plan.toInsert) calendarRepo.insertSyncedEntry(entry, remoteTagsByUid[entry.uid].orEmpty())
        for (entry in plan.toUpdate) {
            val localId = calendarRepo.getIdByUid(entry.uid) ?: continue
            calendarRepo.updateSyncedEntry(entry.copy(id = localId), remoteTagsByUid[entry.uid].orEmpty())
        }
        for (uid in plan.toDeleteUids) calendarRepo.deleteEntryByUid(uid)

        return VoxResult(
            ok = true,
            text = "${plan.toInsert.size} inserted, ${plan.toUpdate.size} updated, ${plan.toDeleteUids.size} deleted"
        )
    }
}

private object CalendarEntrySyncIdentity : SyncIdentity<CalendarEntry> {
    override fun uidOf(record: CalendarEntry): String = record.uid
    override fun updatedAtOf(record: CalendarEntry): Long = record.updatedAt
}

private fun CalendarEntry.toSyncJson(layerName: String?, tags: List<String>): JSONObject = JSONObject().apply {
    put(SyncDeltaKeys.UID, uid)
    put("type", type.name)
    put("title", title)
    put("description", description)
    put("location", location)
    put("startMillis", startMillis)
    put("endMillis", endMillis)
    put("allDay", allDay)
    put("completed", completed)
    put("recurrenceFrequency", recurrenceFrequency.name)
    put("recurrenceInterval", recurrenceInterval)
    put("recurrenceUntilMillis", recurrenceUntilMillis)
    put("layerName", layerName)
    put("tags", JSONArray(tags))
    put("createdAt", createdAt)
    put(SyncDeltaKeys.UPDATED_AT, updatedAt)
}

private fun JSONObject.toCalendarEntry(layerId: Long): CalendarEntry = CalendarEntry(
    uid = optString(SyncDeltaKeys.UID),
    type = optNullableString("type").toEnumOrNull<CalendarEntryType>()
        ?: CalendarEntryType.EVENT,
    title = optString("title"),
    description = optNullableString("description"),
    location = optNullableString("location"),
    startMillis = optLong("startMillis"),
    endMillis = if (has("endMillis") && !isNull("endMillis")) optLong("endMillis") else null,
    allDay = optBoolean("allDay", false),
    completed = optBoolean("completed", false),
    recurrenceFrequency = optNullableString("recurrenceFrequency")
        .toEnumOrNull<RecurrenceFrequency>()
        ?: RecurrenceFrequency.NONE,
    recurrenceInterval = if (has("recurrenceInterval")) optInt("recurrenceInterval", 1) else 1,
    recurrenceUntilMillis = if (has("recurrenceUntilMillis") && !isNull("recurrenceUntilMillis")) {
        optLong("recurrenceUntilMillis")
    } else {
        null
    },
    layerId = layerId,
    createdAt = optLong("createdAt"),
    updatedAt = optLong(SyncDeltaKeys.UPDATED_AT)
)

private fun JSONObject.optNullableString(key: String): String? =
    if (has(key) && !isNull(key)) optString(key) else null
