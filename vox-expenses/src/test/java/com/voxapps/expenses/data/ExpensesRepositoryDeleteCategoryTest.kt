package com.voxapps.expenses.data

import android.content.Context
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

/** What becomes of the records when the label they were filed under is deleted. */
class ExpensesRepositoryDeleteCategoryTest {

    private lateinit var expenseDao: ExpenseDao
    private lateinit var categoryDao: CategoryDao
    private lateinit var remapRuleDao: RemapRuleDao
    private lateinit var repository: ExpensesRepository

    private val fallback = Category(id = 1L, name = "Uncategorised", colorArgb = 0L, createdAt = 0L, isDefault = true)
    private val cafes = Category(id = 4L, name = "Cafes", colorArgb = 0L, createdAt = 0L)

    @Before
    fun setup() {
        expenseDao = mockk(relaxed = true)
        categoryDao = mockk(relaxed = true)
        remapRuleDao = mockk(relaxed = true)
        repository = ExpensesRepository(
            expenseDao, categoryDao, mockk(relaxed = true), mockk(relaxed = true), remapRuleDao,
            mockk(relaxed = true), mockk<Context>(), mockk(relaxed = true), mockk(relaxed = true),
            mockk(relaxed = true), mockk(relaxed = true)
        )
        coEvery { remapRuleDao.getAll() } returns emptyList()
        coEvery { categoryDao.getAll() } returns listOf(fallback, cafes)
    }

    @Test
    fun `the records land on the fallback category, not on none at all`() = runTest {
        repository.deleteCategory(cafes)

        coVerify(exactly = 1) { expenseDao.reassignCategory(cafes.id, fallback.id, any()) }
        coVerify(exactly = 0) { expenseDao.clearCategory(any(), any()) }
        coVerify(exactly = 1) { categoryDao.delete(cafes) }
    }

    /** Nowhere to send them is the one case where a record is left without a category — and it is
     *  not reachable in a database the app seeded. */
    @Test
    fun `with no fallback stored the records keep no category`() = runTest {
        coEvery { categoryDao.getAll() } returns listOf(cafes)

        repository.deleteCategory(cafes)

        coVerify(exactly = 1) { expenseDao.clearCategory(cafes.id, any()) }
        coVerify(exactly = 0) { expenseDao.reassignCategory(any(), any(), any()) }
    }

    @Test
    fun `the fallback itself is never deleted`() = runTest {
        repository.deleteCategory(fallback)

        coVerify(exactly = 0) { categoryDao.delete(any()) }
        coVerify(exactly = 0) { expenseDao.reassignCategory(any(), any(), any()) }
        coVerify(exactly = 0) { expenseDao.clearCategory(any(), any()) }
    }
}
