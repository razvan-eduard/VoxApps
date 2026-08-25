package com.voxapps.expenses.data

import android.content.Context
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** Putting records out of the way, bringing them back, and letting them go. */
class ExpensesRepositoryArchiveTest {

    private lateinit var expenseDao: ExpenseDao
    private lateinit var repository: ExpensesRepository

    @Before
    fun setup() {
        expenseDao = mockk(relaxed = true)
        repository = ExpensesRepository(
            expenseDao, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
            mockk(relaxed = true), mockk(relaxed = true), mockk<Context>(), mockk(relaxed = true),
            mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true)
        )
    }

    @Test
    fun `archiving stamps the moment it happened`() = runTest {
        val before = System.currentTimeMillis()

        assertEquals(2, repository.archiveExpenses(listOf(1L, 2L)))

        coVerify(exactly = 1) {
            expenseDao.setArchivedAt(listOf(1L, 2L), match { it != null && it >= before }, any())
        }
    }

    @Test
    fun `restoring clears it`() = runTest {
        repository.restoreExpenses(listOf(3L))

        coVerify(exactly = 1) { expenseDao.setArchivedAt(listOf(3L), null, any()) }
    }

    /** Both are edits as far as another device is concerned, so both bump the field its merge
     *  compares — otherwise a sync would hand the record straight back. */
    @Test
    fun `both count as an edit`() = runTest {
        val before = System.currentTimeMillis()

        repository.archiveExpenses(listOf(1L))
        repository.restoreExpenses(listOf(1L))

        coVerify(exactly = 2) { expenseDao.setArchivedAt(any(), any(), match { it >= before }) }
    }

    @Test
    fun `an empty selection touches nothing`() = runTest {
        assertEquals(0, repository.archiveExpenses(emptyList()))
        assertEquals(0, repository.restoreExpenses(emptyList()))

        coVerify(exactly = 0) { expenseDao.setArchivedAt(any(), any(), any()) }
    }

    /** Deleted the ordinary way, one at a time: the tombstone is what stops another device sending
     *  the record back, and it is per record. */
    @Test
    fun `what the archive lets go of is deleted like anything else`() = runTest {
        coEvery { expenseDao.archivedBefore(any()) } returns listOf(7L, 9L)
        coEvery { expenseDao.getWithDetailsById(any()) } returns null

        assertEquals(2, repository.purgeArchivedBefore(1_000L))

        coVerify(exactly = 1) { expenseDao.deleteById(7L) }
        coVerify(exactly = 1) { expenseDao.deleteById(9L) }
    }

    @Test
    fun `an archive with nothing past its time deletes nothing`() = runTest {
        coEvery { expenseDao.archivedBefore(any()) } returns emptyList()

        assertEquals(0, repository.purgeArchivedBefore(1_000L))

        coVerify(exactly = 0) { expenseDao.deleteById(any()) }
    }
}
