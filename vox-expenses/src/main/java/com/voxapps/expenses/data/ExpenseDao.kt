package com.voxapps.expenses.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

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

    @Insert
    suspend fun insert(expense: Expense): Long

    /** Detach all expenses from a category being deleted (code-level ON DELETE SET NULL). */
    @Query("UPDATE expenses SET categoryId = NULL WHERE categoryId = :categoryId")
    suspend fun clearCategory(categoryId: Long)

    /** Reassigns all expenses from one category to another (used by category auto-merge). */
    @Query("UPDATE expenses SET categoryId = :newCategoryId WHERE categoryId = :oldCategoryId")
    suspend fun reassignCategory(oldCategoryId: Long, newCategoryId: Long)

    @Query("DELETE FROM expenses WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Update
    suspend fun update(expense: Expense)

    @Delete
    suspend fun delete(expense: Expense)
}
