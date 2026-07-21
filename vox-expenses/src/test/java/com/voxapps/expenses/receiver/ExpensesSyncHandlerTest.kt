package com.voxapps.expenses.receiver

import com.voxapps.expenses.data.Category
import com.voxapps.expenses.data.Expense
import com.voxapps.expenses.data.ExpenseTombstone
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
        handler = ExpensesSyncHandler(settingsRepo, sessionManager, expensesRepo)

        every { settingsRepo.getSnapshot() } returns ExpensesSettings(isBiometricRequired = false)
        every { expensesRepo.categories } returns flowOf(emptyList())
        coEvery { expensesRepo.expensesSnapshot() } returns emptyList()
        coEvery { expensesRepo.tombstonesSince(any()) } returns emptyList()
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

    // --- export ---

    @Test
    fun `export only includes entries updated after since`() = runTest {
        coEvery { expensesRepo.expensesSnapshot() } returns listOf(
            expense(uid = "old", updatedAt = 100L),
            expense(uid = "new", updatedAt = 2000L)
        )

        val result = handler.export(since = 1000L, scopeNames = null)
        val uids = JSONObject(result.text).getJSONArray("entries").let { arr ->
            (0 until arr.length()).map { arr.getJSONObject(it).getString("uid") }
        }

        assertEquals(listOf("new"), uids)
    }

    @Test
    fun `export restricts to scopeNames when provided, matching category name case-insensitively`() = runTest {
        every { expensesRepo.categories } returns flowOf(
            listOf(Category(id = 1, name = "Groceries", colorArgb = 0, position = 0, createdAt = 0))
        )
        coEvery { expensesRepo.expensesSnapshot() } returns listOf(
            expense(uid = "in-scope", updatedAt = 100L, categoryId = 1),
            expense(uid = "uncategorized", updatedAt = 100L, categoryId = null)
        )

        val result = handler.export(since = 0L, scopeNames = listOf("groceries"))
        val uids = JSONObject(result.text).getJSONArray("entries").let { arr ->
            (0 until arr.length()).map { arr.getJSONObject(it).getString("uid") }
        }

        assertEquals(listOf("in-scope"), uids)
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
        coVerify(exactly = 0) { expensesRepo.expensesSnapshot() }
    }

    // --- merge ---

    @Test
    fun `merge inserts a remote uid not present locally`() = runTest {
        coEvery { expensesRepo.insertSyncedExpense(any()) } returns 1L

        val payload = """{"entries":[{"uid":"a","totalAmount":5.0,"currencyCode":"RON","dateTime":0,"createdAt":100,"updatedAt":100}],"tombstones":[]}"""
        handler.merge(payload)

        coVerify(exactly = 1) { expensesRepo.insertSyncedExpense(match { it.uid == "a" && it.totalAmount == 5.0 }) }
    }

    @Test
    fun `merge updates when the remote updatedAt is newer, preserving the local id`() = runTest {
        coEvery { expensesRepo.expensesSnapshot() } returns listOf(expense(uid = "a", updatedAt = 100L).copy(id = 42))
        coEvery { expensesRepo.getIdByUid("a") } returns 42L
        coEvery { expensesRepo.updateSyncedExpense(any()) } just Runs

        val payload = """{"entries":[{"uid":"a","totalAmount":99.0,"currencyCode":"RON","dateTime":0,"createdAt":100,"updatedAt":200}],"tombstones":[]}"""
        handler.merge(payload)

        coVerify(exactly = 1) { expensesRepo.updateSyncedExpense(match { it.id == 42L && it.uid == "a" && it.totalAmount == 99.0 }) }
        coVerify(exactly = 0) { expensesRepo.insertSyncedExpense(any()) }
    }

    @Test
    fun `merge ignores a remote entry that is not newer than the local one`() = runTest {
        coEvery { expensesRepo.expensesSnapshot() } returns listOf(expense(uid = "a", updatedAt = 500L))

        val payload = """{"entries":[{"uid":"a","totalAmount":99.0,"currencyCode":"RON","dateTime":0,"createdAt":100,"updatedAt":100}],"tombstones":[]}"""
        handler.merge(payload)

        coVerify(exactly = 0) { expensesRepo.insertSyncedExpense(any()) }
        coVerify(exactly = 0) { expensesRepo.updateSyncedExpense(any()) }
    }

    @Test
    fun `merge deletes a locally-present uid named in a tombstone`() = runTest {
        coEvery { expensesRepo.expensesSnapshot() } returns listOf(expense(uid = "a", updatedAt = 100L))
        coEvery { expensesRepo.deleteExpenseByUid("a") } just Runs

        val payload = """{"entries":[],"tombstones":[{"uid":"a","deletedAt":9999}]}"""
        handler.merge(payload)

        coVerify(exactly = 1) { expensesRepo.deleteExpenseByUid("a") }
    }

    @Test
    fun `merge resolves an unknown category name by auto-creating it, matching import's convention`() = runTest {
        every { expensesRepo.categories } returns flowOf(emptyList())
        coEvery { expensesRepo.addCategory(any(), any(), any(), any()) } returns 7L
        coEvery { expensesRepo.insertSyncedExpense(any()) } returns 1L

        val payload = """{"entries":[{"uid":"a","totalAmount":5.0,"currencyCode":"RON","dateTime":0,"createdAt":100,"updatedAt":100,"categoryName":"Travel"}],"tombstones":[]}"""
        handler.merge(payload)

        coVerify(exactly = 1) { expensesRepo.addCategory("Travel", any(), any(), any()) }
        coVerify(exactly = 1) { expensesRepo.insertSyncedExpense(match { it.categoryId == 7L }) }
    }

    @Test
    fun `malformed merge payload returns a failure without touching the repository`() = runTest {
        val result = handler.merge("{ not json")

        assertFalse(result.ok)
        coVerify(exactly = 0) { expensesRepo.expensesSnapshot() }
    }

    @Test
    fun `merge while locked returns a failure without touching the repository`() = runTest {
        every { settingsRepo.getSnapshot() } returns ExpensesSettings(isBiometricRequired = true)
        every { sessionManager.isSessionValid(any()) } returns false

        val result = handler.merge("""{"entries":[],"tombstones":[]}""")

        assertFalse(result.ok)
        coVerify(exactly = 0) { expensesRepo.expensesSnapshot() }
    }
}
