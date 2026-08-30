package com.voxapps.expenses.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A user-configured spending limit, checked periodically by
 * [com.voxapps.expenses.domain.limits.SpendingLimitCheckWorker]. [categoryId] null means an overall
 * limit across all expenses; otherwise it applies only to that category. [amountHomeCurrency] is
 * always in the user's home currency (see `ExpensesSettings.homeCurrency`) — comparisons convert each
 * expense's own currency into it at check time via [com.voxapps.expenses.data.ExchangeRateRepository],
 * the same infrastructure Stage 5 built for reports.
 */
@Entity(
    tableName = "spending_limits",
    indices = [Index("categoryId")]
)
data class SpendingLimit(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val categoryId: Long? = null,
    val amountHomeCurrency: Double,
    val period: String,
    val createdAt: Long = System.currentTimeMillis(),
    /**
     * Whether only records made on this device count toward the limit — true by default, so a
     * personal budget keeps meaning what it meant before device sync could add someone else's
     * spending to the ledger. Unticked, records from every device count: a shared budget.
     */
    val ownDeviceOnly: Boolean = true
) {
    companion object {
        const val PERIOD_WEEKLY = "WEEKLY"
        const val PERIOD_MONTHLY = "MONTHLY"
    }
}
