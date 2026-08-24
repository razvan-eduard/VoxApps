package com.voxapps.expenses.domain.health

import com.voxapps.expenses.data.ExpenseSource
import com.voxapps.expenses.data.ExpenseWithDetails
import com.voxapps.expenses.domain.llm.ExpenseAmountMismatch

/** Something about a record that is missing or does not add up. */
enum class ExpenseGap {
    /** A scan whose reading never came back usable — the photo is there and the fields are not. */
    UNREAD,

    /** The rows and the total contradict each other. One of them is wrong and only a person can
     *  say which. */
    TOTALS_DISAGREE,

    /** No figure at all, or zero: whatever else it says, it says nothing about money. */
    NO_AMOUNT,

    /** Neither a shop nor a title — nothing to recognise it by in a list a year from now. */
    NO_NAME,

    /** Filed under nothing, or under the category that means "nothing classified this". */
    NO_CATEGORY,

    /** A capture that named no card or account. Only captures: a record somebody typed has no
     *  account because none was involved, which is not a gap but a fact about cash. */
    NO_ACCOUNT
}

/**
 * What a record is still missing, if anything.
 *
 * The load-bearing rule is what this refuses to complain about. A record the person has edited is a
 * record they have looked at: if they saved it without a bank, that is their answer, and asking
 * again is how a "needs you" list becomes something people learn to ignore. Same for the fallback
 * category — it is only a gap while nobody has confirmed it.
 *
 * Nothing here is a judgement about the spending. It is about whether the record can be found,
 * counted and trusted later.
 */
object ExpenseGaps {

    fun of(record: ExpenseWithDetails, fallbackCategoryId: Long?): Set<ExpenseGap> {
        val expense = record.expense
        // Seen by a person, and left as it is. That is an answer, not an omission.
        if (expense.manuallyEdited) return emptySet()

        return buildSet {
            if (expense.isStub) add(ExpenseGap.UNREAD)
            if (expense.totalAmount <= 0.0) add(ExpenseGap.NO_AMOUNT)
            if (ExpenseAmountMismatch.isGrossMismatch(
                    expense.totalAmount,
                    record.items.sumOf { it.quantity * it.unitPrice }
                )
            ) {
                add(ExpenseGap.TOTALS_DISAGREE)
            }
            if (expense.vendor.isNullOrBlank() && expense.title.isNullOrBlank()) add(ExpenseGap.NO_NAME)
            if (expense.categoryId == null || expense.categoryId == fallbackCategoryId) {
                add(ExpenseGap.NO_CATEGORY)
            }
            // A capture read a message or a page that named a card; a typed record never had one to
            // lose. Asking a person to attach an account to their own cash is asking for a fiction.
            val captured = expense.source == ExpenseSource.NOTIFICATION || expense.source == ExpenseSource.SCAN
            if (captured && expense.bankAccountId == null) add(ExpenseGap.NO_ACCOUNT)
        }
    }

    /** Every record with anything missing — what a "needs you" list counts. */
    fun needingAttention(
        records: List<ExpenseWithDetails>,
        fallbackCategoryId: Long?
    ): List<ExpenseWithDetails> = records.filter { of(it, fallbackCategoryId).isNotEmpty() }
}
