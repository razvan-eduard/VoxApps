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

    /** One-shot read for the write paths — `observeAll().first()` would spin up an
     *  InvalidationTracker observer, run the query, then tear it all down again just to read
     *  the current rows once. */
    @Query("SELECT * FROM calendar_layers ORDER BY position ASC, createdAt ASC")
    suspend fun getAll(): List<CalendarLayer>

    /** Single-row lookup, replacing the several call sites that loaded the whole table just
     *  to `firstOrNull { it.id == layerId }` it away. */
    @Query("SELECT * FROM calendar_layers WHERE id = :id")
    suspend fun getById(id: Long): CalendarLayer?

    @Insert
    suspend fun insert(layer: CalendarLayer): Long

    @Update
    suspend fun update(layer: CalendarLayer)

    @Delete
    suspend fun delete(layer: CalendarLayer)
}
