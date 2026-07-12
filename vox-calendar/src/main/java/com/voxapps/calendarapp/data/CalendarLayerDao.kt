package com.voxapps.calendarapp.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface CalendarLayerDao {
    @Query("SELECT * FROM calendar_layers ORDER BY position ASC, createdAt ASC")
    fun observeAll(): Flow<List<CalendarLayer>>

    @Insert
    suspend fun insert(layer: CalendarLayer): Long

    @Update
    suspend fun update(layer: CalendarLayer)

    @Delete
    suspend fun delete(layer: CalendarLayer)
}
