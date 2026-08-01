package com.voxapps.expenses.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * A snapshot of what [com.voxapps.expenses.receiver.LlmResultReceiver]'s EXPENSE_LINEITEMS_RESCAN
 * reply found for an already-saved expense's scalar fields — deliberately NOT applied automatically
 * (unlike line items, which the same rescan writes directly): [com.voxapps.expenses.ui.ExpenseEditScreen]
 * diffs each field against the record's current value and, wherever they differ, shows a tappable
 * suggestion chip instead. One row per expense (upserted on every rescan, replacing any prior
 * suggestion), cleared once the expense is saved so a stale scan never lingers past that point.
 * [category] is the raw parsed category NAME, not an id — resolved against the live category list at
 * display time (existing categories only, no auto-create, unlike the voice/scan-create paths).
 */
@Entity(tableName = "pending_field_suggestions")
data class PendingFieldSuggestion(
    @PrimaryKey val expenseId: Long,
    val title: String? = null,
    val vendor: String? = null,
    val bank: String? = null,
    val totalAmount: Double? = null,
    val currencyCode: String? = null,
    val category: String? = null,
    val location: String? = null,
    val dateTime: Long? = null
)

@Dao
interface PendingFieldSuggestionDao {
    @Query("SELECT * FROM pending_field_suggestions WHERE expenseId = :expenseId")
    fun observe(expenseId: Long): Flow<PendingFieldSuggestion?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(suggestion: PendingFieldSuggestion)

    @Query("DELETE FROM pending_field_suggestions WHERE expenseId = :expenseId")
    suspend fun clear(expenseId: Long)
}
