package com.voxapps.fieldmemory

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface LearnedFieldCorrectionDao {

    @Query("SELECT * FROM learned_field_corrections WHERE garbageKey = :garbageKey")
    suspend fun get(garbageKey: String): LearnedFieldCorrection?

    /** The corrections allowed to answer: never quarantined, confirmed at least [threshold] times. */
    @Query("SELECT * FROM learned_field_corrections WHERE quarantined = 0 AND consecutiveCount >= :threshold")
    suspend fun getActive(threshold: Int): List<LearnedFieldCorrection>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(correction: LearnedFieldCorrection)

    @Query("SELECT * FROM learned_field_corrections")
    suspend fun getAll(): List<LearnedFieldCorrection>

    @Query("DELETE FROM learned_field_corrections")
    suspend fun deleteAll()
}
