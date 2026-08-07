package com.voxapps.calendarapp.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface CalendarEntryDao {
    /**
     * All date-bearing entries joined with their tags, earliest start first — `startMillis IS NOT
     * NULL` excludes dateless to-do checklist items (see [CalendarEntry.listId]'s doc comment), which
     * have nothing to sort/bucket into a grid position by. Layer/tag/visibility filtering and
     * recurrence expansion happen in the state/UI layer (mirrors vox-expenses' ExpenseDao), so this
     * query stays simple. This also backs the headless read/export/sync paths (`entriesSnapshot`),
     * which excludes dateless to-do items the same way for the same reason.
     */
    @Transaction
    @Query("SELECT * FROM calendar_entries WHERE startMillis IS NOT NULL ORDER BY startMillis ASC")
    fun observeEntriesWithTags(): Flow<List<CalendarEntryWithTags>>

    /** One-shot read for the write/export paths — `observeAll().first()` would spin up an
     *  InvalidationTracker observer, run the query, then tear it all down again. */
    @androidx.room.Transaction
    @Query("SELECT * FROM calendar_entries WHERE startMillis IS NOT NULL ORDER BY startMillis ASC")
    suspend fun getEntriesWithTags(): List<CalendarEntryWithTags>

    @Query("SELECT * FROM calendar_entries ORDER BY startMillis ASC")
    fun observeAll(): Flow<List<CalendarEntry>>

    /** A to-do list's items, in their user-arranged order — includes dateless items (unlike
     *  [observeEntriesWithTags], which is grid-oriented and excludes them). */
    @Query("SELECT * FROM calendar_entries WHERE listId = :listId ORDER BY position ASC")
    fun observeForList(listId: Long): Flow<List<CalendarEntry>>

    @Query("SELECT * FROM calendar_entries WHERE listId = :listId ORDER BY position ASC")
    suspend fun getForList(listId: Long): List<CalendarEntry>

    @Insert
    suspend fun insert(entry: CalendarEntry): Long

    /** Point lookup for contexts with no in-memory observed state to filter — e.g. ReminderReceiver
     *  firing outside any active ViewModel/UI. */
    @Query("SELECT * FROM calendar_entries WHERE id = :id")
    suspend fun getById(id: Long): CalendarEntry?

    /** Reassigns all entries from a layer being deleted to the surviving default layer. */
    @Query("UPDATE calendar_entries SET layerId = :newLayerId WHERE layerId = :oldLayerId")
    suspend fun reassignLayer(oldLayerId: Long, newLayerId: Long)

    /** Every uid currently under [layerId] — a subscription sync's diff base (see
     *  CalendarSubscriptionSyncEngine) and the delete-all branch of layer deletion. */
    @Query("SELECT uid FROM calendar_entries WHERE layerId = :layerId")
    suspend fun getUidsForLayer(layerId: Long): List<String>

    /** Ids under [layerId] — used by the hard-delete branch of layer deletion. */
    @Query("SELECT id FROM calendar_entries WHERE layerId = :layerId")
    suspend fun getIdsForLayer(layerId: Long): List<Long>

    /** Bulk "move to calendar" for multi-select — safe as one UPDATE (unlike delete) since a layer
     *  reassignment has no other per-row side effect (no tombstone, no attachment/reminder cleanup)
     *  that a raw bulk query would skip. */
    @Query("UPDATE calendar_entries SET layerId = :newLayerId, updatedAt = :now WHERE id IN (:ids)")
    suspend fun bulkReassignLayer(ids: List<Long>, newLayerId: Long, now: Long)

    @Query("DELETE FROM calendar_entries WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Update
    suspend fun update(entry: CalendarEntry)

    @Delete
    suspend fun delete(entry: CalendarEntry)

    // --- Sync tombstones (see CalendarEntryTombstone) ---

    /** Read the uid before a delete-by-id, since the row won't exist to query afterwards. */
    @Query("SELECT uid FROM calendar_entries WHERE id = :id")
    suspend fun getUidById(id: Long): String?

    /** Resolves a peer-to-peer sync delta's uid back to this device's own local row id — needed
     *  before an update (preserve the local id) or a delete-by-uid (Room has no delete-by-uid). */
    @Query("SELECT id FROM calendar_entries WHERE uid = :uid")
    suspend fun getIdByUid(uid: String): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTombstone(tombstone: CalendarEntryTombstone)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTombstones(tombstones: List<CalendarEntryTombstone>)

    @Query("SELECT * FROM calendar_entry_tombstones WHERE deletedAt > :since")
    suspend fun getTombstonesSince(since: Long): List<CalendarEntryTombstone>

    @Query("DELETE FROM calendar_entry_tombstones WHERE deletedAt < :before")
    suspend fun deleteStaleTombstones(before: Long)
}
