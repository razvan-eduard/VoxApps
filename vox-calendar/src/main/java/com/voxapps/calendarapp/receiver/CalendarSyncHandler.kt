package com.voxapps.calendarapp.receiver

import com.voxapps.calendarapp.data.CalendarEntry
import com.voxapps.calendarapp.data.CalendarEntryType
import com.voxapps.calendarapp.data.CalendarRepository
import com.voxapps.calendarapp.data.RecurrenceFrequency
import com.voxapps.calendarapp.data.ToDoRepository
import com.voxapps.calendarapp.data.preferences.CalendarSettingsRepository
import com.voxapps.calendarapp.state.SessionManager
import com.voxapps.datahygiene.SyncDeltaKeys
import com.voxapps.datahygiene.SyncIdentity
import com.voxapps.datahygiene.SyncPaging
import com.voxapps.datahygiene.planMerge
import com.voxapps.design.toEnumOrNull
import com.voxapps.ipc.VoxCommand
import com.voxapps.ipc.VoxResult
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import com.voxapps.design.color.VoxColorPalette

/**
 * Vox Hub's peer-to-peer sync for this app (see [VoxIpc.OP_SYNC_EXPORT]/[VoxIpc.OP_SYNC_MERGE]) —
 * deliberately separate from [CalendarExportImportHandler], which is a one-directional *restore*.
 * This is a *delta* merge: pages of entries changed since a watermark, reconciled via
 * [com.voxapps.datahygiene.planMerge]'s insert-if-new / last-write-wins / delete-on-tombstone
 * algorithm, never a blind overwrite.
 *
 * What the export volunteers is governed by the per-peer calendar scope the command carries
 * ([VoxCommand.scopeNames] — layer names, since layers are Calendar's organizing dimension; null
 * means every calendar, an EMPTY list none); records the user explicitly pushed ([VoxCommand.uids])
 * travel regardless of scope or watermark. Deltas are paged ([SyncPaging]) so a large first sync
 * crosses the binder boundary in bounded pieces.
 *
 * On the wire, every field the entity has travels, and links travel by NAME (layer, to-do list — a
 * local Room id has no meaning on another phone). A key holding an explicit JSON null means "this
 * field IS null" and overwrites; an ABSENT key means the sending build never knew the field, and
 * the merge keeps the local row's value — so an older peer's narrower delta can't blank fields it
 * never heard of. Tags travel *with* their entry (no sync identity of their own — the in-app edit
 * flow already replaces an entry's entire tag set atomically, so whichever version wins
 * last-write-wins carries its tags along).
 *
 * Rows a merge INSERTS are stamped with the sending device's identity
 * ([VoxCommand.sourceDeviceId]/[VoxCommand.sourceDeviceName]) as their provenance; an update never
 * rewrites an existing row's stamp — where a record came from doesn't change when it's edited.
 */
