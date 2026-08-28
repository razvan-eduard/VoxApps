package com.voxapps.expenses.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface RecipientDao {

    @Query("SELECT * FROM recipients ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<Recipient>>

    @Query("SELECT * FROM recipients ORDER BY name COLLATE NOCASE")
    suspend fun getAll(): List<Recipient>

    @Query("SELECT * FROM recipients WHERE id = :id")
    suspend fun getById(id: Long): Recipient?

    /** IGNORE, not ABORT: two captures racing to create one IBAN's row must leave one row and one
     *  loser who re-reads, never a failed capture — same contract as [BankAccountDao]. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(recipient: Recipient): Long

    @Update
    suspend fun update(recipient: Recipient)

    @Delete
    suspend fun delete(recipient: Recipient)
}
