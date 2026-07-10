package com.voxapps.expenses.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SpendingLimitDao {
    @Query("SELECT * FROM spending_limits ORDER BY id ASC")
    fun observeAll(): Flow<List<SpendingLimit>>

    @Insert
    suspend fun insert(limit: SpendingLimit): Long

    @Delete
    suspend fun delete(limit: SpendingLimit)

    @Query("UPDATE spending_limits SET categoryId = NULL WHERE categoryId = :categoryId")
    suspend fun clearCategory(categoryId: Long)
}
