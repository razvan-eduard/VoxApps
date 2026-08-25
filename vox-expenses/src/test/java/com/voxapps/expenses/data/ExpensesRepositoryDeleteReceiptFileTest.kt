package com.voxapps.expenses.data

import android.content.Context
import com.voxapps.attachments.AttachmentDao
import com.voxapps.attachments.AttachmentEntity
import com.voxapps.attachments.AttachmentSource
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Real (not mocked) in-memory [AttachmentDao] so these tests exercise the actual
 *  reference-counted cleanup ([AttachmentDao.countByFileName]) rather than asserting against a
 *  relaxed mock's default behavior — the whole point of these tests is that guard. */
private class FakeAttachmentDao : AttachmentDao {
    private val rows = mutableListOf<AttachmentEntity>()
    private var nextId = 1L

    // observeForInternal is the @Query member; the plain observeFor is a concrete wrapper on the
    // interface that adds distinctUntilChanged, so the fake supplies the raw half and inherits it.
    // observeRecordIdsWithAttachments has no wrapper (see its doc) and is overridden directly.
    override fun observeForInternal(recordType: String, recordId: Long): Flow<List<AttachmentEntity>> =
        flowOf(rows.filter { it.recordType == recordType && it.recordId == recordId })

    override suspend fun getFor(recordType: String, recordId: Long): List<AttachmentEntity> =
        rows.filter { it.recordType == recordType && it.recordId == recordId }

    override suspend fun countFor(recordType: String, recordId: Long, source: String): Int =
        rows.count { it.recordType == recordType && it.recordId == recordId && it.source == source }

    override suspend fun countByFileName(recordType: String, fileName: String): Int =
        rows.count { it.recordType == recordType && it.fileName == fileName }

    override fun observeRecordIdsWithAttachments(recordType: String): Flow<List<Long>> =
        flowOf(rows.filter { it.recordType == recordType }.map { it.recordId }.distinct())

    override suspend fun getRecordIdsWithAttachments(recordType: String): List<Long> =
        rows.filter { it.recordType == recordType }.map { it.recordId }.distinct()

    override suspend fun reassignRecordId(recordType: String, oldRecordId: Long, newRecordId: Long, fileName: String) {
        rows.replaceAll {
            if (it.recordType == recordType && it.recordId == oldRecordId && it.fileName == fileName) {
                it.copy(recordId = newRecordId)
            } else {
                it
            }
        }
    }

    override suspend fun insert(entity: AttachmentEntity): Long {
        val withId = entity.copy(id = nextId++)
        rows += withId
        return withId.id
    }

    override suspend fun getById(id: Long): AttachmentEntity? = rows.firstOrNull { it.id == id }

    override suspend fun delete(id: Long) {
        rows.removeAll { it.id == id }
    }

    override suspend fun deleteAllForInternal(recordType: String, recordId: Long) {
        rows.removeAll { it.recordType == recordType && it.recordId == recordId }
    }

    override suspend fun deleteGroupInternal(recordType: String, recordId: Long, groupId: String) {
        rows.removeAll { it.recordType == recordType && it.recordId == recordId && it.groupId == groupId }
    }
}

class ExpensesRepositoryDeleteReceiptFileTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var expenseDao: ExpenseDao
    private lateinit var categoryDao: CategoryDao
    private lateinit var lineItemDao: ExpenseLineItemDao
    private lateinit var spendingLimitDao: SpendingLimitDao
    private lateinit var attachmentDao: FakeAttachmentDao
    private lateinit var repository: ExpensesRepository
    private lateinit var receiptsDir: File

    @Before
    fun setup() {
        expenseDao = mockk(relaxed = true)
        categoryDao = mockk(relaxed = true)
        lineItemDao = mockk(relaxed = true)
        spendingLimitDao = mockk(relaxed = true)
        attachmentDao = FakeAttachmentDao()

        val filesDir = tempFolder.newFolder("files")
        receiptsDir = File(filesDir, "receipts").apply { mkdirs() }

        val context = mockk<Context>()
        every { context.filesDir } returns filesDir

        repository = ExpensesRepository(
            expenseDao, categoryDao, lineItemDao, spendingLimitDao, mockk(relaxed = true), mockk(relaxed = true), context, attachmentDao,
            mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true)
        )
    }

    private fun stageReceipt(name: String): File {
        val jpg = File(receiptsDir, name).apply { writeText("fake-jpg") }
        File(receiptsDir, name.substringBeforeLast('.') + ".txt").writeText("fake-ocr-text")
        return jpg
    }

    /** Mirrors what [ExpensesRepository.addExpense] now does whenever a receipt is set — these
     *  tests exercise [ExpensesRepository]'s delete-side cleanup in isolation, so the attachment
     *  row is seeded directly rather than routing every test through a full addExpense call. */
    private suspend fun trackReceipt(expenseId: Long, fileName: String) {
        attachmentDao.insert(
            AttachmentEntity(
                recordType = ExpensesAttachments.RECORD_TYPE, recordId = expenseId, fileName = fileName,
                source = AttachmentSource.SCANNED, createdAt = 0L
            )
        )
    }

    @Test
    fun `deleteExpense removes the receipt image and its sibling text file`() = runTest {
        stageReceipt("rec_1.jpg")
        trackReceipt(1, "rec_1.jpg")
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
    fun `deleteExpenseById deletes the tracked receipt file`() = runTest {
        stageReceipt("rec_2.jpg")
        trackReceipt(2, "rec_2.jpg")
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
        trackReceipt(3, "rec_3.jpg")
        trackReceipt(4, "rec_4.jpg")
        val expenses = listOf(
            Expense(id = 3, totalAmount = 10.0, currencyCode = "RON", dateTime = 0, receiptImageName = "rec_3.jpg"),
            Expense(id = 4, totalAmount = 10.0, currencyCode = "RON", dateTime = 0, receiptImageName = "rec_4.jpg")
        )
        coEvery { expenseDao.observeAll() } returns flowOf(expenses)
        coEvery { expenseDao.getAll() } returns expenses
        // Deleting everything means the archive too, so it is the unfiltered read that answers here.
        coEvery { expenseDao.getAllIncludingArchived() } returns expenses

        repository.deleteAllExpenses()

        assertFalse(File(receiptsDir, "rec_3.jpg").exists())
        assertFalse(File(receiptsDir, "rec_4.jpg").exists())
    }

    @Test
    fun `deleteExpenseById keeps the receipt file when another row still references it`() = runTest {
        // Reproduces an import's insert-then-delete-old-rows reconciliation: a freshly-inserted
        // replacement row (id 8) reuses the same receiptImageName as the old row (id 7) about to be
        // deleted here — both now have their own attachment row pointing at the same file.
        val kept = stageReceipt("rec_shared.jpg")
        trackReceipt(7, "rec_shared.jpg")
        trackReceipt(8, "rec_shared.jpg")
        val expense = Expense(id = 7, totalAmount = 10.0, currencyCode = "RON", dateTime = 0, receiptImageName = "rec_shared.jpg")
        coEvery { expenseDao.getWithDetailsById(7) } returns ExpenseWithDetails(expense = expense)

        repository.deleteExpenseById(7)

        assertTrue(kept.exists())
    }

    @Test
    fun `applyExpenseDeduplication removes receipts for deleted duplicates only`() = runTest {
        val kept = stageReceipt("rec_keep.jpg")
        stageReceipt("rec_dup.jpg")
        trackReceipt(6, "rec_keep.jpg")
        trackReceipt(5, "rec_dup.jpg")
        val keeper = Expense(id = 6, totalAmount = 10.0, currencyCode = "RON", dateTime = 0, receiptImageName = "rec_keep.jpg")
        val duplicate = Expense(id = 5, totalAmount = 10.0, currencyCode = "RON", dateTime = 0, receiptImageName = "rec_dup.jpg")
        coEvery { expenseDao.getWithDetailsById(6) } returns ExpenseWithDetails(expense = keeper)
        coEvery { expenseDao.getWithDetailsById(5) } returns ExpenseWithDetails(expense = duplicate)

        repository.applyExpenseDeduplication(
            listOf(com.voxapps.expenses.domain.llm.DuplicateGroup(keepId = 6, duplicateIds = listOf(5, 6)))
        )

        assertFalse(File(receiptsDir, "rec_dup.jpg").exists())
        assertTrue(kept.exists())
    }
}
