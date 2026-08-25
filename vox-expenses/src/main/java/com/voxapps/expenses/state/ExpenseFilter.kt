package com.voxapps.expenses.state

import com.voxapps.design.filter.VoxRange
import com.voxapps.expenses.data.ExpenseWithDetails

enum class SortMode { NEWEST, OLDEST, AMOUNT_ASC, AMOUNT_DESC }

/** The translation key naming a sort, so the chips that set it and the summary that reports it
 *  cannot end up calling the same order two different things. */
fun sortKeyOf(sort: SortMode): String = when (sort) {
    SortMode.NEWEST -> "sort_newest"
    SortMode.OLDEST -> "sort_oldest"
    SortMode.AMOUNT_ASC -> "sort_amount_asc"
    SortMode.AMOUNT_DESC -> "sort_amount_desc"
}

/**
 * Pure filtering/sorting of expenses by category + date range + bank + vendor + sort direction.
 * Kept out of Room so it's trivially unit-testable (mirrors vox-notes' NoteFilter).
 */
object ExpenseFilter {
    fun apply(
        expenses: List<ExpenseWithDetails>,
        categoryId: Long?,
        dateFrom: Long?,
        dateTo: Long?,
        bank: FilterValue?,
        /** The bank a record is with, resolved from the account it points at — a record carries no
         *  bank of its own. Supplied by the caller, which has the accounts. */
        bankOf: (Long?) -> String?,
        vendor: FilterValue?,
        location: FilterValue?,
        amount: VoxRange?,
        /**
         * The accounts a record may be filed against — an account together with its cards, already
         * flattened by [com.voxapps.expenses.domain.accounts.BankAccountTree.familyOf]. Resolved by
         * the caller so this stays a comparison rather than a walk.
         */
        accountIds: Set<Long>?,
        currency: String?,
        sort: SortMode
    ): List<ExpenseWithDetails> {
        val filtered = expenses.filter { ewd ->
            val e = ewd.expense
            (categoryId == null || e.categoryId == categoryId) &&
                (dateFrom == null || e.dateTime >= dateFrom) &&
                (dateTo == null || e.dateTime <= dateTo) &&
                (bank == null || bank.matches(bankOf(e.bankAccountId))) &&
                (vendor == null || vendor.matches(e.vendor)) &&
                (location == null || location.matches(e.location)) &&
                (amount == null || amount.contains(e.totalAmount)) &&
                (accountIds == null || e.bankAccountId in accountIds) &&
                (currency == null || e.currencyCode.equals(currency, ignoreCase = true))
        }
        return when (sort) {
            SortMode.NEWEST -> filtered.sortedByDescending { it.expense.dateTime }
            SortMode.OLDEST -> filtered.sortedBy { it.expense.dateTime }
            SortMode.AMOUNT_ASC -> filtered.sortedBy { it.expense.totalAmount }
            SortMode.AMOUNT_DESC -> filtered.sortedByDescending { it.expense.totalAmount }
        }
    }
}
