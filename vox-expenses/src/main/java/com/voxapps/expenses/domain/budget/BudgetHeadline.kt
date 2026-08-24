package com.voxapps.expenses.domain.budget

import com.voxapps.expenses.data.AccountBudget
import com.voxapps.expenses.data.BankAccount
import com.voxapps.expenses.data.Expense
import com.voxapps.expenses.data.preferences.ExpensesSettings
import java.time.LocalDate

/**
 * The one line a glance can carry: what is left, of what a person chose to be told about.
 *
 * Off is an answer, not an empty list. A home screen is read on a lock screen and over shoulders, so
 * a person who does not want a balance there wants it absent — the header is not drawn at all, and
 * nothing about it says a figure is being withheld.
 */
object BudgetHeadline {

    data class Line(val remaining: Double, val currency: String, val mixed: Boolean)

    /**
     * [convert] turns an amount in one currency into the home currency, or returns null when it
     * cannot — a rate nobody has fetched. Budgets it cannot convert are left out rather than added
     * as though they were already home currency, which would state a total that is simply wrong.
     */
    suspend fun of(
        settings: ExpensesSettings,
        budgets: List<AccountBudget>,
        expenses: List<Expense>,
        accounts: List<BankAccount> = emptyList(),
        convert: suspend (Double, String) -> Double?,
        today: LocalDate = LocalDate.now()
    ): Line? {
        val chosen = when (settings.widgetBudgetMode) {
            ExpensesSettings.WIDGET_BUDGET_TOTAL -> budgets
            ExpensesSettings.WIDGET_BUDGET_SELECTED ->
                budgets.filter { it.accountId in settings.widgetBudgetAccountIds }
            else -> return null
        }
        if (chosen.isEmpty()) return null

        val remainings = chosen.map { it to BudgetMath.remaining(it, expenses, accounts, today) }
        // One currency throughout is the ordinary case, and it needs no rate and no rounding: the
        // sum is exact and says so in the currency the budgets are actually in.
        val currencies = chosen.map { it.currencyCode.uppercase() }.distinct()
        if (currencies.size == 1) {
            return Line(remainings.sumOf { it.second }, currencies.single(), mixed = false)
        }
        val home = settings.homeCurrency
        val converted = buildList {
            for ((budget, left) in remainings) {
                val inHome =
                    if (budget.currencyCode.equals(home, ignoreCase = true)) left
                    else convert(left, budget.currencyCode)
                if (inHome != null) add(inHome)
            }
        }
        if (converted.isEmpty()) return null
        return Line(converted.sum(), home, mixed = true)
    }
}
