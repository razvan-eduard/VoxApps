package com.voxapps.calendarapp.domain.subscription

import com.voxapps.calendarapp.data.CalendarLayer
import com.voxapps.calendarapp.data.CalendarLayerKind
import com.voxapps.calendarapp.data.CalendarRepository
import com.voxapps.calendarapp.data.ReminderOffsetsCodec
import com.voxapps.calendarapp.domain.ics.IcsExportImportUtil
import com.voxapps.calendarapp.domain.ics.ParsedIcsEntry
import com.voxapps.logging.Logger
import java.io.InputStream

private const val TAG = "CalendarSubscriptionSyncEngine"

/**
 * Reconciles one subscribed [CalendarLayer] against its live .ics feed, diffing **by ICS UID** rather
 * than a blind delete-all-then-reinsert:
 * - a uid that disappeared upstream → [CalendarRepository.deleteEntryByUid] (still tombstones, for
 *   P2P sync — a blunt wipe-and-recreate would instead tombstone-and-recreate *every* entry on *every*
 *   periodic sync, forever, which is real continuous P2P noise for nothing).
 * - a uid whose content changed → updated **in place** (same row id), preserving the entry's own
 *   `individualReminderOffsetsMinutes` — anything that references an entry by id within a session
 *   (the widget's edit deep-link, the Day summary sheet) stays valid across a resync.
 * - an unchanged uid → left alone entirely (no DB write at all).
 * - a brand-new uid → inserted.
 */
object CalendarSubscriptionSyncEngine {

    suspend fun sync(repository: CalendarRepository, layer: CalendarLayer, fetch: suspend (String) -> InputStream) {
        require(layer.kind == CalendarLayerKind.SUBSCRIBED) { "sync() is only for SUBSCRIBED calendars" }
        val url = layer.subscriptionUrl ?: return
        try {
            val parsed = fetch(url).use { IcsExportImportUtil.read(it) }
            val fetchedByUid = parsed.associateBy { it.uid }
            val existingUids = repository.uidsForLayer(layer.id).toSet()

            for (removedUid in existingUids - fetchedByUid.keys) {
                repository.deleteEntryByUid(removedUid)
            }

            for ((uid, parsedEntry) in fetchedByUid) {
                val existingId = repository.getIdByUid(uid)
                if (existingId == null) {
                    insertNew(repository, layer.id, parsedEntry)
                } else {
                    updateIfChanged(repository, existingId, parsedEntry)
                }
            }

            repository.setSyncStatus(layer.id, lastSyncedAt = System.currentTimeMillis(), lastSyncError = null)
        } catch (e: Exception) {
            Logger.w(TAG, "Sync failed for calendar ${layer.id}", e)
            repository.setSyncStatus(layer.id, lastSyncedAt = null, lastSyncError = e.message ?: "Sync failed", keepLastSyncedAt = true)
        }
    }

    private suspend fun insertNew(repository: CalendarRepository, layerId: Long, e: ParsedIcsEntry) {
        repository.addEntry(
            uid = e.uid, type = e.type, title = e.title, description = e.description, location = e.location,
            startMillis = e.startMillis, endMillis = e.endMillis, allDay = e.allDay, completed = e.completed,
            recurrenceFrequency = e.recurrenceFrequency, recurrenceInterval = e.recurrenceInterval,
            recurrenceUntilMillis = e.recurrenceUntilMillis, layerId = layerId, tags = e.tags
        )
    }

    private suspend fun updateIfChanged(repository: CalendarRepository, existingId: Long, e: ParsedIcsEntry) {
        val current = repository.getEntryById(existingId) ?: return
        val updated = current.copy(
            type = e.type,
            title = e.title.trim(),
            description = e.description,
            location = e.location,
            startMillis = e.startMillis,
            endMillis = e.endMillis,
            allDay = e.allDay,
            completed = e.completed,
            recurrenceFrequency = e.recurrenceFrequency,
            recurrenceInterval = e.recurrenceInterval,
            recurrenceUntilMillis = e.recurrenceUntilMillis
        )
        if (updated == current) return // no-op — avoids a pointless write + reminder reschedule
        val individualOffsets = ReminderOffsetsCodec.decode(current.individualReminderOffsetsMinutes)
        repository.updateEntry(updated, e.tags, individualOffsets)
    }
}
