package com.voxapps.expenses.domain.llm

/**
 * Shared "does totalAmount roughly match sum(items)" check, used both for tight live-edit feedback
 * (exact tolerance, [isMismatch]) and for automated parse-time/list-row flagging where receipts
 * routinely and legitimately don't sum (tips, discounts, un-itemized tax) — [isGrossMismatch] only
 * flags large discrepancies (e.g. a distributive price wrongly divided by quantity), not ordinary
 * rounding/tip/discount variance, so it stays a soft "might be worth a glance" signal rather than
 * spamming on every normal receipt.
 */
object ExpenseAmountMismatch {
    fun isMismatch(totalAmount: Double, itemsSum: Double, tolerance: Double = 0.01): Boolean {
        if (itemsSum == 0.0) return false
        return kotlin.math.abs(totalAmount - itemsSum) > tolerance
    }

    fun isGrossMismatch(totalAmount: Double, itemsSum: Double): Boolean {
        if (itemsSum == 0.0) return false
        val diff = kotlin.math.abs(totalAmount - itemsSum)
        val relativeFloor = 0.20 * kotlin.math.max(totalAmount, itemsSum)
        return diff > kotlin.math.max(0.05, relativeFloor)
    }
}
