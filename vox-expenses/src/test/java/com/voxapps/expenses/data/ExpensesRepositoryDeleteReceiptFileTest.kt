package com.voxapps.expenses.data

import android.content.Context
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ExpensesRepositoryDeleteReceiptFileTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var expenseDao: ExpenseDao
    private lateinit var categoryDao: CategoryDao
    private lateinit var lineItemDao: ExpenseLineItemDao
    private lateinit var spendingLimitDao: SpendingLimitDao
    private lateinit var repository: ExpensesRepository
    private lateinit var receiptsDir: File

    @Before
    fun setup() {
        expenseDao = mockk(relaxed = true)
        categoryDao = mockk(relaxed = true)
        lineItemDao = mockk(relaxed = true)
        spendingLimitDao = mockk(relaxed = true)

        val filesDir = tempFolder.newFolder("files")
        receiptsDir = File(filesDir, "receipts").apply { mkdirs() }

        val context = mockk<Context>()
        every { context.filesDir } returns filesDir

        repository = ExpensesRepository(
            expenseDao, categoryDao, lineItemDao, spendingLimitDao, mockk(relaxed = true), context
        )
    }

    private fun stageReceipt(name: String): File {
        val jpg = File(receiptsDir, name).apply { writeText("fake-jpg") }
        File(receiptsDir, name.substringBeforeLast('.') + ".txt").writeText("fake-ocr-text")
        return jpg
    }

    @Test
    fun `deleteExpense removes the receipt image and its sibling text file`() = runTest {
        stageReceipt("rec_1.jpg")
        val expense = Expense(id = 1, totalAmount = 10.0, currencyCode = "RON", dateTime = 0, receiptImageName = "rec_1.jpg")

        repository.deleteExpense(expense)

        assertFalse(File(receiptsDir, "rec_1.jpg").exists())
        assertFalse(File(receiptsDir, "rec_1.txt").exists())
    }

    @Test
    fun `deleteExpense with no receiptImageName does not throw`() = runTest {
        val expense = Expense(id = 1, totalAmount = 10.0, currencyCode = "RON", dateTime = 0, receiptImageName = null)
        repository.deleteExpense(expense)
    }

    @Test
    fun `deleteExpenseById looks up the filename before deleting the row`() = runTest {
        stageReceipt("rec_2.jpg")
        val expense = Expense(id = 2, totalAmount = 10.0, currencyCode = "RON", dateTime = 0, receiptImageName = "rec_2.jpg")
        coEvery { expenseDao.getWithDetailsById(2) } returns ExpenseWithDetails(expense = expense)

        repository.deleteExpenseById(2)

        assertFalse(File(receiptsDir, "rec_2.jpg").exists())
        assertFalse(File(receiptsDir, "rec_2.txt").exists())
    }

    @Test
    fun `deleteAllExpenses removes every staged receipt`() = runTest {
        stageReceipt("rec_3.jpg")
        stageReceipt("rec_4.jpg")
        val expenses = listOf(
            Expense(id = 3, totalAmount = 10.0, currencyCode = "RON", dateTime = 0, receiptImageName = "rec_3.jpg"),
            Expense(id = 4, totalAmount = 10.0, currencyCode = "RON", dateTime = 0, receiptImageName = "rec_4.jpg")
        )
        coEvery { expenseDao.observeAll() } returns flowOf(expenses)

        repository.deleteAllExpenses()

        assertFalse(File(receiptsDir, "rec_3.jpg").exists())
        assertFalse(File(receiptsDir, "rec_4.jpg").exists())
    }

    @Test
    fun `applyExpenseDeduplication removes receipts for deleted duplicates only`() = runTest {
        val kept = stageReceipt("rec_keep.jpg")
        stageReceipt("rec_dup.jpg")
        coEvery { expenseDao.getReceiptImageNames(listOf(5)) } returns listOf("rec_dup.jpg")

        repository.applyExpenseDeduplication(
            listOf(com.voxapps.expenses.domain.llm.DuplicateGroup(keepId = 6, duplicateIds = listOf(5, 6)))
        )

        assertFalse(File(receiptsDir, "rec_dup.jpg").exists())
        assertTrue(kept.exists())
    }
}
