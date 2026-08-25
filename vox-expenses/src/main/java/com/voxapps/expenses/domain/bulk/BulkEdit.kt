package com.voxapps.expenses.domain.bulk

import com.voxapps.expenses.data.Expense
import com.voxapps.expenses.data.ExpenseOrigins
import com.voxapps.expenses.data.TransactionDirection

/**
 * The fields one edit may set across many records at once.
 *
 * Every one is null by default and null means *leave it alone* — not "clear it". A screen that edits
 * twenty records has to be able to say "make these all Groceries" without also saying anything about
 * their shops, and a blank field that erased what it touched would do exactly that, silently, twenty
 * times over.
 *
 * Only what is common to records by nature is here. An amount, a date and a currency are facts about
 * one payment each; setting twenty of them to the same figure is not an edit, it is a fabrication.
 */
data class BulkEdit(
    val categoryId: Long? = null,
    val vendor: String? = null,
    val bank: String? = null,
    val bankAccountId: Long? = null,
    val location: String? = null,
    val direction: TransactionDirection? = null
) {
    val isEmpty: Boolean
        get() = categoryId == null && vendor == null && bank == null &&
            bankAccountId == null && location == null && direction == null

    /**
     * [expense] with this edit written onto it.
     *
     * Everything it touches becomes the person's: they chose the value, for records they chose,
     * which is as typed as typing gets. That also stops those records being named as incomplete
     * afterwards, which is the correct outcome — somebody has now answered for them.
     */
    fun applyTo(expense: Expense): Expense {
        val touched = buildSet {
            if (categoryId != null) add(ExpenseOrigins.FIELD_CATEGORY)
            if (vendor != null) add(ExpenseOrigins.FIELD_VENDOR)
            if (bank != null) add(ExpenseOrigins.FIELD_BANK)
            if (location != null) add(ExpenseOrigins.FIELD_LOCATION)
        }
        return expense.copy(
            categoryId = categoryId ?: expense.categoryId,
            vendor = vendor ?: expense.vendor,
            bank = bank ?: expense.bank,
            bankAccountId = bankAccountId ?: expense.bankAccountId,
            location = location ?: expense.location,
            direction = direction ?: expense.direction,
            originsJson = ExpenseOrigins.withTyped(expense.originsJson, touched)
        )
    }
}
