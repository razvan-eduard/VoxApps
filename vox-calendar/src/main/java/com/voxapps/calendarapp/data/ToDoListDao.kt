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

    @Query("SELECT * FROM todo_lists WHERE id = :id")
    suspend fun getById(id: Long): ToDoList?

    @Insert
    suspend fun insert(list: ToDoList): Long

    @Update
    suspend fun update(list: ToDoList)

    @Delete
    suspend fun delete(list: ToDoList)
}
