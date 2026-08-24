package com.voxapps.expenses.data

import android.content.Context
import com.voxapps.datahygiene.RemapCondition
import com.voxapps.datahygiene.RemapOp
import com.voxapps.expenses.domain.rules.RuleAlert
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
    private lateinit var alerted: MutableList<List<RuleAlert>>

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
        alerted = mutableListOf()
        repository = ExpensesRepository(
            expenseDao, categoryDao, lineItemDao, spendingLimitDao, remapRuleDao, mockk(relaxed = true), mockk<Context>(), mockk(relaxed = true), mockk(relaxed = true),
            mockk(relaxed = true), mockk(relaxed = true), null,
            onRuleAlerts = { alerted += it }
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

    // --- a consequence that is not a field ---

    private fun overRule(cents: String, sets: Map<String, String> = emptyMap()) = RemapRuleEntity(
        id = 7L, name = "Big payment",
        matchJson = RemapConditionsJson.encode(
            listOf(listOf(RemapCondition("totalAmount", cents, op = RemapOp.GT)))
        ),
        setJson = RemapRuleJson.encode(sets),
        origin = RemapRuleEntity.ORIGIN_USER, updatedAt = 0L, alertEnabled = true
    )

    @Test
    fun `a payment over the figure alerts, and names the record it was about`() = runTest {
        every { categoryDao.observeAll() } returns flowOf(listOf(shopping))
        coEvery { categoryDao.getAll() } returns listOf(shopping)
        coEvery { remapRuleDao.getAll() } returns listOf(overRule("50000"))

        repository.addParsedExpense(
            title = null, totalAmount = 763.0, currencyCode = "RON", vendor = "Emag", bank = null,
            location = null, comments = null, dateTime = 1000L,
            spokenCategory = null, defaultCategoryId = null, autoCreate = false
        )

        assertEquals(1, alerted.size)
        val alert = alerted.single().single()
        assertEquals("Big payment", alert.ruleName)
        assertEquals(99L, alert.expenseId)
        assertEquals("Emag", alert.vendor)
        assertEquals(763.0, alert.amount, 0.001)
    }

    @Test
    fun `a payment under the figure alerts nobody`() = runTest {
        every { categoryDao.observeAll() } returns flowOf(listOf(shopping))
        coEvery { categoryDao.getAll() } returns listOf(shopping)
        coEvery { remapRuleDao.getAll() } returns listOf(overRule("50000"))

        repository.addParsedExpense(
            title = null, totalAmount = 12.0, currencyCode = "RON", vendor = "Lidl", bank = null,
            location = null, comments = null, dateTime = 1000L,
            spokenCategory = null, defaultCategoryId = null, autoCreate = false
        )

        assertEquals(emptyList<List<RuleAlert>>(), alerted)
    }

    /** Both consequences at once: the record is rewritten AND the alert goes out. */
    @Test
    fun `a rule may rewrite the record and alert about it`() = runTest {
        every { categoryDao.observeAll() } returns flowOf(listOf(shopping, groceries))
        coEvery { categoryDao.getAll() } returns listOf(shopping, groceries)
        coEvery { remapRuleDao.getAll() } returns
            listOf(overRule("50000", mapOf("categoryId" to groceries.id.toString())))

        repository.addParsedExpense(
            title = null, totalAmount = 763.0, currencyCode = "RON", vendor = "Emag", bank = null,
            location = null, comments = null, dateTime = 1000L,
            spokenCategory = "Shopping", defaultCategoryId = null, autoCreate = false
        )

        coVerify(exactly = 1) { expenseDao.insert(match { it.categoryId == groceries.id }) }
        assertEquals(1, alerted.size)
    }

    @Test
    fun `a disabled rule alerts about nothing`() = runTest {
        every { categoryDao.observeAll() } returns flowOf(listOf(shopping))
        coEvery { categoryDao.getAll() } returns listOf(shopping)
        coEvery { remapRuleDao.getAll() } returns listOf(overRule("50000").copy(enabled = false))

        repository.addParsedExpense(
            title = null, totalAmount = 763.0, currencyCode = "RON", vendor = "Emag", bank = null,
            location = null, comments = null, dateTime = 1000L,
            spokenCategory = null, defaultCategoryId = null, autoCreate = false
        )

        assertEquals(emptyList<List<RuleAlert>>(), alerted)
    }
}
