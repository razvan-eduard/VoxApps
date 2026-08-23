package com.voxapps.expenses.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface BankAccountDao {

    @Query("SELECT * FROM bank_accounts ORDER BY label IS NULL, label COLLATE NOCASE, digits")
    fun observeAll(): Flow<List<BankAccount>>

    @Query("SELECT * FROM bank_accounts ORDER BY label IS NULL, label COLLATE NOCASE, digits")
    suspend fun getAll(): List<BankAccount>

    @Query("SELECT * FROM bank_accounts WHERE id = :id")
    suspend fun getById(id: Long): BankAccount?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(account: BankAccount): Long

    @Update
    suspend fun update(account: BankAccount)

    @Delete
    suspend fun delete(account: BankAccount)

    /** Every currency any account holds — what the currency filter offers. */
    @Query("SELECT DISTINCT currencyCode FROM bank_accounts WHERE currencyCode != '' ORDER BY currencyCode")
    fun observeCurrencies(): Flow<List<String>>
}
