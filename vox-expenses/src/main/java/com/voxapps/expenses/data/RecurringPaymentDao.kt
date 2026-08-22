package com.voxapps.expenses.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface RecurringPaymentDao {

    /** Everything a person might see, observations included — the screen decides what to show. */
    @Query("SELECT * FROM recurring_payments WHERE dismissed = 0 ORDER BY confirmedAt IS NULL, vendorLabel COLLATE NOCASE")
    fun observeAll(): Flow<List<RecurringPayment>>

    /** Only what someone has said recurs; only these predict anything. */
    @Query("SELECT * FROM recurring_payments WHERE confirmedAt IS NOT NULL AND dismissed = 0")
    suspend fun confirmed(): List<RecurringPayment>

    /** The same set as [observeAll], read once — for the proposal pass, which is a question asked at
     *  a moment rather than a stream to watch. */
    @Query("SELECT * FROM recurring_payments WHERE dismissed = 0")
    suspend fun observeAllOnce(): List<RecurringPayment>

    /** Every row, dismissed ones included — for backup. A dismissal is an answer a person gave, and
     *  a restore that dropped it would start proposing what they already refused. */
    @Query("SELECT * FROM recurring_payments")
    suspend fun allRows(): List<RecurringPayment>

    /** Identity is vendor + how often, so one shop can hold a monthly bill and a yearly one at once
     *  without either being mistaken for the other. */
    @Query("SELECT * FROM recurring_payments WHERE vendorKey = :vendorKey AND frequency = :frequency LIMIT 1")
    suspend fun find(vendorKey: String, frequency: RecurrenceFrequency): RecurringPayment?

    @Query("SELECT * FROM recurring_payments WHERE id = :id")
    suspend fun byId(id: Long): RecurringPayment?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(payment: RecurringPayment): Long

    @Update
    suspend fun update(payment: RecurringPayment)

    @Delete
    suspend fun delete(payment: RecurringPayment)
}
