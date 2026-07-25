package com.voxapps.expenses.data

import android.content.Context
import com.voxapps.datahygiene.RuleCombinator
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class ExpensesRepositoryDuplicateCheckTest {

    private lateinit var expenseDao: ExpenseDao
    private lateinit var categoryDao: CategoryDao
    private lateinit var lineItemDao: ExpenseLineItemDao
    private lateinit var spendingLimitDao: SpendingLimitDao
    private lateinit var duplicateRuleDao: DuplicateRuleDao
    private lateinit var repository: ExpensesRepository

    private fun rule(fieldIds: List<String>, combinator: RuleCombinator = RuleCombinator.AND) =
        DuplicateRuleEntity(id = 1, name = "test rule", fieldIds = fieldIds, combinator = combinator.name)

    @Before
    fun setup() {
        expenseDao = mockk(relaxed = true)
        categoryDao = mockk(relaxed = true)
        lineItemDao = mockk(relaxed = true)
        spendingLimitDao = mockk(relaxed = true)
        duplicateRuleDao = mockk(relaxed = true)
        every { duplicateRuleDao.observeAll() } returns flowOf(emptyList())
        repository = ExpensesRepository(
            expenseDao, categoryDao, lineItemDao, spendingLimitDao, mockk(relaxed = true), mockk<Context>(), mockk(relaxed = true), duplicateRuleDao
        )
    }

    @Test
    fun `addExpense inserts normally when duplicate checking is off by default`() = runTest {
        val existing = Expense(id = 1, title = "Groceries", totalAmount = 42.0, currencyCode = "RON", vendor = "Carrefour", dateTime = 1000L)
        coEvery { expenseDao.getForDateRange(any(), any()) } returns listOf(existing)
        coEvery { expenseDao.insert(any()) } returns 7L

        val result = repository.addExpense(
            title = "Groceries", totalAmount = 42.0, currencyCode = "RON", vendor = "Carrefour",
            bank = null, location = null, dateTime = 1000L, comments = null, categoryId = null
        )

        // nearDuplicateCheckEnabled defaults to false — no rule engine query happens at all.
        assertEquals(7L, result)
        coVerify(exactly = 1) { expenseDao.insert(any()) }
    }

    @Test
    fun `an enabled rule matching every relevant field returns DUPLICATE_ENTRY_RESULT, not a merge`() = runTest {
        val existing = Expense(id = 1, title = "Groceries", totalAmount = 42.0, currencyCode = "RON", vendor = "Carrefour", bank = "Some Bank", dateTime = 1000L)
        coEvery { expenseDao.getForDateRange(any(), any()) } returns listOf(existing)
        every { duplicateRuleDao.observeAll() } returns flowOf(listOf(rule(listOf("title", "totalAmount", "vendor", "bank"))))

        val result = repository.addExpense(
            title = "Groceries", totalAmount = 42.0, currencyCode = "RON", vendor = "Carrefour",
            bank = "Some Bank", location = null, dateTime = 1000L, comments = null, categoryId = null,
            nearDuplicateCheckEnabled = true,
            nearDuplicateConfig = NearDuplicateConfig(timeWindowMillis = TimeUnit.MINUTES.toMillis(2))
        )

        // Every field the candidate carries is already present on the existing row, so enrichment
        // changes nothing — a genuine exact duplicate, not a "gained new data" merge.
        assertEquals(DUPLICATE_ENTRY_RESULT, result)
        coVerify(exactly = 0) { expenseDao.insert(any()) }
        coVerify(exactly = 0) { expenseDao.update(any()) }
    }

    @Test
    fun `checkForDuplicate = false bypasses the check entirely (Hub import path)`() = runTest {
        val existing = Expense(id = 1, title = "Groceries", totalAmount = 42.0, currencyCode = "RON", vendor = "Carrefour", dateTime = 1000L)
        coEvery { expenseDao.getForDateRange(any(), any()) } returns listOf(existing)
        coEvery { expenseDao.insert(any()) } returns 9L
        every { duplicateRuleDao.observeAll() } returns flowOf(listOf(rule(listOf("title", "totalAmount"))))

        val result = repository.addExpense(
            title = "Groceries", totalAmount = 42.0, currencyCode = "RON", vendor = "Carrefour",
            bank = null, location = null, dateTime = 1000L, comments = null, categoryId = null,
            checkForDuplicate = false,
            nearDuplicateCheckEnabled = true
        )

        assertEquals(9L, result)
        coVerify(exactly = 1) { expenseDao.insert(any()) }
    }

    // --- near-duplicate detection (off by default; requires an enabled rule) ---

    @Test
    fun `near-duplicate detection stays off by default even with a fuzzy match nearby in time`() = runTest {
        val nearby = Expense(id = 1, title = "Example Store", totalAmount = 99.0, currencyCode = "RON", dateTime = 1000L)
        coEvery { expenseDao.getForDateRange(any(), any()) } returns listOf(nearby)
        coEvery { expenseDao.insert(any()) } returns 5L

        val result = repository.addExpense(
            title = "Payment to Example Store", totalAmount = 99.0, currencyCode = "RON", vendor = null,
            bank = null, location = null, dateTime = 1050L, comments = null, categoryId = null
        )

        assertEquals(5L, result)
        coVerify(exactly = 1) { expenseDao.insert(any()) }
        coVerify(exactly = 0) { expenseDao.update(any()) }
    }

    @Test
    fun `near-duplicate detection enabled merges a fuzzy title match within the time window instead of inserting`() = runTest {
        val nearby = Expense(id = 1, title = "Example Store", totalAmount = 99.0, currencyCode = "RON", dateTime = 1000L)
        coEvery { expenseDao.getForDateRange(any(), any()) } returns listOf(nearby)
        coEvery { expenseDao.update(any()) } just Runs
        every { duplicateRuleDao.observeAll() } returns flowOf(listOf(rule(listOf("totalAmount", "title"))))

        val result = repository.addExpense(
            title = "Payment to Example Store", totalAmount = 99.0, currencyCode = "RON", vendor = null,
            bank = "Some Bank", location = null, dateTime = 1050L, comments = null, categoryId = null,
            nearDuplicateCheckEnabled = true,
            nearDuplicateConfig = NearDuplicateConfig(timeWindowMillis = TimeUnit.MINUTES.toMillis(2))
        )

        assertEquals(NEAR_DUPLICATE_MERGED_RESULT, result)
        coVerify(exactly = 1) { expenseDao.update(match { it.id == 1L && it.bank == "Some Bank" }) }
        coVerify(exactly = 0) { expenseDao.insert(any()) }
    }

    @Test
    fun `near-duplicate detection enabled but outside the time window inserts normally`() = runTest {
        coEvery { expenseDao.getForDateRange(any(), any()) } returns emptyList()
        coEvery { expenseDao.insert(any()) } returns 5L
        every { duplicateRuleDao.observeAll() } returns flowOf(listOf(rule(listOf("totalAmount", "title"))))

        val result = repository.addExpense(
            title = "Example Store", totalAmount = 99.0, currencyCode = "RON", vendor = null,
            bank = null, location = null, dateTime = 1000L + TimeUnit.HOURS.toMillis(1), comments = null, categoryId = null,
            nearDuplicateCheckEnabled = true,
            nearDuplicateConfig = NearDuplicateConfig(timeWindowMillis = TimeUnit.MINUTES.toMillis(2))
        )

        assertEquals(5L, result)
        coVerify(exactly = 1) { expenseDao.insert(any()) }
        coVerify(exactly = 0) { expenseDao.update(any()) }
    }

    @Test
    fun `no enabled rules means nothing ever matches, even with an identical candidate nearby`() = runTest {
        val existing = Expense(id = 1, title = "Groceries", totalAmount = 42.0, currencyCode = "RON", vendor = "Carrefour", dateTime = 1000L)
        coEvery { expenseDao.getForDateRange(any(), any()) } returns listOf(existing)
        coEvery { expenseDao.insert(any()) } returns 11L
        // setup()'s default: duplicateRuleDao.observeAll() returns an empty rule list.

        val result = repository.addExpense(
            title = "Groceries", totalAmount = 42.0, currencyCode = "RON", vendor = "Carrefour",
            bank = null, location = null, dateTime = 1000L, comments = null, categoryId = null,
            nearDuplicateCheckEnabled = true
        )

        assertEquals(11L, result)
        coVerify(exactly = 1) { expenseDao.insert(any()) }
    }
}
