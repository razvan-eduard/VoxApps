package com.voxapps.ipc

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface PendingLlmRequestDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: PendingLlmRequestEntity)

    @Query("SELECT * FROM pending_llm_requests WHERE lastAttemptAt < :olderThan AND attemptCount < :maxAttempts")
    suspend fun getStale(olderThan: Long, maxAttempts: Int): List<PendingLlmRequestEntity>

    @Query("UPDATE pending_llm_requests SET attemptCount = attemptCount + 1, lastAttemptAt = :now WHERE requestId = :requestId")
    suspend fun incrementAttempt(requestId: String, now: Long)

    @Query("DELETE FROM pending_llm_requests WHERE requestId = :requestId")
    suspend fun deleteByRequestId(requestId: String)

    @Query("SELECT * FROM pending_llm_requests")
    suspend fun getAll(): List<PendingLlmRequestEntity>

    @Delete
    suspend fun delete(entity: PendingLlmRequestEntity)
}
