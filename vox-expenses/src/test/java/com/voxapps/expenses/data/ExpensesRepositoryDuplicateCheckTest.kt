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
        coEvery { expenseDao.getForDateRange(1000L, 1000L) } returns listOf(existing)

        val result = repository.addExpense(
            title = "Groceries", totalAmount = 42.0, currencyCode = "RON", vendor = "Carrefour",
            bank = null, location = null, dateTime = 1000L, comments = null, categoryId = null
        )

        assertEquals(DUPLICATE_ENTRY_RESULT, result)
        coVerify(exactly = 0) { expenseDao.insert(any()) }
    }

    @Test
    fun `addExpense inserts normally when no duplicate exists at that instant`() = runTest {
        coEvery { expenseDao.getForDateRange(1000L, 1000L) } returns emptyList()
        coEvery { expenseDao.insert(any()) } returns 7L

        val result = repository.addExpense(
            title = "Groceries", totalAmount = 42.0, currencyCode = "RON", vendor = "Carrefour",
            bank = null, location = null, dateTime = 1000L, comments = null, categoryId = null
        )

        assertEquals(7L, result)
        coVerify(exactly = 1) { expenseDao.insert(any()) }
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
