package com.voxapps.calendarapp.data

import android.content.Context
import com.voxapps.attachments.AttachmentDao
import com.voxapps.calendarapp.domain.reminders.ReminderScheduler
import com.voxapps.logging.Logger
import com.voxapps.textmatch.FuzzyNameMatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import java.util.UUID

/**
 * Single write point over the Room DAOs (mirrors vox-expenses' ExpensesRepository). CalendarStateManager
 * observes [entriesWithTags]/[layers] and calls the suspend writers.
 */
class CalendarRepository(
    private val entryDao: CalendarEntryDao,
    private val layerDao: CalendarLayerDao,
    private val tagDao: CalendarEntryTagDao,
    private val attachmentDao: AttachmentDao,
    private val reminderDao: CalendarReminderDao,
    private val toDoListDao: ToDoListDao,
    private val appContext: Context,
    /** The non-database side effects of writing (alarms, attachment files). Defaulted so
     *  production wiring is unchanged; tests substitute a fake instead of shaping fixtures to
     *  dodge AlarmManager. See [CalendarPlatformEffects]. */
    private val effects: CalendarPlatformEffects = AndroidCalendarPlatformEffects(appContext)
) {
    // All three are deduped structurally because Room re-runs an observed query on *any* write to
    // the tables it touches, not only one that changes the result. Every emission here re-triggers
    // CalendarStateManager's five-way combine, which rebuilds the whole UI state and ultimately
    // feeds RecurrenceExpander over a multi-year window — by far the most expensive chain in the
    // app to run for no change. distinctTagNames is the sharpest case: it is a SELECT DISTINCT, and
    // saving any entry rewrites its tag rows (deleteAllForEntry + insertAll), so the identical name
    // list is re-emitted on essentially every save.
    val entriesWithTags: Flow<List<CalendarEntryWithTags>> =
        entryDao.observeEntriesWithTags().distinctUntilChanged()
    val layers: Flow<List<CalendarLayer>> = layerDao.observeAll().distinctUntilChanged()
    val distinctTagNames: Flow<List<String>> = tagDao.observeDistinctTagNames().distinctUntilChanged()

    /** One-shot snapshot for the headless read/export path (Commander IPC). */
    suspend fun entriesSnapshot(): List<CalendarEntryWithTags> = entryDao.getEntriesWithTags()
    suspend fun layersSnapshot(): List<CalendarLayer> = layerDao.getAll()

    // --- ENTRIES ---

    suspend fun addEntry(
        uid: String = UUID.randomUUID().toString(),
        type: CalendarEntryType,
        title: String,
        description: String?,
        location: String?,
        // Nullable so a restored to-do item with no due date (see CalendarEntry's doc comment) can
        // be created through this same path — every existing plain Event/Task caller already always
        // passes a real value here, so this widening is source-compatible for them.
        startMillis: Long?,
        endMillis: Long?,
        allDay: Boolean,
        completed: Boolean = false,
        isImportant: Boolean = false,
        recurrenceFrequency: RecurrenceFrequency = RecurrenceFrequency.NONE,
        recurrenceInterval: Int = 1,
        recurrenceUntilMillis: Long? = null,
        layerId: Long,
        tags: List<String> = emptyList(),
        reminderOffsetsMinutes: List<Int> = emptyList(),
        now: Long = System.currentTimeMillis(),
        // To-do-specific fields (see CalendarEntry's doc comment) — null/default for a plain
        // Event/Task. Exist here (rather than a separate ToDoRepository insert path) purely so the
        // Hub export/import handler can restore a to-do item through the same single write point
        // as everything else (tags/reminders wiring), without duplicating that logic.
        listId: Long? = null,
        position: Int = 0,
        colorArgb: Long? = null,
        comments: String? = null
    ): Long {
        val id = entryDao.insert(
            CalendarEntry(
                uid = uid,
                type = type,
                title = title.trim(),
                description = description?.trim()?.takeIf { it.isNotEmpty() },
                location = location?.trim()?.takeIf { it.isNotEmpty() },
                startMillis = startMillis,
                endMillis = endMillis,
                allDay = allDay,
                completed = completed,
                isImportant = isImportant,
                recurrenceFrequency = recurrenceFrequency,
                recurrenceInterval = recurrenceInterval,
                recurrenceUntilMillis = recurrenceUntilMillis,
                layerId = layerId,
                listId = listId,
                position = position,
                colorArgb = colorArgb,
                comments = comments?.trim()?.takeIf { it.isNotEmpty() },
                createdAt = now,
                updatedAt = now,
                individualReminderOffsetsMinutes = ReminderOffsetsCodec.encode(reminderOffsetsMinutes)
            )
        )
        insertTags(id, tags)
        replaceReminders(id, effectiveOffsetsFor(layerId, reminderOffsetsMinutes))
        return id
    }

    /** Replaces an entry's fields, its full tag set, and its reminders (simplest correct update
     *  semantics — mirrors the tag replacement already done here). [reminderOffsetsMinutes] is always
     *  stored as this entry's own individual preference, even while a calendar-level override means
     *  it's not what's actually scheduled right now (see [effectiveOffsetsFor]). */
    suspend fun updateEntry(entry: CalendarEntry, tags: List<String>, reminderOffsetsMinutes: List<Int> = emptyList()) {
        entryDao.update(
            entry.copy(
                updatedAt = System.currentTimeMillis(),
                individualReminderOffsetsMinutes = ReminderOffsetsCodec.encode(reminderOffsetsMinutes)
            )
        )
        tagDao.deleteAllForEntry(entry.id)
        insertTags(entry.id, tags)
        replaceReminders(entry.id, effectiveOffsetsFor(entry.layerId, reminderOffsetsMinutes))
    }

    suspend fun deleteEntry(entry: CalendarEntry) {
        entryDao.delete(entry)
        entryDao.insertTombstone(CalendarEntryTombstone(entry.uid, System.currentTimeMillis()))
        deleteAttachmentsFor(entry.id)
        cancelReminders(entry.id)
    }

    suspend fun deleteEntryById(id: Long) {
        val uid = entryDao.getUidById(id)
        entryDao.deleteById(id)
        if (uid != null) entryDao.insertTombstone(CalendarEntryTombstone(uid, System.currentTimeMillis()))
        deleteAttachmentsFor(id)
        cancelReminders(id)
    }

    // --- Multi-select batch actions (Day/Week view) ---

    /** Loops [deleteEntryById] rather than a raw bulk `DELETE` — each entry's tombstone (P2P sync) and
     *  attachment/reminder cleanup must still happen per row, exactly as a single delete would. */
    suspend fun bulkDeleteEntries(ids: List<Long>) {
        for (id in ids) deleteEntryById(id)
    }

    /** Reassigns every id to [newLayerId] in one query (safe here — unlike delete, a plain layer
     *  reassignment has no other per-row side effect to preserve), then reschedules each moved
     *  entry's reminders against its *new* calendar — otherwise a moved entry would keep whatever
     *  alarms its old calendar's [effectiveOffsetsFor] had set up. */
    suspend fun bulkMoveEntries(ids: List<Long>, newLayerId: Long) {
        if (ids.isEmpty()) return
        entryDao.bulkReassignLayer(ids, newLayerId, System.currentTimeMillis())
        for (id in ids) {
            val entry = entryDao.getById(id) ?: continue
            val individual = ReminderOffsetsCodec.decode(entry.individualReminderOffsetsMinutes)
            replaceReminders(id, effectiveOffsetsFor(newLayerId, individual))
        }
    }

    // --- REMINDERS (see :domain/reminders/ReminderScheduler; non-recurring entries only, v1) ---

    suspend fun getEntryById(id: Long): CalendarEntry? = entryDao.getById(id)

    suspend fun getRemindersForEntry(entryId: Long): List<CalendarReminder> = reminderDao.getForEntry(entryId)

    suspend fun getReminderById(id: Long): CalendarReminder? = reminderDao.getById(id)

    suspend fun getAllReminders(): List<CalendarReminder> = reminderDao.getAll()

    /** The offsets that should actually be scheduled for an entry under [layerId]: its calendar's own
     *  [CalendarLayer.reminderOffsetsMinutes] if non-empty ("ON" — overrides every entry in that
     *  calendar, even a [CalendarLayerKind.SUBSCRIBED] one), else [individualOffsets] (the entry's own
     *  choice) unchanged. */
    private suspend fun effectiveOffsetsFor(layerId: Long, individualOffsets: List<Int>): List<Int> {
        val layer = layerDao.getById(layerId)
        val calendarOffsets = layer?.reminderOffsetsMinutes?.let(ReminderOffsetsCodec::decode) ?: emptyList()
        return calendarOffsets.ifEmpty { individualOffsets }
    }

    /** Sets a calendar's reminder-offset override and immediately reschedules every entry currently
     *  under it to match the new effective result — the only way turning a calendar's reminders ON,
     *  OFF, or editing the offset list takes effect for entries that already existed before the change,
     *  not just future ones. */
    suspend fun setLayerReminderOffsets(layerId: Long, offsets: List<Int>) {
        val layer = layerDao.getById(layerId) ?: return
        layerDao.update(layer.copy(reminderOffsetsMinutes = ReminderOffsetsCodec.encode(offsets)))
        for (entryId in entryDao.getIdsForLayer(layerId)) {
            val entry = entryDao.getById(entryId) ?: continue
            val individual = ReminderOffsetsCodec.decode(entry.individualReminderOffsetsMinutes)
            replaceReminders(entryId, effectiveOffsetsFor(layerId, individual))
        }
    }

    /** Cancels + deletes every existing reminder for [entryId], then inserts+schedules one row per
     *  offset in [offsetsMinutes] against the entry's current (just-saved) start time. Used by both
     *  create (offsets usually empty) and update (offsets may have changed). */
    private suspend fun replaceReminders(entryId: Long, offsetsMinutes: List<Int>) {
        cancelReminders(entryId)
        if (offsetsMinutes.isEmpty()) return
        val entry = entryDao.getById(entryId) ?: return
        for (offset in offsetsMinutes.distinct()) {
            val reminder = CalendarReminder(entryId = entryId, offsetMinutesBefore = offset)
            val reminderId = reminderDao.insert(reminder)
            effects.scheduleReminder(reminder.copy(id = reminderId), entry)
        }
    }

    private suspend fun cancelReminders(entryId: Long) {
        val removed = reminderDao.deleteForEntry(entryId)
        for (reminder in removed) {
            effects.cancelReminder(reminder.id)
        }
    }

    /** Best-effort cleanup of this entry's attachments (see :core:attachments) — a file-delete
     *  failure never blocks/rolls back the DB delete. */
    private suspend fun deleteAttachmentsFor(entryId: Long) {
        val rows = attachmentDao.deleteAllFor(CalendarAttachments.RECORD_TYPE, entryId)
        for (row in rows) {
            try {
                if (attachmentDao.countByFileName(CalendarAttachments.RECORD_TYPE, row.fileName) == 0) {
                    effects.deleteAttachmentFile(CalendarAttachments.DIR, row.fileName)
                }
            } catch (e: Exception) {
                Logger.w("CalendarRepository", "Failed to delete attachment file for entry $entryId", e)
            }
        }
    }

    // --- Peer-to-peer sync (see :core:datahygiene's SyncMerge and CalendarSyncHandler) ---

    suspend fun tombstonesSince(since: Long): List<CalendarEntryTombstone> = entryDao.getTombstonesSince(since)

    suspend fun getIdByUid(uid: String): Long? = entryDao.getIdByUid(uid)

    /** Insert-side of a sync merge: preserves [entry]'s uid/createdAt/updatedAt verbatim — unlike
     *  [addEntry], which always mints a fresh uid and stamps both timestamps to "now" (correct for a
     *  locally *created* entry, wrong for one being replicated from a peer that already has real sync
     *  identity). [tags] come from the peer's delta, same as [entry]'s other fields. */
    suspend fun insertSyncedEntry(entry: CalendarEntry, tags: List<String>): Long {
        val id = entryDao.insert(entry.copy(id = 0))
        insertTags(id, tags)
        return id
    }

    /** Update-side of a sync merge: [entry] must already carry the *local* row's id (resolved via
     *  [getIdByUid] before calling this) — every other field, including updatedAt, comes from the
     *  peer's newer version, since it won the last-write-wins comparison that got us here. */
    suspend fun updateSyncedEntry(entry: CalendarEntry, tags: List<String>) {
        entryDao.update(entry)
        tagDao.deleteAllForEntry(entry.id)
        insertTags(entry.id, tags)
    }

    /** Applies an incoming sync tombstone: deletes the local row by uid (a no-op if it's already gone
     *  or was never synced here) via the normal [deleteEntryById] path, so a fresh local tombstone is
     *  written too — letting the deletion propagate transitively to a third device. */
    suspend fun deleteEntryByUid(uid: String) {
        val id = entryDao.getIdByUid(uid) ?: return
        deleteEntryById(id)
    }

    private suspend fun insertTags(entryId: Long, tags: List<String>) {
        val clean = tags.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (clean.isNotEmpty()) {
            tagDao.insertAll(clean.map { CalendarEntryTag(entryId = entryId, tagName = it) })
        }
    }

    /**
     * Headless entry insert from a parsed voice/LLM result: resolves the spoken/suggested layer name
     * (exact match, then fuzzy match via the shared `:core:textmatch` resolver — same algorithm
     * vox-expenses/vox-notes use for category resolution) or the configured default, saves, and
     * returns the resolved layer id/name so the caller can toast it.
     */
    suspend fun addParsedEntry(
        type: CalendarEntryType,
        title: String,
        description: String?,
        location: String?,
        startMillis: Long,
        endMillis: Long?,
        allDay: Boolean,
        tags: List<String>,
        spokenLayer: String?,
        defaultLayerId: Long?,
        autoCreateLayer: Boolean
    ): FuzzyNameMatcher.Resolved {
        val existingLayers = layerDao.getAll()
        var resolved = FuzzyNameMatcher.resolve(
            spokenName = spokenLayer,
            candidates = existingLayers.map { FuzzyNameMatcher.Candidate(it.id, it.name) },
            defaultId = defaultLayerId ?: existingLayers.firstOrNull { it.isDefault }?.id
        )

        val spoken = spokenLayer?.trim()?.takeIf { it.isNotEmpty() }
        if (resolved.id == null && autoCreateLayer && spoken != null) {
            val id = addLayer(spoken, CalendarLayerPalette.unusedOrRandomColor(existingLayers.map { it.colorArgb }), existingLayers.size)
            if (id > 0) resolved = FuzzyNameMatcher.Resolved(id, spoken)
        }

        val layerId = resolved.id ?: existingLayers.firstOrNull { it.isDefault }?.id ?: existingLayers.firstOrNull()?.id
            ?: error("No calendar layer exists to assign this entry to")
        addEntry(
            type = type,
            title = title,
            description = description,
            location = location,
            startMillis = startMillis,
            endMillis = endMillis,
            allDay = allDay,
            layerId = layerId,
            tags = tags
        )
        return resolved
    }

    // --- LAYERS ---

    suspend fun addLayer(name: String, colorArgb: Long, position: Int, isDefault: Boolean = false): Long {
        val clean = name.trim()
        if (clean.isEmpty()) return -1
        return layerDao.insert(
            CalendarLayer(
                name = clean,
                colorArgb = colorArgb,
                position = position,
                isDefault = isDefault,
                createdAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun updateLayer(layer: CalendarLayer) = layerDao.update(layer)

    /** Promotes [layerId] to the Main calendar, demoting whichever one currently holds the flag —
     *  [CalendarLayer.isDefault] is a "exactly one row carries this" invariant enforced here rather
     *  than by the schema (see its doc comment), so the demote must happen in the same pass. Once
     *  demoted, the old Main becomes deletable like any other calendar. A [CalendarLayerKind
     *  .SUBSCRIBED] calendar can never be Main: it's read-only, so new entries (which fall back to
     *  Main whenever no calendar is specified) would have nowhere valid to land. */
    suspend fun setMainLayer(layerId: Long) {
        val layers = layerDao.getAll()
        val target = layers.firstOrNull { it.id == layerId } ?: return
        if (target.kind == CalendarLayerKind.SUBSCRIBED || target.isDefault) return
        layers.filter { it.isDefault }.forEach { layerDao.update(it.copy(isDefault = false)) }
        layerDao.update(target.copy(isDefault = true))
    }

    /** Persists a user-dragged calendar order — [orderedIds] is the full list in its new order, and
     *  each row's [CalendarLayer.position] is rewritten to its index (the field `CalendarLayerDao
     *  .observeAll` already sorts by). */
    suspend fun reorderLayers(orderedIds: List<Long>) {
        val byId = layerDao.getAll().associateBy { it.id }
        orderedIds.forEachIndexed { index, id ->
            val layer = byId[id] ?: return@forEachIndexed
            if (layer.position != index) layerDao.update(layer.copy(position = index))
        }
    }

    /** Full-fidelity layer creation for Hub/local backup restore — unlike [addLayer] (used by
     *  voice/LLM creation and the Sidebar's "+" flow, which only ever produce a plain LOCAL calendar),
     *  this preserves a subscribed calendar's kind/URL/sync status and any calendar-level reminder
     *  override across an export/import round-trip. Never triggers an immediate sync itself — a
     *  restored SUBSCRIBED calendar picks back up on the next periodic
     *  [com.voxapps.calendarapp.domain.subscription.CalendarSubscriptionSyncWorker] tick, or a manual
     *  "Resync now". */
    suspend fun addLayerFromBackup(
        name: String,
        colorArgb: Long,
        position: Int,
        kind: CalendarLayerKind,
        subscriptionUrl: String?,
        lastSyncedAt: Long?,
        lastSyncError: String?,
        reminderOffsetsMinutes: String
    ): Long {
        val clean = name.trim()
        if (clean.isEmpty()) return -1
        return layerDao.insert(
            CalendarLayer(
                name = clean,
                colorArgb = colorArgb,
                position = position,
                kind = kind,
                subscriptionUrl = subscriptionUrl,
                lastSyncedAt = lastSyncedAt,
                lastSyncError = lastSyncError,
                reminderOffsetsMinutes = reminderOffsetsMinutes,
                createdAt = System.currentTimeMillis()
            )
        )
    }

    /**
     * Deleting a non-default calendar always asks which of these two to do (see [Sidebar]'s
     * confirmation dialog) rather than following an automatic rule — [CalendarEntry.layerId] is
     * non-nullable, unlike Notes'/Expenses' category ids, so entries can never be silently orphaned.
     * The Main calendar itself can never be deleted (enforced by the caller not offering the option;
     * this function trusts it wasn't called for the default/Main layer).
     */
    enum class LayerDeleteMode { REASSIGN_TO_MAIN, DELETE_ALL_ENTRIES }

    suspend fun deleteLayer(layer: CalendarLayer, mode: LayerDeleteMode) {
        when (mode) {
            LayerDeleteMode.REASSIGN_TO_MAIN -> {
                val fallback = layerDao.getAll().firstOrNull { it.isDefault && it.id != layer.id }
                    ?: return
                entryDao.reassignLayer(layer.id, fallback.id)
                toDoListDao.reassignLayer(layer.id, fallback.id)
            }
            LayerDeleteMode.DELETE_ALL_ENTRIES -> {
                for (id in entryDao.getIdsForLayer(layer.id)) deleteEntryById(id)
                for (list in toDoListDao.getAllForLayer(layer.id)) toDoListDao.delete(list)
            }
        }
        layerDao.delete(layer)
    }

    // --- Online-subscribed calendars (see domain/subscription/CalendarSubscriptionSyncEngine) ---

    suspend fun uidsForLayer(layerId: Long): List<String> = entryDao.getUidsForLayer(layerId)

    suspend fun addSubscribedLayer(name: String, colorArgb: Long, position: Int, url: String): Long {
        val clean = name.trim()
        if (clean.isEmpty()) return -1
        return layerDao.insert(
            CalendarLayer(
                name = clean,
                colorArgb = colorArgb,
                position = position,
                kind = CalendarLayerKind.SUBSCRIBED,
                subscriptionUrl = url.trim(),
                createdAt = System.currentTimeMillis()
            )
        )
    }

    /** [keepLastSyncedAt] = true on a failed sync so one transient failure doesn't erase an
     *  old-but-working "last synced" timestamp — only [lastSyncError] gets set in that case. */
    suspend fun setSyncStatus(layerId: Long, lastSyncedAt: Long?, lastSyncError: String?, keepLastSyncedAt: Boolean = false) {
        val layer = layerDao.getById(layerId) ?: return
        layerDao.update(
            layer.copy(
                lastSyncedAt = if (keepLastSyncedAt) layer.lastSyncedAt else lastSyncedAt,
                lastSyncError = lastSyncError
            )
        )
    }

    /** Snapshots every entry under [source] into a brand-new LOCAL calendar named [newName] — fresh
     *  uids/ids/timestamps per entry (never reuses [source]'s ICS uids, so a future resync of
     *  [source] can never touch this copy) and copied tags; reminders are NOT copied, since a
     *  reminder is tied to "this device, this row" and the user can set new ones on the copy like
     *  any local entry. Returns the new calendar's id. */
    suspend fun duplicateLayerToOfflineCopy(source: CalendarLayer, newName: String): Long {
        val existingLayers = layerDao.getAll()
        val newColor = CalendarLayerPalette.unusedOrRandomColor(existingLayers.map { it.colorArgb })
        val newLayerId = addLayer(newName, newColor, existingLayers.size)
        if (newLayerId <= 0) return newLayerId
        for (ewt in entryDao.getEntriesWithTags()) {
            if (ewt.entry.layerId != source.id) continue
            val e = ewt.entry
            addEntry(
                type = e.type, title = e.title, description = e.description, location = e.location,
                startMillis = e.startMillis, endMillis = e.endMillis, allDay = e.allDay, completed = e.completed,
                isImportant = e.isImportant, recurrenceFrequency = e.recurrenceFrequency,
                recurrenceInterval = e.recurrenceInterval, recurrenceUntilMillis = e.recurrenceUntilMillis,
                layerId = newLayerId, tags = ewt.tagNames
            )
        }
        return newLayerId
    }
}
