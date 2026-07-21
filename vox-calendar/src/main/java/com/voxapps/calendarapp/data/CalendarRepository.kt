package com.voxapps.calendarapp.data

import com.voxapps.textmatch.FuzzyNameMatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import java.util.UUID

/**
 * Single write point over the Room DAOs (mirrors vox-expenses' ExpensesRepository). CalendarStateManager
 * observes [entriesWithTags]/[layers] and calls the suspend writers.
 */
class CalendarRepository(
    private val entryDao: CalendarEntryDao,
    private val layerDao: CalendarLayerDao,
    private val tagDao: CalendarEntryTagDao
) {
    val entriesWithTags: Flow<List<CalendarEntryWithTags>> = entryDao.observeEntriesWithTags()
    val layers: Flow<List<CalendarLayer>> = layerDao.observeAll()
    val distinctTagNames: Flow<List<String>> = tagDao.observeDistinctTagNames()

    /** One-shot snapshot for the headless read/export path (Commander IPC). */
    suspend fun entriesSnapshot(): List<CalendarEntryWithTags> = entryDao.observeEntriesWithTags().first()
    suspend fun layersSnapshot(): List<CalendarLayer> = layerDao.observeAll().first()

    // --- ENTRIES ---

    suspend fun addEntry(
        uid: String = UUID.randomUUID().toString(),
        type: CalendarEntryType,
        title: String,
        description: String?,
        location: String?,
        startMillis: Long,
        endMillis: Long?,
        allDay: Boolean,
        completed: Boolean = false,
        recurrenceFrequency: RecurrenceFrequency = RecurrenceFrequency.NONE,
        recurrenceUntilMillis: Long? = null,
        layerId: Long,
        tags: List<String> = emptyList(),
        now: Long = System.currentTimeMillis()
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
                recurrenceFrequency = recurrenceFrequency,
                recurrenceUntilMillis = recurrenceUntilMillis,
                layerId = layerId,
                createdAt = now,
                updatedAt = now
            )
        )
        insertTags(id, tags)
        return id
    }

    /** Replaces an entry's fields and its full tag set (simplest correct update semantics). */
    suspend fun updateEntry(entry: CalendarEntry, tags: List<String>) {
        entryDao.update(entry.copy(updatedAt = System.currentTimeMillis()))
        tagDao.deleteAllForEntry(entry.id)
        insertTags(entry.id, tags)
    }

    suspend fun deleteEntry(entry: CalendarEntry) {
        entryDao.delete(entry)
        entryDao.insertTombstone(CalendarEntryTombstone(entry.uid, System.currentTimeMillis()))
    }

    suspend fun deleteEntryById(id: Long) {
        val uid = entryDao.getUidById(id)
        entryDao.deleteById(id)
        if (uid != null) entryDao.insertTombstone(CalendarEntryTombstone(uid, System.currentTimeMillis()))
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
        val existingLayers = layerDao.observeAll().first()
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

    /**
     * Deleting a layer reassigns its entries to the (single, always-present) default layer rather than
     * orphaning them — [CalendarEntry.layerId] is non-nullable, unlike Notes'/Expenses' category ids.
     * The default layer itself can never be deleted (enforced by the caller not offering the option;
     * this function trusts it wasn't called for the default layer).
     */
    suspend fun deleteLayer(layer: CalendarLayer) {
        val fallback = layerDao.observeAll().first().firstOrNull { it.isDefault && it.id != layer.id }
            ?: return
        entryDao.reassignLayer(layer.id, fallback.id)
        layerDao.delete(layer)
    }
}
