package com.voxapps.expenses.state

import com.voxapps.expenses.data.ExpenseWithDetails

enum class SortMode { NEWEST, OLDEST, AMOUNT_ASC, AMOUNT_DESC }

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
        bank: String?,
        vendor: String?,
        sort: SortMode
    ): List<ExpenseWithDetails> {
        val filtered = expenses.filter { ewd ->
            val e = ewd.expense
            (categoryId == null || e.categoryId == categoryId) &&
                (dateFrom == null || e.dateTime >= dateFrom) &&
                (dateTo == null || e.dateTime <= dateTo) &&
                (bank == null || e.bank == bank) &&
                (vendor == null || e.vendor == vendor)
        }
        return when (sort) {
            SortMode.NEWEST -> filtered.sortedByDescending { it.expense.dateTime }
            SortMode.OLDEST -> filtered.sortedBy { it.expense.dateTime }
            SortMode.AMOUNT_ASC -> filtered.sortedBy { it.expense.totalAmount }
            SortMode.AMOUNT_DESC -> filtered.sortedByDescending { it.expense.totalAmount }
        }
    }
}
