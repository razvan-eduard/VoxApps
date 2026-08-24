package com.voxapps.expenses.domain.budget

import com.voxapps.expenses.data.AccountBudget
import com.voxapps.expenses.data.Expense
import com.voxapps.expenses.data.TransactionDirection
import com.voxapps.expenses.data.preferences.ExpensesSettings
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class BudgetHeadlineTest {

    private val today = LocalDate.of(2026, 8, 24)
    private fun at(day: Int) =
        LocalDate.of(2026, 8, day).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private val settings = ExpensesSettings(homeCurrency = "RON", defaultCurrency = "RON")
    private fun budget(id: Long, account: Long, currency: String, amount: Double) =
        AccountBudget(id = id, accountId = account, currencyCode = currency, amount = amount)

    private fun spend(amount: Double, account: Long, currency: String) = Expense(
        title = null, totalAmount = amount, currencyCode = currency, vendor = null, bank = null,
        location = null, dateTime = at(20), comments = null, categoryId = null,
        direction = TransactionDirection.OUTGOING, bankAccountId = account
    )

    private val noRates: suspend (Double, String) -> Double? = { _, _ -> null }

    @Test
    fun `off says nothing at all`() = runTest {
        val off = settings.copy(widgetBudgetMode = ExpensesSettings.WIDGET_BUDGET_OFF)
        assertNull(BudgetHeadline.of(off, listOf(budget(1, 7, "RON", 1000.0)), emptyList(), convert = noRates, today = today))
    }

    @Test
    fun `one currency needs no rate and says so in its own currency`() = runTest {
        val on = settings.copy(widgetBudgetMode = ExpensesSettings.WIDGET_BUDGET_TOTAL)
        val line = BudgetHeadline.of(
            on,
            listOf(budget(1, 7, "RON", 1000.0), budget(2, 8, "RON", 500.0)),
            listOf(spend(200.0, 7, "RON")),
            convert = noRates,
            today = today
        )
        assertEquals(1300.0, line!!.remaining, 0.001)
        assertEquals("RON", line.currency)
        assertTrue(!line.mixed)
    }

    @Test
    fun `chosen accounts count only themselves`() = runTest {
        val chosen = settings.copy(
            widgetBudgetMode = ExpensesSettings.WIDGET_BUDGET_SELECTED,
            widgetBudgetAccountIds = setOf(8L)
        )
        val line = BudgetHeadline.of(
            chosen,
            listOf(budget(1, 7, "RON", 1000.0), budget(2, 8, "RON", 500.0)),
            emptyList(),
            convert = noRates,
            today = today
        )
        assertEquals(500.0, line!!.remaining, 0.001)
    }

    @Test
    fun `chosen but nothing ticked is a header with nothing to say`() = runTest {
        val chosen = settings.copy(widgetBudgetMode = ExpensesSettings.WIDGET_BUDGET_SELECTED)
        assertNull(BudgetHeadline.of(chosen, listOf(budget(1, 7, "RON", 1000.0)), emptyList(), convert = noRates, today = today))
    }

    @Test
    fun `mixed currencies convert into the home one`() = runTest {
        val on = settings.copy(widgetBudgetMode = ExpensesSettings.WIDGET_BUDGET_TOTAL)
        val line = BudgetHeadline.of(
            on,
            listOf(budget(1, 7, "RON", 1000.0), budget(2, 8, "EUR", 100.0)),
            emptyList(),
            convert = { amount, from -> if (from == "EUR") amount * 5.0 else null },
            today = today
        )
        assertEquals(1500.0, line!!.remaining, 0.001)
        assertEquals("RON", line.currency)
        assertTrue(line.mixed)
    }

    /** A rate nobody has fetched leaves that budget out rather than adding it as though it were
     *  already home currency, which would state a total that is simply wrong. */
    @Test
    fun `a budget that cannot be converted is left out, and all of them leaves nothing`() = runTest {
        val on = settings.copy(widgetBudgetMode = ExpensesSettings.WIDGET_BUDGET_TOTAL)
        val partial = BudgetHeadline.of(
            on,
            listOf(budget(1, 7, "RON", 1000.0), budget(2, 8, "EUR", 100.0)),
            emptyList(),
            convert = noRates,
            today = today
        )
        assertEquals(1000.0, partial!!.remaining, 0.001)

        val none = BudgetHeadline.of(
            on,
            listOf(budget(2, 8, "EUR", 100.0), budget(3, 9, "GBP", 50.0)),
            emptyList(),
            convert = noRates,
            today = today
        )
        assertNull(none)
    }
}
