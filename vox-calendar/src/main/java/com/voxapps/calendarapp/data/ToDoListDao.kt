package com.voxapps.calendarapp.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ToDoListDao {
    // Newest first, so a freshly-created list (opened directly in edit mode, like a new note) lands
    // at the top of the screen without needing a scroll.
    @Query("SELECT * FROM todo_lists ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<ToDoList>>

    /** One-shot read for the write paths — `observeAll().first()` would spin up an
     *  InvalidationTracker observer, run the query, then tear it all down again just to read
     *  the current rows once. */
    @Query("SELECT * FROM todo_lists ORDER BY createdAt DESC")
    suspend fun getAll(): List<ToDoList>

    @Query("SELECT * FROM todo_lists WHERE id = :id")
    suspend fun getById(id: Long): ToDoList?

    /** Lists under a calendar being deleted — used by both delete-calendar branches (reassign vs.
     *  delete-all-entries; see CalendarRepository.deleteLayer). */
    @Query("SELECT * FROM todo_lists WHERE layerId = :layerId")
    suspend fun getAllForLayer(layerId: Long): List<ToDoList>

    /** Reassigns every to-do list from a layer being deleted to the surviving Main calendar — the
     *  gap CalendarEntryDao.reassignLayer never covered (a to-do list's own layerId is a separate
     *  column from its items', see ToDoList's doc comment). */
    @Query("UPDATE todo_lists SET layerId = :newLayerId WHERE layerId = :oldLayerId")
    suspend fun reassignLayer(oldLayerId: Long, newLayerId: Long)

    @Insert
    suspend fun insert(list: ToDoList): Long

    @Update
    suspend fun update(list: ToDoList)

    @Delete
    suspend fun delete(list: ToDoList)
}
