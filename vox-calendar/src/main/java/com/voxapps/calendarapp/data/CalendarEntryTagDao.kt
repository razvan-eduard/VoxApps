package com.voxapps.calendarapp.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface CalendarEntryTagDao {
    @Query("SELECT * FROM calendar_entry_tags WHERE entryId = :entryId")
    fun observeForEntry(entryId: Long): Flow<List<CalendarEntryTag>>

    @Query("SELECT DISTINCT tagName FROM calendar_entry_tags ORDER BY tagName ASC")
    fun observeDistinctTagNames(): Flow<List<String>>

    @Insert
    suspend fun insertAll(tags: List<CalendarEntryTag>)

    @Query("DELETE FROM calendar_entry_tags WHERE entryId = :entryId")
    suspend fun deleteAllForEntry(entryId: Long)
}
