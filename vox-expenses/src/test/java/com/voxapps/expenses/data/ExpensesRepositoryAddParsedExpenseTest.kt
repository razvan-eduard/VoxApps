package com.voxapps.expenses.data

import android.content.Context
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class ExpensesRepositoryAddParsedExpenseTest {

    private lateinit var expenseDao: ExpenseDao
    private lateinit var categoryDao: CategoryDao
    private lateinit var lineItemDao: ExpenseLineItemDao
    private lateinit var spendingLimitDao: SpendingLimitDao
    private lateinit var merchantCategoryMemoryDao: MerchantCategoryMemoryDao
    private lateinit var repository: ExpensesRepository

    private val shopping = Category(id = 1L, name = "Shopping", colorArgb = 0xFFEF5350L, createdAt = 0L)
    private val groceries = Category(id = 2L, name = "Groceries", colorArgb = 0xFF26A69AL, createdAt = 0L)

    @Before
    fun setup() {
        expenseDao = mockk(relaxed = true)
        categoryDao = mockk(relaxed = true)
        lineItemDao = mockk(relaxed = true)
        spendingLimitDao = mockk(relaxed = true)
        merchantCategoryMemoryDao = mockk(relaxed = true)
        repository = ExpensesRepository(
            expenseDao, categoryDao, lineItemDao, spendingLimitDao, merchantCategoryMemoryDao, mockk<Context>()
        )
        coEvery { expenseDao.getForDateRange(any(), any()) } returns emptyList()
        coEvery { expenseDao.insert(any()) } returns 99L
    }

    @Test
    fun `a learned mapping at threshold overrides the spoken category entirely`() = runTest {
        every { categoryDao.observeAll() } returns flowOf(listOf(shopping, groceries))
        coEvery { merchantCategoryMemoryDao.getLearnedCategoryId("lidl", 1) } returns groceries.id

        repository.addParsedExpense(
            title = null, totalAmount = 10.0, currencyCode = "RON", vendor = "Lidl", bank = null,
            location = null, comments = null, dateTime = 1000L,
            spokenCategory = "Shopping", defaultCategoryId = null, autoCreate = false,
            merchantMemoryEnabled = true, merchantMemoryThreshold = 1
        )

        coVerify(exactly = 1) { expenseDao.insert(match { it.categoryId == groceries.id }) }
    }

    @Test
    fun `merchant memory disabled never queries the learned mapping and resolves normally`() = runTest {
        every { categoryDao.observeAll() } returns flowOf(listOf(shopping, groceries))

        repository.addParsedExpense(
            title = null, totalAmount = 10.0, currencyCode = "RON", vendor = "Lidl", bank = null,
            location = null, comments = null, dateTime = 1000L,
            spokenCategory = "Shopping", defaultCategoryId = null, autoCreate = false,
            merchantMemoryEnabled = false
        )

        coVerify(exactly = 0) { merchantCategoryMemoryDao.getLearnedCategoryId(any(), any()) }
        coVerify(exactly = 1) { expenseDao.insert(match { it.categoryId == shopping.id }) }
    }

    @Test
    fun `a learned mapping pointing at a since-deleted category falls through to normal resolution`() = runTest {
        every { categoryDao.observeAll() } returns flowOf(listOf(shopping))
        coEvery { merchantCategoryMemoryDao.getLearnedCategoryId("lidl", 1) } returns groceries.id // no longer exists

        repository.addParsedExpense(
            title = null, totalAmount = 10.0, currencyCode = "RON", vendor = "Lidl", bank = null,
            location = null, comments = null, dateTime = 1000L,
            spokenCategory = "Shopping", defaultCategoryId = null, autoCreate = false,
            merchantMemoryEnabled = true, merchantMemoryThreshold = 1
        )

        coVerify(exactly = 1) { expenseDao.insert(match { it.categoryId == shopping.id }) }
    }

    @Test
    fun `auto-creating a category fetches the most recent category color for adjacency`() = runTest {
        every { categoryDao.observeAll() } returns flowOf(listOf(shopping))
        coEvery { expenseDao.getMostRecentCategoryColor() } returns shopping.colorArgb
        coEvery { categoryDao.insert(any()) } returns 42L

        val id = repository.addParsedExpense(
            title = null, totalAmount = 10.0, currencyCode = "RON", vendor = "Kaufland", bank = null,
            location = null, comments = null, dateTime = 1000L,
            spokenCategory = "Produce", defaultCategoryId = null, autoCreate = true
        )

        coVerify(exactly = 1) { expenseDao.getMostRecentCategoryColor() }
        coVerify(exactly = 1) { categoryDao.insert(match { it.name == "Produce" }) }
        assertEquals(99L, id)
    }
}
