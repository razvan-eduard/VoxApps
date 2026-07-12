package com.voxapps.calendarapp.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface CalendarEntryDao {
    /**
     * All entries joined with their tags, earliest start first. Layer/tag/visibility filtering and
     * recurrence expansion happen in the state/UI layer (mirrors vox-expenses' ExpenseDao), so this
     * query stays simple.
     */
    @Transaction
    @Query("SELECT * FROM calendar_entries ORDER BY startMillis ASC")
    fun observeEntriesWithTags(): Flow<List<CalendarEntryWithTags>>

    @Query("SELECT * FROM calendar_entries ORDER BY startMillis ASC")
    fun observeAll(): Flow<List<CalendarEntry>>

    @Insert
    suspend fun insert(entry: CalendarEntry): Long

    /** Reassigns all entries from a layer being deleted to the surviving default layer. */
    @Query("UPDATE calendar_entries SET layerId = :newLayerId WHERE layerId = :oldLayerId")
    suspend fun reassignLayer(oldLayerId: Long, newLayerId: Long)

    @Query("DELETE FROM calendar_entries WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Update
    suspend fun update(entry: CalendarEntry)

    @Delete
    suspend fun delete(entry: CalendarEntry)
}
