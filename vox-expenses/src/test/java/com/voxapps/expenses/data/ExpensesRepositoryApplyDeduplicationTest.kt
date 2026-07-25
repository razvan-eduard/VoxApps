package com.voxapps.expenses.data

import com.voxapps.expenses.domain.llm.DuplicateGroup
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

class ExpensesRepositoryApplyDeduplicationTest {

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
        coEvery { expenseDao.getReceiptImageNames(any()) } returns emptyList()
        repository = ExpensesRepository(
            expenseDao, categoryDao, lineItemDao, spendingLimitDao, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true)
        )
    }

    @Test
    fun `deletes every duplicate id except keepId in one bulk call`() = runTest {
        repository.applyExpenseDeduplication(listOf(DuplicateGroup(keepId = 12, duplicateIds = listOf(7, 9))))

        coVerify(exactly = 1) { expenseDao.getReceiptImageNames(listOf(7, 9)) }
        coVerify(exactly = 1) { expenseDao.deleteByIds(listOf(7, 9)) }
    }

    @Test
    fun `does not delete keepId even if redundantly listed as its own duplicate`() = runTest {
        repository.applyExpenseDeduplication(listOf(DuplicateGroup(keepId = 12, duplicateIds = listOf(12, 7))))

        coVerify(exactly = 1) { expenseDao.deleteByIds(listOf(7)) }
    }

    @Test
    fun `applies multiple independent groups as one flattened bulk delete`() = runTest {
        repository.applyExpenseDeduplication(
            listOf(
                DuplicateGroup(keepId = 1, duplicateIds = listOf(2)),
                DuplicateGroup(keepId = 10, duplicateIds = listOf(11, 12))
            )
        )

        coVerify(exactly = 1) { expenseDao.deleteByIds(listOf(2, 11, 12)) }
    }

    @Test
    fun `empty groups list deletes nothing`() = runTest {
        repository.applyExpenseDeduplication(emptyList())

        coVerify(exactly = 0) { expenseDao.deleteByIds(any()) }
        coVerify(exactly = 0) { expenseDao.getReceiptImageNames(any()) }
    }
}
