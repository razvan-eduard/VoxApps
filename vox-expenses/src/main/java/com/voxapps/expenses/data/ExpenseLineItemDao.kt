package com.voxapps.expenses.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ExpenseLineItemDao {
    @Query("SELECT * FROM expense_line_items WHERE expenseId = :expenseId ORDER BY position ASC")
    fun observeByExpense(expenseId: Long): Flow<List<ExpenseLineItem>>

    @Insert
    suspend fun insert(item: ExpenseLineItem): Long

    @Insert
    suspend fun insertAll(items: List<ExpenseLineItem>)

    @Update
    suspend fun update(item: ExpenseLineItem)

    @Delete
    suspend fun delete(item: ExpenseLineItem)

    @Query("DELETE FROM expense_line_items WHERE expenseId = :expenseId")
    suspend fun deleteAllForExpense(expenseId: Long)
}
