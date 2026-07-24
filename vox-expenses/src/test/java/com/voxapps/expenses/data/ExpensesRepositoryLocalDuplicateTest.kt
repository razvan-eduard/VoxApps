package com.voxapps.expenses.data

import com.voxapps.expenses.domain.llm.DuplicateGroup
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ExpensesRepositoryLocalDuplicateTest {

    private lateinit var expenseDao: ExpenseDao
    private lateinit var repository: ExpensesRepository

    private fun stubAll(expenses: List<Expense>) {
        coEvery { expenseDao.observeAll() } returns flowOf(expenses)
    }

    @Before
    fun setup() {
        expenseDao = mockk(relaxed = true)
        repository = ExpensesRepository(
            expenseDao, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
            mockk(relaxed = true), mockk(relaxed = true)
        )
    }

    @Test
    fun `findLocalDuplicateGroups groups exact-title matches within the time window`() = runTest {
        stubAll(
            listOf(
                Expense(id = 1, title = "Easybox Pleasa", totalAmount = 134.0, currencyCode = "RON", dateTime = 1_000L, createdAt = 1),
                Expense(id = 2, title = "EASYBOX PLEASA", totalAmount = 134.0, currencyCode = "RON", dateTime = 1_500L, createdAt = 2)
            )
        )

        val groups = repository.findLocalDuplicateGroups(fuzzyMatch = false, timeWindowMillis = 60_000)

        assertEquals(listOf(DuplicateGroup(keepId = 1, duplicateIds = listOf(2))), groups)
    }

    @Test
    fun `findLocalDuplicateGroups keeps the earliest-created row as keepId`() = runTest {
        stubAll(
            listOf(
                Expense(id = 5, title = "Patreon", totalAmount = 60.03, currencyCode = "RON", dateTime = 2_000L, createdAt = 200),
                Expense(id = 3, title = "Patreon", totalAmount = 60.03, currencyCode = "RON", dateTime = 1_000L, createdAt = 100)
            )
        )

        val groups = repository.findLocalDuplicateGroups(fuzzyMatch = false, timeWindowMillis = 60_000)

        assertEquals(listOf(DuplicateGroup(keepId = 3, duplicateIds = listOf(5))), groups)
    }

    @Test
    fun `findLocalDuplicateGroups never re-groups an already-consumed row`() = runTest {
        // Three near-identical rows within the window — must produce ONE group of size 3, not two
        // overlapping groups sharing a member.
        stubAll(
            listOf(
                Expense(id = 1, title = "Coffee", totalAmount = 15.0, currencyCode = "RON", dateTime = 1_000L, createdAt = 1),
                Expense(id = 2, title = "Coffee", totalAmount = 15.0, currencyCode = "RON", dateTime = 1_100L, createdAt = 2),
                Expense(id = 3, title = "Coffee", totalAmount = 15.0, currencyCode = "RON", dateTime = 1_200L, createdAt = 3)
            )
        )

        val groups = repository.findLocalDuplicateGroups(fuzzyMatch = false, timeWindowMillis = 60_000)

        assertEquals(1, groups.size)
        assertEquals(1, groups.first().keepId)
        assertEquals(setOf(2L, 3L), groups.first().duplicateIds.toSet())
    }

    @Test
    fun `findLocalDuplicateGroups finds nothing when amounts differ`() = runTest {
        stubAll(
            listOf(
                Expense(id = 1, title = "Coffee", totalAmount = 15.0, currencyCode = "RON", dateTime = 1_000L, createdAt = 1),
                Expense(id = 2, title = "Coffee", totalAmount = 20.0, currencyCode = "RON", dateTime = 1_100L, createdAt = 2)
            )
        )

        val groups = repository.findLocalDuplicateGroups(fuzzyMatch = false, timeWindowMillis = 60_000)

        assertTrue(groups.isEmpty())
    }

    @Test
    fun `duplicateCandidateClusters groups by amount currency and direction, ignoring time`() = runTest {
        stubAll(
            listOf(
                Expense(id = 1, title = "Patreon", totalAmount = 313.22, currencyCode = "RON", dateTime = 1_000L, direction = TransactionDirection.OUTGOING),
                Expense(id = 2, title = "Patreon", totalAmount = 313.22, currencyCode = "RON", dateTime = 999_999_999L, direction = TransactionDirection.OUTGOING),
                Expense(id = 3, title = "Rent", totalAmount = 900.0, currencyCode = "RON", dateTime = 1_000L, direction = TransactionDirection.OUTGOING)
            )
        )

        val clusters = repository.duplicateCandidateClusters()

        assertEquals(1, clusters.size)
        assertEquals(setOf(1L, 2L), clusters.first().map { it.id }.toSet())
    }

    @Test
    fun `duplicateCandidateClusters does not cluster a row with no same-amount peer`() = runTest {
        stubAll(
            listOf(
                Expense(id = 1, title = "Rent", totalAmount = 900.0, currencyCode = "RON", dateTime = 1_000L)
            )
        )

        assertTrue(repository.duplicateCandidateClusters().isEmpty())
    }

    @Test
    fun `duplicateCandidateClusters scoped to an id only returns that id's own cluster`() = runTest {
        stubAll(
            listOf(
                Expense(id = 1, title = "Patreon", totalAmount = 313.22, currencyCode = "RON", dateTime = 1_000L),
                Expense(id = 2, title = "Patreon", totalAmount = 313.22, currencyCode = "RON", dateTime = 2_000L),
                Expense(id = 3, title = "Coffee", totalAmount = 15.0, currencyCode = "RON", dateTime = 1_000L),
                Expense(id = 4, title = "Coffee", totalAmount = 15.0, currencyCode = "RON", dateTime = 2_000L)
            )
        )

        val clusters = repository.duplicateCandidateClusters(scopedToId = 1)

        assertEquals(1, clusters.size)
        assertEquals(setOf(1L, 2L), clusters.first().map { it.id }.toSet())
    }

    @Test
    fun `duplicateCandidateClusters scoped to an unclustered id returns nothing`() = runTest {
        stubAll(
            listOf(
                Expense(id = 1, title = "Rent", totalAmount = 900.0, currencyCode = "RON", dateTime = 1_000L)
            )
        )

        assertTrue(repository.duplicateCandidateClusters(scopedToId = 1).isEmpty())
    }
}
