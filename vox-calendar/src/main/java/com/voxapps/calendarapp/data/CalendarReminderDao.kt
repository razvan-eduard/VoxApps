package com.voxapps.calendarapp.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface CalendarReminderDao {
    @Query("SELECT * FROM reminders WHERE entryId = :entryId")
    suspend fun getForEntry(entryId: Long): List<CalendarReminder>

    @Query("SELECT * FROM reminders")
    suspend fun getAll(): List<CalendarReminder>

    @Insert
    suspend fun insert(reminder: CalendarReminder): Long

    /** For cascade-safe entry deletion/replacement — returns the deleted rows so the caller can
     *  also cancel their scheduled alarms (this DAO never touches AlarmManager itself). */
    suspend fun deleteForEntry(entryId: Long): List<CalendarReminder> {
        val rows = getForEntry(entryId)
        deleteForEntryInternal(entryId)
        return rows
    }

    @Query("DELETE FROM reminders WHERE entryId = :entryId")
    suspend fun deleteForEntryInternal(entryId: Long)
}
