package com.voxapps.expenses.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import com.voxapps.design.color.VoxColorPalette

@Dao
interface ExpenseDao {
    /**
     * All expenses joined with category + line items, newest first. Category/date-range filtering and
     * sort direction are applied in the state layer (mirrors vox-notes' NoteFilter) so the query stays
     * simple and the logic stays pure/testable.
     */
    @Transaction
    @Query("SELECT * FROM expenses ORDER BY dateTime DESC")
    fun observeExpensesWithDetails(): Flow<List<ExpenseWithDetails>>

    @Query("SELECT * FROM expenses ORDER BY dateTime DESC")
    fun observeAll(): Flow<List<Expense>>

    /** One-shot read for the write/export paths — `observeAll().first()` would spin up an
     *  InvalidationTracker observer, run the query, then tear it all down again. */
    @Query("SELECT * FROM expenses ORDER BY dateTime DESC")
    suspend fun getAll(): List<Expense>

    @Transaction
    @Query("SELECT * FROM expenses WHERE id = :id")
    suspend fun getWithDetailsById(id: Long): ExpenseWithDetails?

    /** One-shot day-scoped read (e.g. Vox Calendar's day-tap summary via VoxCommand.dateFrom/dateTo) —
     *  a plain SQL range query rather than fetching everything and filtering in memory, since the
     *  caller only wants one day's worth of records. */
    @Query("SELECT * FROM expenses WHERE dateTime BETWEEN :from AND :to ORDER BY dateTime ASC")
    suspend fun getForDateRange(from: Long, to: Long): List<Expense>

    /** Cheap "what color is the top-of-list expense's category" lookup for
     *  [VoxColorPalette.unusedOrRandomColor]'s `precedingColor` param — a single indexed-order
     *  `LIMIT 1` row, not a full fetch+sort of the table. */
    @Query(
        """
        SELECT c.colorArgb FROM expenses e
        INNER JOIN categories c ON c.id = e.categoryId
        WHERE e.categoryId IS NOT NULL
        ORDER BY e.dateTime DESC
        LIMIT 1
        """
    )
    suspend fun getMostRecentCategoryColor(): Long?

    @Insert
    suspend fun insert(expense: Expense): Long

    /** Detach all expenses from a category being deleted (code-level ON DELETE SET NULL). */
    @Query("UPDATE expenses SET categoryId = NULL WHERE categoryId = :categoryId")
    suspend fun clearCategory(categoryId: Long)

    /** Reassigns all expenses from one category to another (used by category auto-merge). */
    @Query("UPDATE expenses SET categoryId = :newCategoryId WHERE categoryId = :oldCategoryId")
    suspend fun reassignCategory(oldCategoryId: Long, newCategoryId: Long)

    @Query("DELETE FROM expenses")
    suspend fun deleteAll()

    @Query("DELETE FROM expenses WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM expenses WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    /** Read tombstone-worthy uids before a delete-by-ids, since the rows won't exist to query afterwards. */
    @Query("SELECT uid FROM expenses WHERE id IN (:ids)")
    suspend fun getUidsByIds(ids: List<Long>): List<String>

    /** Resolves a peer-to-peer sync delta's uid back to this device's own local row id — needed
     *  before an update (preserve the local id) or a delete-by-uid (Room has no delete-by-uid). */
    @Query("SELECT id FROM expenses WHERE uid = :uid")
    suspend fun getIdByUid(uid: String): Long?

    @Update
    suspend fun update(expense: Expense)

    @Delete
    suspend fun delete(expense: Expense)

    // --- Sync tombstones (see ExpenseTombstone) ---

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTombstone(tombstone: ExpenseTombstone)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTombstones(tombstones: List<ExpenseTombstone>)

    @Query("SELECT * FROM expense_tombstones WHERE deletedAt > :since")
    suspend fun getTombstonesSince(since: Long): List<ExpenseTombstone>

    @Query("DELETE FROM expense_tombstones WHERE deletedAt < :before")
    suspend fun deleteStaleTombstones(before: Long)
}
