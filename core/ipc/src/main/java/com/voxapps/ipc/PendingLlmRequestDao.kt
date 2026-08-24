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

    /** How many are still to be retried — a row that has spent its budget is not waiting for
     *  anything, and counting it says work is in progress when none is. */
    @Query("SELECT COUNT(*) FROM pending_llm_requests WHERE attemptCount < :maxAttempts")
    fun observeLiveCount(maxAttempts: Int): Flow<Int>

    /** Gives a row its budget back, so a person who says "try it now" is asking for something that
     *  can actually happen. */
    @Query("UPDATE pending_llm_requests SET attemptCount = 0, lastAttemptAt = 0 WHERE requestId = :requestId")
    suspend fun resetAttempts(requestId: String)

    @Query("UPDATE pending_llm_requests SET attemptCount = 0, lastAttemptAt = 0")
    suspend fun resetAllAttempts()

    /** Drops the ones the app gave up on. Nothing is lost that could still arrive — a row past its
     *  budget is never re-sent — and what it holds is a prompt, not a record. */
    @Query("DELETE FROM pending_llm_requests WHERE attemptCount >= :maxAttempts")
    suspend fun deleteExhausted(maxAttempts: Int)

    /** The waiting captures themselves, newest first — for a screen that shows what they are rather
     *  than only how many. */
    @Query("SELECT * FROM pending_llm_requests ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<PendingLlmRequestEntity>>

    @Delete
    suspend fun delete(entity: PendingLlmRequestEntity)
}
