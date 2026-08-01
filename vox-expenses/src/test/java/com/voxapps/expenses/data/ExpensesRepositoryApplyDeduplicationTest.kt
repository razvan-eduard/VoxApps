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
            expenseDao, categoryDao, lineItemDao, spendingLimitDao, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
            mockk(relaxed = true)
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

    @Test
    fun `backfills the kept row's blank fields from a discarded duplicate before deleting it`() = runTest {
        // Manually-typed, so the kept row wins on source tier alone (400 vs 100), yet is missing
        // bank/location that only the voice-captured duplicate has — those should still get adopted.
        val keeper = Expense(
            id = 12, title = "Groceries", totalAmount = 42.0, currencyCode = "RON", vendor = "Lidl",
            dateTime = 1000L, source = ExpenseSource.MANUAL
        )
        val duplicate = Expense(
            id = 7, title = null, totalAmount = 42.0, currencyCode = "RON", vendor = null,
            bank = "Some Bank", location = "Bucharest", dateTime = 1000L, source = ExpenseSource.VOICE
        )
        coEvery { expenseDao.getWithDetailsById(12) } returns ExpenseWithDetails(keeper)
        coEvery { expenseDao.getWithDetailsById(7) } returns ExpenseWithDetails(duplicate)

        repository.applyExpenseDeduplication(listOf(DuplicateGroup(keepId = 12, duplicateIds = listOf(7))))

        coVerify(exactly = 1) {
            expenseDao.update(match { it.id == 12L && it.bank == "Some Bank" && it.location == "Bucharest" && it.vendor == "Lidl" })
        }
        coVerify(exactly = 1) { expenseDao.deleteByIds(listOf(7)) }
    }

    @Test
    fun `does not update the kept row when the duplicate has nothing new to offer`() = runTest {
        val keeper = Expense(id = 12, title = "Groceries", totalAmount = 42.0, currencyCode = "RON", vendor = "Lidl", dateTime = 1000L)
        val duplicate = Expense(id = 7, title = "Groceries", totalAmount = 42.0, currencyCode = "RON", vendor = "Lidl", dateTime = 1000L)
        coEvery { expenseDao.getWithDetailsById(12) } returns ExpenseWithDetails(keeper)
        coEvery { expenseDao.getWithDetailsById(7) } returns ExpenseWithDetails(duplicate)

        repository.applyExpenseDeduplication(listOf(DuplicateGroup(keepId = 12, duplicateIds = listOf(7))))

        coVerify(exactly = 0) { expenseDao.update(any()) }
        coVerify(exactly = 1) { expenseDao.deleteByIds(listOf(7)) }
    }

    @Test
    fun `a receipt image adopted into the kept row is not deleted with its donor`() = runTest {
        val keeper = Expense(id = 12, title = "Groceries", totalAmount = 42.0, currencyCode = "RON", dateTime = 1000L, receiptImageName = null)
        val duplicate = Expense(id = 7, title = "Groceries", totalAmount = 42.0, currencyCode = "RON", dateTime = 1000L, receiptImageName = "rec_1.jpg")
        coEvery { expenseDao.getWithDetailsById(12) } returns ExpenseWithDetails(keeper)
        coEvery { expenseDao.getWithDetailsById(7) } returns ExpenseWithDetails(duplicate)
        coEvery { expenseDao.getReceiptImageNames(listOf(7)) } returns listOf("rec_1.jpg")

        repository.applyExpenseDeduplication(listOf(DuplicateGroup(keepId = 12, duplicateIds = listOf(7))))

        coVerify(exactly = 1) { expenseDao.update(match { it.id == 12L && it.receiptImageName == "rec_1.jpg" }) }
    }
}
