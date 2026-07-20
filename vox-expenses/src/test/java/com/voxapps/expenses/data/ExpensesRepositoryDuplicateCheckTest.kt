package com.voxapps.expenses.data

import android.content.Context
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class ExpensesRepositoryDuplicateCheckTest {

    private lateinit var expenseDao: ExpenseDao
    private lateinit var categoryDao: CategoryDao
    private lateinit var lineItemDao: ExpenseLineItemDao
    private lateinit var spendingLimitDao: SpendingLimitDao
    private lateinit var repository: ExpensesRepository

    @Before
    fun setup() {
        expenseDao = mockk(relaxed = true)
        categoryDao = mockk(relaxed = true)
        lineItemDao = mockk(relaxed = true)
        spendingLimitDao = mockk(relaxed = true)
        repository = ExpensesRepository(expenseDao, categoryDao, lineItemDao, spendingLimitDao, mockk<Context>())
    }

    @Test
    fun `addExpense skips insert and returns DUPLICATE_ENTRY_RESULT when an exact match already exists`() = runTest {
        val existing = Expense(id = 1, title = "Groceries", totalAmount = 42.0, currencyCode = "RON", vendor = "Carrefour", dateTime = 1000L)
        coEvery { expenseDao.getForDateRange(any(), any()) } returns listOf(existing)

        val result = repository.addExpense(
            title = "Groceries", totalAmount = 42.0, currencyCode = "RON", vendor = "Carrefour",
            bank = null, location = null, dateTime = 1000L, comments = null, categoryId = null
        )

        assertEquals(DUPLICATE_ENTRY_RESULT, result)
        coVerify(exactly = 0) { expenseDao.insert(any()) }
    }

    @Test
    fun `addExpense inserts normally when no duplicate exists that day`() = runTest {
        coEvery { expenseDao.getForDateRange(any(), any()) } returns emptyList()
        coEvery { expenseDao.insert(any()) } returns 7L

        val result = repository.addExpense(
            title = "Groceries", totalAmount = 42.0, currencyCode = "RON", vendor = "Carrefour",
            bank = null, location = null, dateTime = 1000L, comments = null, categoryId = null
        )

        assertEquals(7L, result)
        coVerify(exactly = 1) { expenseDao.insert(any()) }
    }

    @Test
    fun `addExpense catches a same-day duplicate even at a different exact instant`() = runTest {
        // A notification-sourced re-check (e.g. the "force re-check" button) stamps dateTime with
        // the capture instant each time, so it's never exactly equal between attempts — the checker
        // must still catch it via same-day comparison, not require millisecond equality. Both
        // instants are derived from CalendarDateUtils itself (same zone it uses internally) so this
        // holds regardless of the test runner's default timezone.
        val dayStart = com.voxapps.calendar.CalendarDateUtils.startOfDayMillis(java.time.LocalDate.of(2024, 1, 15))
        val morningInstant = dayStart + java.util.concurrent.TimeUnit.HOURS.toMillis(2)
        val eveningInstant = dayStart + java.util.concurrent.TimeUnit.HOURS.toMillis(20)
        val existing = Expense(id = 1, title = "Groceries", totalAmount = 42.0, currencyCode = "RON", vendor = "Carrefour", dateTime = morningInstant)
        coEvery { expenseDao.getForDateRange(any(), any()) } returns listOf(existing)

        val result = repository.addExpense(
            title = "Groceries", totalAmount = 42.0, currencyCode = "RON", vendor = "Carrefour",
            bank = null, location = null, dateTime = eveningInstant, comments = null, categoryId = null
        )

        assertEquals(DUPLICATE_ENTRY_RESULT, result)
        coVerify(exactly = 0) { expenseDao.insert(any()) }
    }

    @Test
    fun `checkForDuplicate = false bypasses the check entirely (Hub import path)`() = runTest {
        val existing = Expense(id = 1, title = "Groceries", totalAmount = 42.0, currencyCode = "RON", vendor = "Carrefour", dateTime = 1000L)
        coEvery { expenseDao.getForDateRange(any(), any()) } returns listOf(existing)
        coEvery { expenseDao.insert(any()) } returns 9L

        val result = repository.addExpense(
            title = "Groceries", totalAmount = 42.0, currencyCode = "RON", vendor = "Carrefour",
            bank = null, location = null, dateTime = 1000L, comments = null, categoryId = null,
            checkForDuplicate = false
        )

        assertEquals(9L, result)
        coVerify(exactly = 1) { expenseDao.insert(any()) }
    }
}
