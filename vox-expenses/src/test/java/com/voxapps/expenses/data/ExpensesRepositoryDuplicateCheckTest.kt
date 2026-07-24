package com.voxapps.expenses.data

import android.content.Context
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

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
        repository = ExpensesRepository(
            expenseDao, categoryDao, lineItemDao, spendingLimitDao, mockk(relaxed = true), mockk<Context>()
        )
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

    // --- near-duplicate detection (off by default) ---

    @Test
    fun `near-duplicate detection stays off by default even with a fuzzy match nearby in time`() = runTest {
        val nearby = Expense(id = 1, title = "Example Store", totalAmount = 99.0, currencyCode = "RON", dateTime = 1000L)
        coEvery { expenseDao.getForDateRange(any(), any()) } returns listOf(nearby)
        coEvery { expenseDao.insert(any()) } returns 5L

        val result = repository.addExpense(
            title = "Payment to Example Store", totalAmount = 99.0, currencyCode = "RON", vendor = null,
            bank = null, location = null, dateTime = 1050L, comments = null, categoryId = null
        )

        assertEquals(5L, result)
        coVerify(exactly = 1) { expenseDao.insert(any()) }
        coVerify(exactly = 0) { expenseDao.update(any()) }
    }

    @Test
    fun `near-duplicate detection enabled merges a fuzzy match within the time window instead of inserting`() = runTest {
        val nearby = Expense(id = 1, title = "Example Store", totalAmount = 99.0, currencyCode = "RON", dateTime = 1000L)
        coEvery { expenseDao.getForDateRange(any(), any()) } returns listOf(nearby)
        coEvery { expenseDao.update(any()) } just Runs

        val result = repository.addExpense(
            title = "Payment to Example Store", totalAmount = 99.0, currencyCode = "RON", vendor = null,
            bank = "Some Bank", location = null, dateTime = 1050L, comments = null, categoryId = null,
            nearDuplicateCheckEnabled = true,
            nearDuplicateFuzzyMatch = true,
            nearDuplicateTimeWindowMillis = TimeUnit.MINUTES.toMillis(2)
        )

        assertEquals(NEAR_DUPLICATE_MERGED_RESULT, result)
        coVerify(exactly = 1) { expenseDao.update(match { it.id == 1L && it.bank == "Some Bank" }) }
        coVerify(exactly = 0) { expenseDao.insert(any()) }
    }

    @Test
    fun `near-duplicate detection enabled but outside the time window inserts normally`() = runTest {
        coEvery { expenseDao.getForDateRange(any(), any()) } returns emptyList()
        coEvery { expenseDao.insert(any()) } returns 5L

        val result = repository.addExpense(
            title = "Example Store", totalAmount = 99.0, currencyCode = "RON", vendor = null,
            bank = null, location = null, dateTime = 1000L + TimeUnit.HOURS.toMillis(1), comments = null, categoryId = null,
            nearDuplicateCheckEnabled = true,
            nearDuplicateFuzzyMatch = true,
            nearDuplicateTimeWindowMillis = TimeUnit.MINUTES.toMillis(2)
        )

        assertEquals(5L, result)
        coVerify(exactly = 1) { expenseDao.insert(any()) }
        coVerify(exactly = 0) { expenseDao.update(any()) }
    }
}