class CalendarSyncHandler(
    private val settingsRepo: CalendarSettingsRepository,
    private val sessionManager: SessionManager,
    private val calendarRepo: CalendarRepository,
    private val toDoRepository: ToDoRepository,
    private val lockedMessage: String
) {
    suspend fun export(command: VoxCommand): VoxResult {
        val settings = settingsRepo.getSnapshot()
        val locked = settings.isBiometricRequired &&
            !sessionManager.isSessionValid(settings.sessionTimeoutMinutes)
        if (locked) return VoxResult(ok = false, text = lockedMessage)

        val since = command.since ?: 0L
        // null = everything, empty = nothing — the wire contract; see VoxCommand.scopeNames.
        val scopeSet = command.scopeNames?.map { it.lowercase() }?.toSet()
        val layerNameById = calendarRepo.layers.first().associate { it.id to it.name }
        val listNameById = toDoRepository.lists.first().associate { it.id to it.title }

        val all = calendarRepo.entriesSnapshot()
        val continuous = all.filter { withTags ->
            withTags.entry.updatedAt > since && inLayerScope(withTags.entry, scopeSet, layerNameById)
        }
        val forcedUids = command.uids?.toSet().orEmpty()
        val forced = if (forcedUids.isEmpty()) emptyList() else all.filter { it.entry.uid in forcedUids }
        val candidates = (continuous + forced).distinctBy { it.entry.uid }
        val tombstones = calendarRepo.tombstonesSince(since)

        val page = SyncPaging.page(
            candidates, tombstones, command.cursor, command.limit,
            entryKey = { SyncPaging.Key(it.entry.updatedAt, it.entry.uid) },
            tombstoneKey = { SyncPaging.Key(it.deletedAt, it.uid) }
        )

        val json = JSONObject()
        json.put(
            SyncDeltaKeys.ENTRIES,
            JSONArray(page.entries.map {
                it.entry.toSyncJson(
                    layerNameById[it.entry.layerId],
                    it.entry.listId?.let { id -> listNameById[id] },
                    it.tagNames
                )
            })
        )
        json.put(SyncDeltaKeys.TOMBSTONES, JSONArray(page.tombstones.map {
            JSONObject().put(SyncDeltaKeys.UID, it.uid).put(SyncDeltaKeys.DELETED_AT, it.deletedAt)
        }))
        page.nextCursor?.let { json.put(SyncDeltaKeys.NEXT_CURSOR, it) }
        return VoxResult(ok = true, text = json.toString())
    }

    private fun inLayerScope(
        entry: CalendarEntry,
        scopeSet: Set<String>?,
        layerNameById: Map<Long, String>
    ): Boolean {
        if (scopeSet == null) return true
        val name = layerNameById[entry.layerId] ?: return false
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

        val local = calendarRepo.entriesSnapshot().map { it.entry }
        val localByUid = local.associateBy { it.uid }

        // Same auto-create-by-name convention CalendarExportImportHandler.import() already uses.
        val existingLayers = calendarRepo.layers.first().toMutableList()
        val layerNameToId = existingLayers.associate { it.name.lowercase() to it.id }.toMutableMap()
        val defaultLayerId = existingLayers.firstOrNull { it.isDefault }?.id ?: existingLayers.firstOrNull()?.id
        suspend fun layerIdFor(name: String): Long? =
            layerNameToId[name.lowercase()] ?: run {
                val newId = calendarRepo.addLayer(
                    name,
                    VoxColorPalette.unusedOrRandomColor(existingLayers.map { it.colorArgb }),
                    existingLayers.size
                )
                if (newId > 0) layerNameToId[name.lowercase()] = newId
                newId.takeIf { it > 0 }
            }

        // The local list a peer's list name lands on: exactly one list of that name, or a fresh one
        // created for it through the app's own create flow ([ToDoRepository.createList]). Two lists
        // already carrying the name is a real ambiguity and resolves to null (the caller keeps the
        // local link rather than guessing); the created list lands under the entry's own layer.
        val listIdsByName = mutableMapOf<String, MutableList<Long>>()
        for (list in toDoRepository.lists.first()) {
            listIdsByName.getOrPut(list.title.trim().lowercase()) { mutableListOf() } += list.id
        }
        suspend fun listIdFor(name: String, layerId: Long): Long? {
            val key = name.trim().lowercase().takeIf { it.isNotEmpty() } ?: return null
            listIdsByName[key]?.let { return it.singleOrNull() }
            val newId = toDoRepository.createList(name, layerId)
            if (newId > 0) listIdsByName[key] = mutableListOf(newId)
            return newId.takeIf { it > 0 }
        }

        val entriesJson = root.optJSONArray(SyncDeltaKeys.ENTRIES) ?: JSONArray()
        val remoteEntries = mutableListOf<CalendarEntry>()
        val remoteTagsByUid = mutableMapOf<String, List<String>?>()
        for (i in 0 until entriesJson.length()) {
            val e = entriesJson.getJSONObject(i)
            val localRow = localByUid[e.optString(SyncDeltaKeys.UID)]
            // An entry always belongs to a layer, so a delta carrying no resolvable layer keeps the
            // local one, and a fresh insert falls back to the default layer.
            val layerId = when {
                !e.has("layerName") || e.isNull("layerName") -> localRow?.layerId ?: defaultLayerId ?: -1L
                else -> layerIdFor(e.getString("layerName")) ?: localRow?.layerId ?: defaultLayerId ?: -1L
            }
            val listId = when {
                !e.has("listName") -> localRow?.listId
                e.isNull("listName") -> null
                else -> listIdFor(e.getString("listName"), layerId) ?: localRow?.listId
            }
            val entry = e.toCalendarEntry(localRow, layerId, listId).let {
                // Provenance is stamped exactly once, at insert; an update keeps the local stamp
                // (toCalendarEntry already copied it from localRow).
                if (localRow == null) it.copy(
                    originDeviceId = command.sourceDeviceId,
                    originDeviceName = command.sourceDeviceName
                ) else it
            }
            remoteTagsByUid[entry.uid] = e.toTagsOrNull()
            remoteEntries += entry
        }

        val tombstonesJson = root.optJSONArray(SyncDeltaKeys.TOMBSTONES) ?: JSONArray()
        val remoteTombstoneUids = (0 until tombstonesJson.length())
            .map { tombstonesJson.getJSONObject(it).optString(SyncDeltaKeys.UID) }
            .toSet()

        val plan = CalendarEntrySyncIdentity.planMerge(local, remoteEntries, remoteTombstoneUids)

        for (entry in plan.toInsert) calendarRepo.insertSyncedEntry(entry, remoteTagsByUid[entry.uid] ?: emptyList())
        for (entry in plan.toUpdate) {
            val localId = calendarRepo.getIdByUid(entry.uid) ?: continue
            calendarRepo.updateSyncedEntry(entry.copy(id = localId), remoteTagsByUid[entry.uid])
        }
        for (uid in plan.toDeleteUids) calendarRepo.deleteEntryByUid(uid)

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

private object CalendarEntrySyncIdentity : SyncIdentity<CalendarEntry> {
    override fun uidOf(record: CalendarEntry): String = record.uid
    override fun updatedAtOf(record: CalendarEntry): Long = record.updatedAt
}

/** Every nullable field is written as an explicit JSON null rather than omitted — on this wire,
 *  null and absent mean different things (see the class doc comment). */
private fun CalendarEntry.toSyncJson(
    /** Derived from the layer/list, sent so a peer can resolve the link by name. */
    layerName: String?,
    listName: String?,
    tags: List<String>
): JSONObject = JSONObject().apply {
    put(SyncDeltaKeys.UID, uid)
    put("type", type.name)
    put("title", title)
    putNullable("description", description)
    putNullable("location", location)
    putNullable("startMillis", startMillis)
    putNullable("endMillis", endMillis)
    put("allDay", allDay)
    put("completed", completed)
    put("recurrenceFrequency", recurrenceFrequency.name)
    put("recurrenceInterval", recurrenceInterval)
    putNullable("recurrenceUntilMillis", recurrenceUntilMillis)
    put("recurrenceDaysMask", recurrenceDaysMask)
    putNullable("layerName", layerName)
    putNullable("listName", listName)
    put("isImportant", isImportant)
    putNullable("comments", comments)
    put("position", position)
    putNullable("colorArgb", colorArgb)
    putNullable("individualReminderOffsetsMinutes", individualReminderOffsetsMinutes)
    put("tags", JSONArray(tags))
    put("createdAt", createdAt)
    put(SyncDeltaKeys.UPDATED_AT, updatedAt)
}

/** Null when the delta carries no "tags" key at all — an older build's entry, whose merge must
 *  leave the local tags untouched rather than clear them. */
private fun JSONObject.toTagsOrNull(): List<String>? {
    if (!has("tags")) return null
    val array = optJSONArray("tags") ?: return emptyList()
    return (0 until array.length()).map { array.optString(it) }
}

/**
 * The entry as a full local entity: keys the delta carries overwrite, keys it lacks fall back to
 * [local]'s values (a fresh insert falls back to the entity's own defaults). The resolved link ids
 * come in from the caller because resolving them needs the repository.
 */
private fun JSONObject.toCalendarEntry(
    local: CalendarEntry?,
    layerId: Long,
    listId: Long?
): CalendarEntry = CalendarEntry(
    uid = optString(SyncDeltaKeys.UID),
    type = if (has("type")) {
        optNullableString("type").toEnumOrNull<CalendarEntryType>() ?: local?.type ?: CalendarEntryType.EVENT
    } else {
        local?.type ?: CalendarEntryType.EVENT
    },
    title = if (has("title")) optString("title") else local?.title ?: "",
    description = stringOr("description") { local?.description },
    location = stringOr("location") { local?.location },
    startMillis = longOr("startMillis") { local?.startMillis },
    endMillis = longOr("endMillis") { local?.endMillis },
    allDay = if (has("allDay")) optBoolean("allDay", false) else local?.allDay ?: false,
    completed = if (has("completed")) optBoolean("completed", false) else local?.completed ?: false,
    recurrenceFrequency = if (has("recurrenceFrequency")) {
        optNullableString("recurrenceFrequency").toEnumOrNull<RecurrenceFrequency>()
            ?: local?.recurrenceFrequency ?: RecurrenceFrequency.NONE
    } else {
        local?.recurrenceFrequency ?: RecurrenceFrequency.NONE
    },
    recurrenceInterval = if (has("recurrenceInterval")) optInt("recurrenceInterval", 1) else local?.recurrenceInterval ?: 1,
    recurrenceUntilMillis = longOr("recurrenceUntilMillis") { local?.recurrenceUntilMillis },
    recurrenceDaysMask = if (has("recurrenceDaysMask")) optInt("recurrenceDaysMask", 0) else local?.recurrenceDaysMask ?: 0,
    layerId = layerId,
    isImportant = if (has("isImportant")) optBoolean("isImportant", false) else local?.isImportant ?: false,
    comments = stringOr("comments") { local?.comments },
    listId = listId,
    position = if (has("position")) optInt("position", 0) else local?.position ?: 0,
    colorArgb = longOr("colorArgb") { local?.colorArgb },
    originDeviceId = local?.originDeviceId,
    originDeviceName = local?.originDeviceName,
    createdAt = if (has("createdAt")) optLong("createdAt") else local?.createdAt ?: System.currentTimeMillis(),
    updatedAt = optLong(SyncDeltaKeys.UPDATED_AT),
    individualReminderOffsetsMinutes = stringOr("individualReminderOffsetsMinutes") { local?.individualReminderOffsetsMinutes }
)

private fun JSONObject.putNullable(key: String, value: Any?) {
    put(key, value ?: JSONObject.NULL)
}

private fun JSONObject.optNullableString(key: String): String? =
    if (has(key) && !isNull(key)) optString(key) else null

private inline fun JSONObject.stringOr(key: String, fallback: () -> String?): String? = when {
    !has(key) -> fallback()
    isNull(key) -> null
    else -> optString(key)
}

private inline fun JSONObject.longOr(key: String, fallback: () -> Long?): Long? = when {
    !has(key) -> fallback()
    isNull(key) -> null
    else -> optLong(key)
}
