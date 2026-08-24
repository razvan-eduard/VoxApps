package com.voxapps.expenses.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * How much there is to spend on one account, in one currency.
 *
 * Per (account, currency) rather than per account, because an account holds more than one: a card
 * that charges in RON at home and EUR abroad is one card with two pots, and a single figure would
 * have to pretend one of them is the other at a rate nobody agreed on. Per account rather than per
 * card, because money lives in the account — two cards on one account spend the same money, and two
 * budgets over it would count it twice. A card that belongs to nobody is its own account row here
 * (see [BankAccount.parentId]) and gets its own budget, which is what makes a meal-voucher card work
 * without a second concept.
 *
 * Nothing is ever decremented. What is left is [amount] less what the records say was spent since
 * the window began — a figure derived on every read, so a capture that arrives late, a correction,
 * or a deletion all land without bookkeeping, and nothing can drift out of agreement with the
 * expenses it is a summary of. See [com.voxapps.expenses.domain.budget.BudgetMath].
 */
@Entity(
    tableName = "account_budgets",
    indices = [Index(value = ["accountId", "currencyCode"], unique = true)]
)
data class AccountBudget(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val accountId: Long,
    val currencyCode: String,
    /** What the period grants, or what the pot was last filled with. */
    val amount: Double,
    val mode: String = MODE_PERIOD,
    /** [SpendingLimit.PERIOD_WEEKLY] or [SpendingLimit.PERIOD_MONTHLY]; ignored by [MODE_POT]. */
    val period: String = SpendingLimit.PERIOD_MONTHLY,
    /** When the pot was filled. Unused by [MODE_PERIOD], whose window the calendar decides. */
    val startedAt: Long = System.currentTimeMillis(),
    /**
     * The moment a statement was believed, and what it said was left.
     *
     * A bank knows its own balance better than any arithmetic over notifications this app happened
     * to see. Where one is taken, what is left counts only the records newer than it — so a capture
     * missed while the phone was off stops mattering from that moment, instead of being wrong
     * forever.
     */
    val reconciledAt: Long? = null,
    val reconciledRemaining: Double? = null
) {
    companion object {
        /** Resets with the calendar: a month's or a week's allowance. */
        const val MODE_PERIOD = "PERIOD"

        /** Runs down until it is filled again, and only a person fills it — a prepaid or
         *  meal-voucher card, where no calendar has an opinion about the balance. */
        const val MODE_POT = "POT"
    }
}

@Dao
interface AccountBudgetDao {
    @Query("SELECT * FROM account_budgets ORDER BY accountId, currencyCode")
    fun observeAll(): Flow<List<AccountBudget>>

    @Query("SELECT * FROM account_budgets ORDER BY accountId, currencyCode")
    suspend fun getAll(): List<AccountBudget>

    @Query("SELECT * FROM account_budgets WHERE accountId = :accountId AND currencyCode = :currencyCode LIMIT 1")
    suspend fun forAccount(accountId: Long, currencyCode: String): AccountBudget?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(budget: AccountBudget): Long

    @Update
    suspend fun update(budget: AccountBudget)

    @Delete
    suspend fun delete(budget: AccountBudget)

    @Query("DELETE FROM account_budgets WHERE accountId = :accountId")
    suspend fun deleteForAccount(accountId: Long)
}
