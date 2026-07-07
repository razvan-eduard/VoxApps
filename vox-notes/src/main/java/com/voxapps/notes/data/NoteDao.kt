package com.voxapps.notes.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface NoteDao {
    /**
     * All notes (joined with their category), newest first. Category / date-range filtering and
     * sort direction are applied in the state layer via [com.voxapps.notes.state.NoteFilter] so the
     * logic stays pure/testable.
     */
    @Transaction
    @Query("SELECT * FROM notes ORDER BY createdAt DESC")
    fun observeNotesWithCategory(): Flow<List<NoteWithCategory>>

    @Query("SELECT * FROM notes ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<Note>>

    @Insert
    suspend fun insert(note: Note)

    /** Detach all notes from a category being deleted (code-level ON DELETE SET NULL). */
    @Query("UPDATE notes SET categoryId = NULL WHERE categoryId = :categoryId")
    suspend fun clearCategory(categoryId: Long)

    @Update
    suspend fun update(note: Note)

    @Delete
    suspend fun delete(note: Note)
}
