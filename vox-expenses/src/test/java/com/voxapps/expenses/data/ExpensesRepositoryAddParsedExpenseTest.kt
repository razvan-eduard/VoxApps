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
    private lateinit var remapRuleDao: RemapRuleDao
    private lateinit var repository: ExpensesRepository

    private val shopping = Category(id = 1L, name = "Shopping", colorArgb = 0xFFEF5350L, createdAt = 0L)
    private val groceries = Category(id = 2L, name = "Groceries", colorArgb = 0xFF26A69AL, createdAt = 0L)

    private fun lidlRule(categoryId: Long, enabled: Boolean = true) = RemapRuleEntity(
        id = 1L, name = "lidl",
        matchJson = RemapRuleJson.encode(mapOf("vendor" to "lidl")),
        setJson = RemapRuleJson.encode(mapOf("categoryId" to categoryId.toString())),
        origin = RemapRuleEntity.ORIGIN_USER, enabled = enabled, updatedAt = 0L
    )

    @Before
    fun setup() {
        expenseDao = mockk(relaxed = true)
        categoryDao = mockk(relaxed = true)
        lineItemDao = mockk(relaxed = true)
        spendingLimitDao = mockk(relaxed = true)
        remapRuleDao = mockk(relaxed = true)
        repository = ExpensesRepository(
            expenseDao, categoryDao, lineItemDao, spendingLimitDao, remapRuleDao, mockk(relaxed = true), mockk<Context>(), mockk(relaxed = true), mockk(relaxed = true),
            mockk(relaxed = true), mockk(relaxed = true)
        )
        coEvery { expenseDao.getForDateRange(any(), any()) } returns emptyList()
        coEvery { expenseDao.insert(any()) } returns 99L
    }

    @Test
    fun `an enabled rule's category overrides the spoken category entirely`() = runTest {
        every { categoryDao.observeAll() } returns flowOf(listOf(shopping, groceries))
        coEvery { categoryDao.getAll() } returns listOf(shopping, groceries)
        coEvery { remapRuleDao.getAll() } returns listOf(lidlRule(groceries.id))

        repository.addParsedExpense(
            title = null, totalAmount = 10.0, currencyCode = "RON", vendor = "Lidl", bank = null,
            location = null, comments = null, dateTime = 1000L,
            spokenCategory = "Shopping", defaultCategoryId = null, autoCreate = false
        )

        coVerify(exactly = 1) { expenseDao.insert(match { it.categoryId == groceries.id }) }
    }

    @Test
    fun `with no rules stored, resolution proceeds normally`() = runTest {
        every { categoryDao.observeAll() } returns flowOf(listOf(shopping, groceries))
        coEvery { categoryDao.getAll() } returns listOf(shopping, groceries)
        coEvery { remapRuleDao.getAll() } returns emptyList()

        repository.addParsedExpense(
            title = null, totalAmount = 10.0, currencyCode = "RON", vendor = "Lidl", bank = null,
            location = null, comments = null, dateTime = 1000L,
            spokenCategory = "Shopping", defaultCategoryId = null, autoCreate = false
        )

        coVerify(exactly = 1) { expenseDao.insert(match { it.categoryId == shopping.id }) }
    }

    @Test
    fun `a rule pointing at a since-deleted category falls through to normal resolution`() = runTest {
        every { categoryDao.observeAll() } returns flowOf(listOf(shopping))
        coEvery { categoryDao.getAll() } returns listOf(shopping)
        coEvery { remapRuleDao.getAll() } returns listOf(lidlRule(groceries.id)) // category no longer exists

        repository.addParsedExpense(
            title = null, totalAmount = 10.0, currencyCode = "RON", vendor = "Lidl", bank = null,
            location = null, comments = null, dateTime = 1000L,
            spokenCategory = "Shopping", defaultCategoryId = null, autoCreate = false
        )

        coVerify(exactly = 1) { expenseDao.insert(match { it.categoryId == shopping.id }) }
    }

    @Test
    fun `auto-creating a category fetches the most recent category color for adjacency`() = runTest {
        every { categoryDao.observeAll() } returns flowOf(listOf(shopping))
        coEvery { categoryDao.getAll() } returns listOf(shopping)
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
