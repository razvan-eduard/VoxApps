package com.voxapps.expenses.data

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

class ExpensesRepositoryApplyCategoryMergeTest {

    private lateinit var expenseDao: ExpenseDao
    private lateinit var categoryDao: CategoryDao
    private lateinit var lineItemDao: ExpenseLineItemDao
    private lateinit var spendingLimitDao: SpendingLimitDao
    private lateinit var repository: ExpensesRepository

    private val food = Category(id = 1, name = "Mancare", colorArgb = 0xFF0000, position = 0, createdAt = 0)
    private val groceries = Category(id = 2, name = "Groceries", colorArgb = 0xFF00FF, position = 1, createdAt = 0)
    private val transport = Category(id = 3, name = "Transport", colorArgb = 0x00FF00, position = 2, createdAt = 0)

    @Before
    fun setup() {
        expenseDao = mockk(relaxed = true)
        categoryDao = mockk(relaxed = true)
        lineItemDao = mockk(relaxed = true)
        spendingLimitDao = mockk(relaxed = true)
        repository = ExpensesRepository(
            expenseDao, categoryDao, lineItemDao, spendingLimitDao, mockk(relaxed = true), mockk(relaxed = true)
        )
        coEvery { categoryDao.observeAll() } returns flowOf(listOf(food, groceries, transport))
    }

    @Test
    fun `reassigns expenses and deletes the old category when both names resolve`() = runTest {
        repository.applyCategoryMerge(mapOf("Groceries" to "Mancare"))

        coVerify(exactly = 1) { expenseDao.reassignCategory(groceries.id, food.id) }
        coVerify(exactly = 1) { categoryDao.delete(groceries) }
    }

    @Test
    fun `is case-insensitive when matching names`() = runTest {
        repository.applyCategoryMerge(mapOf("groceries" to "mancare"))

        coVerify(exactly = 1) { expenseDao.reassignCategory(groceries.id, food.id) }
        coVerify(exactly = 1) { categoryDao.delete(groceries) }
    }

    @Test
    fun `skips entries where the old name does not exist`() = runTest {
        repository.applyCategoryMerge(mapOf("Nonexistent" to "Mancare"))

        coVerify(exactly = 0) { expenseDao.reassignCategory(any(), any()) }
        coVerify(exactly = 0) { categoryDao.delete(any()) }
    }

    @Test
    fun `skips entries where the canonical name does not exist`() = runTest {
        repository.applyCategoryMerge(mapOf("Groceries" to "Nonexistent"))

        coVerify(exactly = 0) { expenseDao.reassignCategory(any(), any()) }
        coVerify(exactly = 0) { categoryDao.delete(any()) }
    }

    @Test
    fun `skips same-name entries (no-op merge)`() = runTest {
        repository.applyCategoryMerge(mapOf("Transport" to "Transport"))

        coVerify(exactly = 0) { expenseDao.reassignCategory(any(), any()) }
        coVerify(exactly = 0) { categoryDao.delete(any()) }
    }

    @Test
    fun `applies multiple independent merge entries`() = runTest {
        repository.applyCategoryMerge(mapOf("Groceries" to "Mancare", "Transport" to "Mancare"))

        coVerify(exactly = 1) { expenseDao.reassignCategory(groceries.id, food.id) }
        coVerify(exactly = 1) { expenseDao.reassignCategory(transport.id, food.id) }
        coVerify(exactly = 1) { categoryDao.delete(groceries) }
        coVerify(exactly = 1) { categoryDao.delete(transport) }
    }
}
