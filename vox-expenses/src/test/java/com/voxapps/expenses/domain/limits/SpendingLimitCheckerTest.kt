package com.voxapps.expenses.domain.limits

import com.voxapps.expenses.data.Expense
import com.voxapps.expenses.data.SpendingLimit
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class SpendingLimitCheckerTest {

    private val today = LocalDate.of(2026, 7, 15)
    private val monthStartMillis = today.withDayOfMonth(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    private val lastMonthMillis = today.minusMonths(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private val noConversionNeeded: suspend (Double, String, String) -> Double? = { amount, from, to ->
        if (from == to) amount else null
    }

    @Test
    fun `flags an overall limit exceeded by expenses within the current month`() = runTest {
        val limit = SpendingLimit(id = 1, categoryId = null, amountHomeCurrency = 100.0, period = SpendingLimit.PERIOD_MONTHLY)
        val expenses = listOf(
            Expense(id = 1, totalAmount = 60.0, currencyCode = "RON", dateTime = monthStartMillis + 1000),
            Expense(id = 2, totalAmount = 60.0, currencyCode = "RON", dateTime = monthStartMillis + 2000)
        )

        val result = SpendingLimitChecker.findExceeded(expenses, listOf(limit), "RON", today, noConversionNeeded)

        assertEquals(1, result.size)
        assertEquals(120.0, result[0].spent, 0.0)
    }

    @Test
    fun `does not flag when spend is under the limit`() = runTest {
        val limit = SpendingLimit(id = 1, categoryId = null, amountHomeCurrency = 100.0, period = SpendingLimit.PERIOD_MONTHLY)
        val expenses = listOf(Expense(id = 1, totalAmount = 50.0, currencyCode = "RON", dateTime = monthStartMillis + 1000))

        val result = SpendingLimitChecker.findExceeded(expenses, listOf(limit), "RON", today, noConversionNeeded)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `ignores expenses from before the current period window`() = runTest {
        val limit = SpendingLimit(id = 1, categoryId = null, amountHomeCurrency = 10.0, period = SpendingLimit.PERIOD_MONTHLY)
        val expenses = listOf(Expense(id = 1, totalAmount = 500.0, currencyCode = "RON", dateTime = lastMonthMillis))

        val result = SpendingLimitChecker.findExceeded(expenses, listOf(limit), "RON", today, noConversionNeeded)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `a category-scoped limit only counts expenses in that category`() = runTest {
        val limit = SpendingLimit(id = 1, categoryId = 5, amountHomeCurrency = 10.0, period = SpendingLimit.PERIOD_MONTHLY)
        val expenses = listOf(
            Expense(id = 1, totalAmount = 500.0, currencyCode = "RON", dateTime = monthStartMillis + 1000, categoryId = 6),
            Expense(id = 2, totalAmount = 5.0, currencyCode = "RON", dateTime = monthStartMillis + 1000, categoryId = 5)
        )

        val result = SpendingLimitChecker.findExceeded(expenses, listOf(limit), "RON", today, noConversionNeeded)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `an expense that cannot be converted is skipped from the sum, not treated as a crash`() = runTest {
        val limit = SpendingLimit(id = 1, categoryId = null, amountHomeCurrency = 10.0, period = SpendingLimit.PERIOD_MONTHLY)
        val expenses = listOf(Expense(id = 1, totalAmount = 500.0, currencyCode = "USD", dateTime = monthStartMillis + 1000))

        val result = SpendingLimitChecker.findExceeded(expenses, listOf(limit), "RON", today, noConversionNeeded)

        assertTrue(result.isEmpty())
    }
}
