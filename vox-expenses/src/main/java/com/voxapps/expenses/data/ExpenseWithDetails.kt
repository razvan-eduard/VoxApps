package com.voxapps.expenses.data

import androidx.room.Embedded
import androidx.room.Relation

/**
 * An expense joined with its category (nullable) and its full line-item list, so cards/screens can
 * render everything without a manual lookup. Room fills [items] by matching [Expense.id] ->
 * [ExpenseLineItem.expenseId], and [category] by matching [Expense.categoryId] -> [Category.id].
 */
data class ExpenseWithDetails(
    @Embedded val expense: Expense,
    @Relation(parentColumn = "categoryId", entityColumn = "id")
    val category: Category? = null,
    @Relation(parentColumn = "id", entityColumn = "expenseId")
    val items: List<ExpenseLineItem> = emptyList()
)
