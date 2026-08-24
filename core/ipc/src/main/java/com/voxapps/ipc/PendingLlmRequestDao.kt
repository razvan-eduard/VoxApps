package com.voxapps.ipc

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PendingLlmRequestDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: PendingLlmRequestEntity)

    @Query("SELECT * FROM pending_llm_requests WHERE lastAttemptAt < :olderThan AND attemptCount < :maxAttempts")
    suspend fun getStale(olderThan: Long, maxAttempts: Int): List<PendingLlmRequestEntity>

    @Query("UPDATE pending_llm_requests SET attemptCount = attemptCount + 1, lastAttemptAt = :now WHERE requestId = :requestId")
    suspend fun incrementAttempt(requestId: String, now: Long)

    @Query("SELECT * FROM pending_llm_requests WHERE requestId = :requestId LIMIT 1")
    suspend fun getByRequestId(requestId: String): PendingLlmRequestEntity?

    @Query("DELETE FROM pending_llm_requests WHERE requestId = :requestId")
    suspend fun deleteByRequestId(requestId: String)

    @Query("SELECT * FROM pending_llm_requests")
    suspend fun getAll(): List<PendingLlmRequestEntity>

    /**
     * How many captures are waiting for an answer, as a stream.
     *
     * A queue nothing reads for display is a queue that lets a person speak into the air and wonder
     * whether they were heard. This is what an app puts on screen so the answer is "yes, and it is
     * still working on it" rather than silence.
     */
    @Query("SELECT COUNT(*) FROM pending_llm_requests")
    fun observeCount(): Flow<Int>

    @Delete
    suspend fun delete(entity: PendingLlmRequestEntity)
}
