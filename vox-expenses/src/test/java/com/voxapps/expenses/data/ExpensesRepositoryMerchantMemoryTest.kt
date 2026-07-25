package com.voxapps.expenses.data

import android.content.Context
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class ExpensesRepositoryMerchantMemoryTest {

    private lateinit var expenseDao: ExpenseDao
    private lateinit var categoryDao: CategoryDao
    private lateinit var lineItemDao: ExpenseLineItemDao
    private lateinit var spendingLimitDao: SpendingLimitDao
    private lateinit var merchantCategoryMemoryDao: MerchantCategoryMemoryDao
    private lateinit var repository: ExpensesRepository

    @Before
    fun setup() {
        expenseDao = mockk(relaxed = true)
        categoryDao = mockk(relaxed = true)
        lineItemDao = mockk(relaxed = true)
        spendingLimitDao = mockk(relaxed = true)
        merchantCategoryMemoryDao = mockk(relaxed = true)
        repository = ExpensesRepository(
            expenseDao, categoryDao, lineItemDao, spendingLimitDao, merchantCategoryMemoryDao, mockk<Context>(), mockk(relaxed = true), mockk(relaxed = true)
        )
    }

    @Test
    fun `recordManualCategoryChange is a no-op for a blank vendor`() = runTest {
        repository.recordManualCategoryChange("   ", 5L)

        coVerify(exactly = 0) { merchantCategoryMemoryDao.get(any()) }
        coVerify(exactly = 0) { merchantCategoryMemoryDao.upsert(any()) }
        coVerify(exactly = 0) { merchantCategoryMemoryDao.delete(any()) }
    }

    @Test
    fun `recordManualCategoryChange deletes the row when categoryId is null`() = runTest {
        repository.recordManualCategoryChange("Lidl", null)

        coVerify(exactly = 1) { merchantCategoryMemoryDao.delete("lidl") }
        coVerify(exactly = 0) { merchantCategoryMemoryDao.upsert(any()) }
    }

    @Test
    fun `recordManualCategoryChange starts a fresh streak at 1 when no row exists yet`() = runTest {
        coEvery { merchantCategoryMemoryDao.get("lidl") } returns null

        repository.recordManualCategoryChange("Lidl", 5L)

        coVerify(exactly = 1) { merchantCategoryMemoryDao.upsert(match { it.vendorKey == "lidl" && it.categoryId == 5L && it.consecutiveCount == 1 }) }
    }

    @Test
    fun `recordManualCategoryChange increments the streak when the same category is chosen again`() = runTest {
        coEvery { merchantCategoryMemoryDao.get("lidl") } returns MerchantCategoryMemory("lidl", 5L, 2, 1000L)

        repository.recordManualCategoryChange("Lidl", 5L)

        coVerify(exactly = 1) { merchantCategoryMemoryDao.upsert(match { it.vendorKey == "lidl" && it.categoryId == 5L && it.consecutiveCount == 3 }) }
    }

    @Test
    fun `recordManualCategoryChange resets the streak to 1 when a different category is chosen`() = runTest {
        coEvery { merchantCategoryMemoryDao.get("lidl") } returns MerchantCategoryMemory("lidl", 5L, 4, 1000L)

        repository.recordManualCategoryChange("Lidl", 9L)

        coVerify(exactly = 1) { merchantCategoryMemoryDao.upsert(match { it.vendorKey == "lidl" && it.categoryId == 9L && it.consecutiveCount == 1 }) }
    }

    @Test
    fun `getLearnedCategoryId returns null for a blank vendor without querying the dao`() = runTest {
        val result = repository.getLearnedCategoryId("  ", 3)

        assertNull(result)
        coVerify(exactly = 0) { merchantCategoryMemoryDao.getLearnedCategoryId(any(), any()) }
    }

    @Test
    fun `getLearnedCategoryId normalizes the vendor and passes the threshold through`() = runTest {
        coEvery { merchantCategoryMemoryDao.getLearnedCategoryId("lidl", 3) } returns 5L

        val result = repository.getLearnedCategoryId(" Lidl ", 3)

        assertEquals(5L, result)
    }
}
