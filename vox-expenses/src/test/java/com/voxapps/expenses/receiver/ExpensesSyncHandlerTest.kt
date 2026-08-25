package com.voxapps.expenses.receiver

import com.voxapps.expenses.data.Category
import com.voxapps.expenses.data.Expense
import com.voxapps.expenses.data.ExpenseLineItem
import com.voxapps.expenses.data.ExpenseTombstone
import com.voxapps.expenses.data.ExpenseWithDetails
import com.voxapps.expenses.data.ExpensesRepository
import com.voxapps.expenses.data.preferences.ExpensesSettings
import com.voxapps.expenses.data.preferences.ExpensesSettingsRepository
import com.voxapps.expenses.state.SessionManager
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ExpensesSyncHandlerTest {

    private lateinit var settingsRepo: ExpensesSettingsRepository
    private lateinit var sessionManager: SessionManager
    private lateinit var expensesRepo: ExpensesRepository
    private lateinit var handler: ExpensesSyncHandler

    @Before
    fun setup() {
        settingsRepo = mockk()
        sessionManager = mockk()
        expensesRepo = mockk()
        handler = ExpensesSyncHandler(settingsRepo, sessionManager, expensesRepo, "The expenses are locked. Unlock the app.")

        every { settingsRepo.getSnapshot() } returns ExpensesSettings(isBiometricRequired = false)
        every { expensesRepo.categories } returns flowOf(emptyList())
        every { expensesRepo.allWithDetails } returns flowOf(emptyList())
        coEvery { expensesRepo.allExpensesSnapshot() } returns emptyList()
        coEvery { expensesRepo.tombstonesSince(any()) } returns emptyList()
        coEvery { expensesRepo.mostRecentCategoryColor() } returns null
    }

    private fun expense(uid: String, updatedAt: Long, totalAmount: Double = 10.0, categoryId: Long? = null) = Expense(
        uid = uid,
        totalAmount = totalAmount,
        currencyCode = "RON",
        dateTime = 0L,
        createdAt = updatedAt,
        updatedAt = updatedAt,
        categoryId = categoryId
    )

    private fun withDetails(expense: Expense, category: Category? = null, items: List<ExpenseLineItem> = emptyList()) =
        ExpenseWithDetails(expense = expense, category = category, items = items)

    // --- export ---

    @Test
    fun `export only includes entries updated after since`() = runTest {
        every { expensesRepo.allWithDetails } returns flowOf(listOf(
            withDetails(expense(uid = "old", updatedAt = 100L)),
            withDetails(expense(uid = "new", updatedAt = 2000L))
        ))

        val result = handler.export(since = 1000L, scopeNames = null)
        val uids = JSONObject(result.text).getJSONArray("entries").let { arr ->
            (0 until arr.length()).map { arr.getJSONObject(it).getString("uid") }
        }

        assertEquals(listOf("new"), uids)
    }

    @Test
    fun `export restricts to scopeNames when provided, matching category name case-insensitively`() = runTest {
        val groceries = Category(id = 1, name = "Groceries", colorArgb = 0, position = 0, createdAt = 0)
        every { expensesRepo.allWithDetails } returns flowOf(listOf(
            withDetails(expense(uid = "in-scope", updatedAt = 100L, categoryId = 1), category = groceries),
            withDetails(expense(uid = "uncategorized", updatedAt = 100L, categoryId = null))
        ))

        val result = handler.export(since = 0L, scopeNames = listOf("groceries"))
        val uids = JSONObject(result.text).getJSONArray("entries").let { arr ->
            (0 until arr.length()).map { arr.getJSONObject(it).getString("uid") }
        }

        assertEquals(listOf("in-scope"), uids)
    }

    @Test
    fun `export embeds each expense's line items, ordered by position`() = runTest {
        val items = listOf(
            ExpenseLineItem(expenseId = 1, name = "Bread", quantity = 1.0, unitPrice = 5.0, position = 1),
            ExpenseLineItem(expenseId = 1, name = "Milk", quantity = 2.0, unitPrice = 3.0, position = 0)
        )
        every { expensesRepo.allWithDetails } returns flowOf(listOf(
            withDetails(expense(uid = "a", updatedAt = 100L), items = items)
        ))

        val result = handler.export(since = 0L, scopeNames = null)
        val lineItems = JSONObject(result.text).getJSONArray("entries").getJSONObject(0).getJSONArray("lineItems")

        assertEquals(2, lineItems.length())
        assertEquals("Milk", lineItems.getJSONObject(0).getString("name"))
        assertEquals("Bread", lineItems.getJSONObject(1).getString("name"))
    }

    @Test
    fun `export includes tombstones since the watermark`() = runTest {
        coEvery { expensesRepo.tombstonesSince(1000L) } returns listOf(ExpenseTombstone("deleted-uid", 1500L))

        val result = handler.export(since = 1000L, scopeNames = null)
        val tombstone = JSONObject(result.text).getJSONArray("tombstones").getJSONObject(0)

        assertEquals("deleted-uid", tombstone.getString("uid"))
        assertEquals(1500L, tombstone.getLong("deletedAt"))
    }

    @Test
    fun `export while locked returns a failure without touching the repository`() = runTest {
        every { settingsRepo.getSnapshot() } returns ExpensesSettings(isBiometricRequired = true)
        every { sessionManager.isSessionValid(any()) } returns false

        val result = handler.export(since = 0L, scopeNames = null)

        assertFalse(result.ok)
        coVerify(exactly = 0) { expensesRepo.allExpensesSnapshot() }
    }

    // --- merge ---

    @Test
    fun `merge inserts a remote uid not present locally`() = runTest {
        coEvery { expensesRepo.insertSyncedExpense(any(), any()) } returns 1L

        val payload = """{"entries":[{"uid":"a","totalAmount":5.0,"currencyCode":"RON","dateTime":0,"createdAt":100,"updatedAt":100}],"tombstones":[]}"""
        handler.merge(payload)

        coVerify(exactly = 1) { expensesRepo.insertSyncedExpense(match { it.uid == "a" && it.totalAmount == 5.0 }, any()) }
    }

    @Test
    fun `merge inserts a remote entry's line items alongside it`() = runTest {
        coEvery { expensesRepo.insertSyncedExpense(any(), any()) } returns 1L

        val payload = """{"entries":[{"uid":"a","totalAmount":8.0,"currencyCode":"RON","dateTime":0,"createdAt":100,"updatedAt":100,
            "lineItems":[{"name":"Bread","quantity":1.0,"unitPrice":5.0,"position":0},{"name":"Milk","quantity":1.0,"unitPrice":3.0,"position":1}]}],"tombstones":[]}"""
        handler.merge(payload)

        coVerify(exactly = 1) {
            expensesRepo.insertSyncedExpense(
                match { it.uid == "a" },
                match { items -> items.map { it.name } == listOf("Bread", "Milk") }
            )
        }
    }

    @Test
    fun `merge updates when the remote updatedAt is newer, preserving the local id`() = runTest {
        coEvery { expensesRepo.allExpensesSnapshot() } returns listOf(expense(uid = "a", updatedAt = 100L).copy(id = 42))
        coEvery { expensesRepo.getIdByUid("a") } returns 42L
        coEvery { expensesRepo.updateSyncedExpense(any(), any()) } just Runs

        val payload = """{"entries":[{"uid":"a","totalAmount":99.0,"currencyCode":"RON","dateTime":0,"createdAt":100,"updatedAt":200}],"tombstones":[]}"""
        handler.merge(payload)

        coVerify(exactly = 1) { expensesRepo.updateSyncedExpense(match { it.id == 42L && it.uid == "a" && it.totalAmount == 99.0 }, any()) }
        coVerify(exactly = 0) { expensesRepo.insertSyncedExpense(any(), any()) }
    }

    @Test
    fun `merge replaces an updated expense's line items with whatever the remote sent, including empty`() = runTest {
        coEvery { expensesRepo.allExpensesSnapshot() } returns listOf(expense(uid = "a", updatedAt = 100L).copy(id = 42))
        coEvery { expensesRepo.getIdByUid("a") } returns 42L
        coEvery { expensesRepo.updateSyncedExpense(any(), any()) } just Runs

        // Remote's current state for this expense has no line items — local must end up with none too,
        // not "leave whatever was there" (line items travel with the whole expense, see design doc).
        val payload = """{"entries":[{"uid":"a","totalAmount":99.0,"currencyCode":"RON","dateTime":0,"createdAt":100,"updatedAt":200,"lineItems":[]}],"tombstones":[]}"""
        handler.merge(payload)

        coVerify(exactly = 1) { expensesRepo.updateSyncedExpense(match { it.uid == "a" }, match { it.isEmpty() }) }
    }

    @Test
    fun `merge ignores a remote entry that is not newer than the local one`() = runTest {
        coEvery { expensesRepo.allExpensesSnapshot() } returns listOf(expense(uid = "a", updatedAt = 500L))

        val payload = """{"entries":[{"uid":"a","totalAmount":99.0,"currencyCode":"RON","dateTime":0,"createdAt":100,"updatedAt":100}],"tombstones":[]}"""
        handler.merge(payload)

        coVerify(exactly = 0) { expensesRepo.insertSyncedExpense(any(), any()) }
        coVerify(exactly = 0) { expensesRepo.updateSyncedExpense(any(), any()) }
    }

    @Test
    fun `merge deletes a locally-present uid named in a tombstone`() = runTest {
        coEvery { expensesRepo.allExpensesSnapshot() } returns listOf(expense(uid = "a", updatedAt = 100L))
        coEvery { expensesRepo.deleteExpenseByUid("a") } just Runs

        val payload = """{"entries":[],"tombstones":[{"uid":"a","deletedAt":9999}]}"""
        handler.merge(payload)

        coVerify(exactly = 1) { expensesRepo.deleteExpenseByUid("a") }
    }

    @Test
    fun `merge resolves an unknown category name by auto-creating it, matching import's convention`() = runTest {
        every { expensesRepo.categories } returns flowOf(emptyList())
        coEvery { expensesRepo.addCategory(any(), any(), any(), any()) } returns 7L
        coEvery { expensesRepo.insertSyncedExpense(any(), any()) } returns 1L

        val payload = """{"entries":[{"uid":"a","totalAmount":5.0,"currencyCode":"RON","dateTime":0,"createdAt":100,"updatedAt":100,"categoryName":"Travel"}],"tombstones":[]}"""
        handler.merge(payload)

        coVerify(exactly = 1) { expensesRepo.addCategory("Travel", any(), any(), any()) }
        coVerify(exactly = 1) { expensesRepo.insertSyncedExpense(match { it.categoryId == 7L }, any()) }
    }

    @Test
    fun `malformed merge payload returns a failure without touching the repository`() = runTest {
        val result = handler.merge("{ not json")

        assertFalse(result.ok)
        coVerify(exactly = 0) { expensesRepo.allExpensesSnapshot() }
    }

    @Test
    fun `merge while locked returns a failure without touching the repository`() = runTest {
        every { settingsRepo.getSnapshot() } returns ExpensesSettings(isBiometricRequired = true)
        every { sessionManager.isSessionValid(any()) } returns false

        val result = handler.merge("""{"entries":[],"tombstones":[]}""")

        assertFalse(result.ok)
        coVerify(exactly = 0) { expensesRepo.allExpensesSnapshot() }
    }
}
