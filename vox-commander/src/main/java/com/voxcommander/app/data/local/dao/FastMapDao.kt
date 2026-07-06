package com.voxcommander.app.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.voxcommander.app.domain.intent.model.FastMapRule

import kotlinx.coroutines.flow.Flow

@Dao
interface FastMapDao {
    @Query("SELECT * FROM fast_map_rules ORDER BY sortOrder ASC, id ASC")
    fun getAllRules(): Flow<List<FastMapRule>>

    @Query("SELECT * FROM fast_map_rules ORDER BY sortOrder ASC, id ASC")
    suspend fun getAllRulesOnce(): List<FastMapRule>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRule(rule: FastMapRule)

    @Delete
    suspend fun deleteRule(rule: FastMapRule)

    @Query("UPDATE fast_map_rules SET sortOrder = :order WHERE id = :id")
    suspend fun updateSortOrder(id: Long, order: Int)

    @Query("UPDATE fast_map_rules SET isActive = :active WHERE id = :id")
    suspend fun setRuleActive(id: Long, active: Boolean)

    @Query("UPDATE fast_map_rules SET isActive = 0")
    suspend fun deactivateAllRules()

    @Query("UPDATE fast_map_rules SET isActive = 1")
    suspend fun activateAllRules()

    @Query("DELETE FROM fast_map_rules")
    suspend fun deleteAllRules()

    @Transaction
    suspend fun reorderRules(orderedIds: List<Long>) {
        orderedIds.forEachIndexed { index, id ->
            updateSortOrder(id, index)
        }
    }
}