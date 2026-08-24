package com.voxapps.expenses.domain.budget

import com.voxapps.expenses.data.AccountBudget
import com.voxapps.expenses.data.BankAccount
import com.voxapps.expenses.data.Expense
import com.voxapps.expenses.domain.accounts.BankAccountTree
import com.voxapps.expenses.data.TransactionDirection
import com.voxapps.expenses.domain.limits.SpendingPeriod
import java.time.LocalDate

/**
 * What is left of a budget, derived rather than kept.
 *
 * The alternative — a running figure decremented as records arrive — is wrong the first time a
 * capture is missed, edited or deleted, and stays wrong with no way to notice. Recomputing from the
 * records is exact by construction: whatever the expense list says today is what the budget says
 * today.
 */
object BudgetMath {

    /**
     * The record set a budget is a summary of: its account **and the cards under it**, its currency,
     * its window.
     *
     * A payment is filed against the card it was made with, and a card is a way of reaching an
     * account rather than a pot of its own — so the account's budget is what it comes out of. [held]
     * is the account's family (see [BankAccountTree.familyOf]); a caller that has no tree passes the
     * account alone and gets exactly the old behaviour.
     */
    fun applies(budget: AccountBudget, expense: Expense, windowStart: Long, held: Set<Long>): Boolean =
        expense.bankAccountId != null && expense.bankAccountId in held &&
            expense.currencyCode.equals(budget.currencyCode, ignoreCase = true) &&
            expense.dateTime >= windowStart

    /**
     * When the current window began.
     *
     * A statement believed later than the window's own start wins: counting a payment the bank had
     * already subtracted would take it off twice.
     */
    fun windowStart(budget: AccountBudget, today: LocalDate = LocalDate.now()): Long {
        val fromMode = when (budget.mode) {
            AccountBudget.MODE_POT -> budget.startedAt
            else -> SpendingPeriod.windowStartMillis(budget.period, today)
        }
        return maxOf(fromMode, budget.reconciledAt ?: Long.MIN_VALUE)
    }

    /**
     * What the window started with: the figure the bank last stated, or what was budgeted.
     *
     * A reconciled figure replaces the allowance rather than adjusting it, since it already accounts
     * for everything spent before that moment.
     */
    fun openingBalance(budget: AccountBudget): Double =
        budget.reconciledRemaining ?: budget.amount

    /**
     * What is left: the opening figure, less what went out, plus what came in.
     *
     * Incoming records add — a refund puts money back in the pot it left, and a budget that ignored
     * them would report a month as spent that was paid back on the second day.
     */
    fun remaining(
        budget: AccountBudget,
        expenses: List<Expense>,
        accounts: List<BankAccount> = emptyList(),
        today: LocalDate = LocalDate.now()
    ): Double {
        val start = windowStart(budget, today)
        val held = heldBy(budget, accounts)
        val movement = expenses.filter { applies(budget, it, start, held) }.sumOf {
            if (it.direction == TransactionDirection.INCOMING) it.totalAmount else -it.totalAmount
        }
        return openingBalance(budget) + movement
    }

    /** What went out of it, as a positive figure — for a screen saying "480 of 1500 spent". */
    fun spent(
        budget: AccountBudget,
        expenses: List<Expense>,
        accounts: List<BankAccount> = emptyList(),
        today: LocalDate = LocalDate.now()
    ): Double = openingBalance(budget) - remaining(budget, expenses, accounts, today)

    /** Every account id whose payments come out of this budget: the account and its cards. */
    fun heldBy(budget: AccountBudget, accounts: List<BankAccount>): Set<Long> =
        if (accounts.isEmpty()) setOf(budget.accountId)
        else BankAccountTree.familyOf(budget.accountId, accounts)
}
