package com.voxapps.notes.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
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

    /** One-shot read for the write/export paths — `observeAll().first()` would spin up an
     *  InvalidationTracker observer, run the query, then tear it all down again. */
    @Query("SELECT * FROM notes ORDER BY createdAt DESC")
    suspend fun getAll(): List<Note>

    /** One-shot day-scoped read (e.g. Vox Calendar's day-tap summary via VoxCommand.dateFrom/dateTo) —
     *  a plain SQL range query rather than fetching everything and filtering in memory, since the
     *  caller only wants one day's worth of records. */
    @Query("SELECT * FROM notes WHERE createdAt BETWEEN :from AND :to ORDER BY createdAt ASC")
    suspend fun getForDateRange(from: Long, to: Long): List<Note>

    @Insert
    suspend fun insert(note: Note): Long

    /** Detach all notes from a category being deleted (code-level ON DELETE SET NULL). */
    @Query("UPDATE notes SET categoryId = NULL WHERE categoryId = :categoryId")
    suspend fun clearCategory(categoryId: Long)

    /** Reassigns all notes from one category to another (used by category auto-merge). */
    @Query("UPDATE notes SET categoryId = :newCategoryId WHERE categoryId = :oldCategoryId")
    suspend fun reassignCategory(oldCategoryId: Long, newCategoryId: Long)

    /** Partial update from the inline editor — preserves createdAt. */
    @Query("UPDATE notes SET title = :title, text = :text, categoryId = :categoryId, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateFields(id: Long, title: String?, text: String, categoryId: Long?, updatedAt: Long)

    @Query("DELETE FROM notes WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Update
    suspend fun update(note: Note)

    @Delete
    suspend fun delete(note: Note)

    // --- Sync tombstones (see NoteTombstone) ---

    /** Read the uid before a delete-by-id, since the row won't exist to query afterwards. */
    @Query("SELECT uid FROM notes WHERE id = :id")
    suspend fun getUidById(id: Long): String?

    /** Resolves a peer-to-peer sync delta's uid back to this device's own local row id — needed
     *  before an update (preserve the local id) or a delete-by-uid (Room has no delete-by-uid). */
    @Query("SELECT id FROM notes WHERE uid = :uid")
    suspend fun getIdByUid(uid: String): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTombstone(tombstone: NoteTombstone)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTombstones(tombstones: List<NoteTombstone>)

    @Query("SELECT * FROM note_tombstones WHERE deletedAt > :since")
    suspend fun getTombstonesSince(since: Long): List<NoteTombstone>

    @Query("DELETE FROM note_tombstones WHERE deletedAt < :before")
    suspend fun deleteStaleTombstones(before: Long)
}
