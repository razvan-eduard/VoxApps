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
 *
 * The main list no longer runs whole through here: everything SQL can express — category, dates,
 * amount, account family, currency, sort — narrows inside
 * [com.voxapps.expenses.data.ExpenseDao.observeFiltered], and only
 * [residual] runs up here, on the already-narrowed rows. [apply] remains the complete in-memory
 * form — the reference the tests pin, and the path for callers holding a list rather than the
 * database — and routes through [residual] so the two cannot drift apart.
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
                (amount == null || amount.contains(e.totalAmount)) &&
                (accountIds == null || e.bankAccountId in accountIds) &&
                (currency == null || e.currencyCode.equals(currency, ignoreCase = true))
        }.let { residual(it, bank, bankOf, vendor, location) }
        return when (sort) {
            SortMode.NEWEST -> filtered.sortedByDescending { it.expense.dateTime }
            SortMode.OLDEST -> filtered.sortedBy { it.expense.dateTime }
            SortMode.AMOUNT_ASC -> filtered.sortedBy { it.expense.totalAmount }
            SortMode.AMOUNT_DESC -> filtered.sortedByDescending { it.expense.totalAmount }
        }
    }

    /**
     * The three narrows SQL cannot express faithfully: [FilterValue] folds case the Unicode way
     * (SQLite's NOCASE and LIKE fold ASCII alone, and the names here carry diacritics), and a
     * record's bank lives on its account, not on the row. Runs after the query has already done
     * the heavy narrowing, so it touches the small remainder.
     */
    fun residual(
        expenses: List<ExpenseWithDetails>,
        bank: FilterValue?,
        bankOf: (Long?) -> String?,
        vendor: FilterValue?,
        location: FilterValue?
    ): List<ExpenseWithDetails> {
        if (bank == null && vendor == null && location == null) return expenses
        return expenses.filter { ewd ->
            val e = ewd.expense
            (bank == null || bank.matches(bankOf(e.bankAccountId))) &&
                (vendor == null || vendor.matches(e.vendor)) &&
                (location == null || location.matches(e.location))
        }
    }
}
