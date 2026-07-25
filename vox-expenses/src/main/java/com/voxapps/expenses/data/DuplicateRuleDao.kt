package com.voxapps.expenses.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface DuplicateRuleDao {
    @Query("SELECT * FROM duplicate_rules ORDER BY sortOrder ASC, id ASC")
    fun observeAll(): Flow<List<DuplicateRuleEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(rule: DuplicateRuleEntity): Long

    @Update
    suspend fun update(rule: DuplicateRuleEntity)

    @Delete
    suspend fun delete(rule: DuplicateRuleEntity)

    @Query("UPDATE duplicate_rules SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean)

    @Query("UPDATE duplicate_rules SET sortOrder = :order WHERE id = :id")
    suspend fun updateSortOrder(id: Long, order: Int)
}
