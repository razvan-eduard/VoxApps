package com.voxapps.expenses.receiver

import com.voxapps.datahygiene.SyncDeltaKeys
import com.voxapps.expenses.data.BankAccount
import com.voxapps.expenses.data.Category
import com.voxapps.expenses.data.Expense
import com.voxapps.expenses.data.ExpenseLineItem
import com.voxapps.expenses.data.ExpenseTombstone
import com.voxapps.expenses.data.ExpenseWithDetails
import com.voxapps.expenses.data.ExpensesRepository
import com.voxapps.expenses.data.preferences.ExpensesSettings
import com.voxapps.expenses.data.preferences.ExpensesSettingsRepository
import com.voxapps.expenses.state.SessionManager
import com.voxapps.datahygiene.SyncLevel
import com.voxapps.ipc.VoxCommand
import com.voxapps.ipc.VoxIpc
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

        // ALL unless a test says otherwise: the level gate is exercised by its own tests, and every
        // other test here is about what the wire carries, not about whether it is volunteered.
        every { settingsRepo.getSnapshot() } returns
            ExpensesSettings(isBiometricRequired = false, syncLevel = SyncLevel.ALL.name)
        every { expensesRepo.categories } returns flowOf(emptyList())
        every { expensesRepo.allWithDetails } returns flowOf(emptyList())
        coEvery { expensesRepo.allExpensesSnapshot() } returns emptyList()
        // The bank a delta names is read from the accounts, so the double has to have some.
        coEvery { expensesRepo.bankAccountsSnapshot() } returns emptyList()
        // The counterparty a delta names travels by name too, so the double has to answer for it.
        coEvery { expensesRepo.recipientsSnapshot() } returns emptyList()
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

    private fun exportCommand(
        since: Long = 0L,
        scopeNames: List<String>? = null,
        uids: List<String>? = null,
        cursor: String? = null,
        limit: Int? = null
    ) = VoxCommand(
        op = VoxIpc.OP_SYNC_EXPORT, since = since, scopeNames = scopeNames,
        uids = uids, cursor = cursor, limit = limit
    )

    private fun mergeCommand(
        payload: String,
        sourceDeviceId: String? = null,
        sourceDeviceName: String? = null
    ) = VoxCommand(
        op = VoxIpc.OP_SYNC_MERGE, text = payload,
        sourceDeviceId = sourceDeviceId, sourceDeviceName = sourceDeviceName
    )

    private fun exportedUids(text: String): List<String> =
        JSONObject(text).getJSONArray("entries").let { arr ->
            (0 until arr.length()).map { arr.getJSONObject(it).getString("uid") }
        }

    // --- export ---

    @Test
    fun `export only includes entries updated after since`() = runTest {
        every { expensesRepo.allWithDetails } returns flowOf(listOf(
            withDetails(expense(uid = "old", updatedAt = 100L)),
            withDetails(expense(uid = "new", updatedAt = 2000L))
        ))

        val result = handler.export(exportCommand(since = 1000L))
        val uids = JSONObject(result.text).getJSONArray("entries").let { arr ->
            (0 until arr.length()).map { arr.getJSONObject(it).getString("uid") }
        }

        assertEquals(listOf("new"), uids)
    }

    @Test
    fun `at the shared level, scope names match bank account names case-insensitively`() = runTest {
        every { settingsRepo.getSnapshot() } returns
            ExpensesSettings(isBiometricRequired = false, syncLevel = SyncLevel.SHARED.name)
        coEvery { expensesRepo.bankAccountsSnapshot() } returns listOf(
            BankAccount(id = 1, currencyCode = "RON", bankName = "ING", createdAt = 0)
        )
        every { expensesRepo.allWithDetails } returns flowOf(listOf(
            withDetails(expense(uid = "in-scope", updatedAt = 100L).copy(bankAccountId = 1)),
            withDetails(expense(uid = "cash", updatedAt = 100L))
        ))

        val result = handler.export(exportCommand(scopeNames = listOf("ing")))

        assertEquals(listOf("in-scope"), exportedUids(result.text))
    }

    @Test
    fun `at the shared level, the cash sentinel takes the records with no account`() = runTest {
        every { settingsRepo.getSnapshot() } returns
            ExpensesSettings(isBiometricRequired = false, syncLevel = SyncLevel.SHARED.name)
        coEvery { expensesRepo.bankAccountsSnapshot() } returns listOf(
            BankAccount(id = 1, currencyCode = "RON", bankName = "ING", createdAt = 0)
        )
        every { expensesRepo.allWithDetails } returns flowOf(listOf(
            withDetails(expense(uid = "carded", updatedAt = 100L).copy(bankAccountId = 1)),
            withDetails(expense(uid = "cash", updatedAt = 100L))
        ))

        val result = handler.export(exportCommand(scopeNames = listOf(SyncDeltaKeys.SCOPE_NO_ACCOUNT)))

        assertEquals(listOf("cash"), exportedUids(result.text))
    }

    @Test
    fun `an empty scope list shares nothing, unlike a null one`() = runTest {
        every { settingsRepo.getSnapshot() } returns
            ExpensesSettings(isBiometricRequired = false, syncLevel = SyncLevel.SHARED.name)
        every { expensesRepo.allWithDetails } returns flowOf(listOf(
            withDetails(expense(uid = "a", updatedAt = 100L))
        ))

        assertEquals(emptyList<String>(), exportedUids(handler.export(exportCommand(scopeNames = emptyList())).text))
        assertEquals(listOf("a"), exportedUids(handler.export(exportCommand(scopeNames = null)).text))
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

        val result = handler.export(exportCommand())
        val lineItems = JSONObject(result.text).getJSONArray("entries").getJSONObject(0).getJSONArray("lineItems")

        assertEquals(2, lineItems.length())
        assertEquals("Milk", lineItems.getJSONObject(0).getString("name"))
        assertEquals("Bread", lineItems.getJSONObject(1).getString("name"))
    }

    @Test
    fun `export includes tombstones since the watermark`() = runTest {
        coEvery { expensesRepo.tombstonesSince(1000L) } returns listOf(ExpenseTombstone("deleted-uid", 1500L))

        val result = handler.export(exportCommand(since = 1000L))
        val tombstone = JSONObject(result.text).getJSONArray("tombstones").getJSONObject(0)

        assertEquals("deleted-uid", tombstone.getString("uid"))
        assertEquals(1500L, tombstone.getLong("deletedAt"))
    }

    @Test
    fun `export while locked returns a failure without touching the repository`() = runTest {
        every { settingsRepo.getSnapshot() } returns ExpensesSettings(isBiometricRequired = true)
        every { sessionManager.isSessionValid(any()) } returns false

        val result = handler.export(exportCommand())

        assertFalse(result.ok)
        coVerify(exactly = 0) { expensesRepo.allExpensesSnapshot() }
    }

    // --- merge ---

    @Test
    fun `merge inserts a remote uid not present locally`() = runTest {
        coEvery { expensesRepo.insertSyncedExpense(any(), any()) } returns 1L

        val payload = """{"entries":[{"uid":"a","totalAmount":5.0,"currencyCode":"RON","dateTime":0,"createdAt":100,"updatedAt":100}],"tombstones":[]}"""
        handler.merge(mergeCommand(payload))

        coVerify(exactly = 1) { expensesRepo.insertSyncedExpense(match { it.uid == "a" && it.totalAmount == 5.0 }, any()) }
    }

    @Test
    fun `merge inserts a remote entry's line items alongside it`() = runTest {
        coEvery { expensesRepo.insertSyncedExpense(any(), any()) } returns 1L

        val payload = """{"entries":[{"uid":"a","totalAmount":8.0,"currencyCode":"RON","dateTime":0,"createdAt":100,"updatedAt":100,
            "lineItems":[{"name":"Bread","quantity":1.0,"unitPrice":5.0,"position":0},{"name":"Milk","quantity":1.0,"unitPrice":3.0,"position":1}]}],"tombstones":[]}"""
        handler.merge(mergeCommand(payload))

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
        handler.merge(mergeCommand(payload))

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
        handler.merge(mergeCommand(payload))

        coVerify(exactly = 1) { expensesRepo.updateSyncedExpense(match { it.uid == "a" }, match { it.isEmpty() }) }
    }

    @Test
    fun `merge ignores a remote entry that is not newer than the local one`() = runTest {
        coEvery { expensesRepo.allExpensesSnapshot() } returns listOf(expense(uid = "a", updatedAt = 500L))

        val payload = """{"entries":[{"uid":"a","totalAmount":99.0,"currencyCode":"RON","dateTime":0,"createdAt":100,"updatedAt":100}],"tombstones":[]}"""
        handler.merge(mergeCommand(payload))

        coVerify(exactly = 0) { expensesRepo.insertSyncedExpense(any(), any()) }
        coVerify(exactly = 0) { expensesRepo.updateSyncedExpense(any(), any()) }
    }

    @Test
    fun `merge deletes a locally-present uid named in a tombstone`() = runTest {
        coEvery { expensesRepo.allExpensesSnapshot() } returns listOf(expense(uid = "a", updatedAt = 100L))
        coEvery { expensesRepo.deleteExpenseByUid("a") } just Runs

        val payload = """{"entries":[],"tombstones":[{"uid":"a","deletedAt":9999}]}"""
        handler.merge(mergeCommand(payload))

        coVerify(exactly = 1) { expensesRepo.deleteExpenseByUid("a") }
    }

    @Test
    fun `merge resolves an unknown category name by auto-creating it, matching import's convention`() = runTest {
        every { expensesRepo.categories } returns flowOf(emptyList())
        coEvery { expensesRepo.addCategory(any(), any(), any(), any()) } returns 7L
        coEvery { expensesRepo.insertSyncedExpense(any(), any()) } returns 1L

        val payload = """{"entries":[{"uid":"a","totalAmount":5.0,"currencyCode":"RON","dateTime":0,"createdAt":100,"updatedAt":100,"categoryName":"Travel"}],"tombstones":[]}"""
        handler.merge(mergeCommand(payload))

        coVerify(exactly = 1) { expensesRepo.addCategory("Travel", any(), any(), any()) }
        coVerify(exactly = 1) { expensesRepo.insertSyncedExpense(match { it.categoryId == 7L }, any()) }
    }

    @Test
    fun `malformed merge payload returns a failure without touching the repository`() = runTest {
        val result = handler.merge(mergeCommand("{ not json"))

        assertFalse(result.ok)
        coVerify(exactly = 0) { expensesRepo.allExpensesSnapshot() }
    }

    @Test
    fun `merge while locked returns a failure without touching the repository`() = runTest {
        every { settingsRepo.getSnapshot() } returns ExpensesSettings(isBiometricRequired = true)
        every { sessionManager.isSessionValid(any()) } returns false

        val result = handler.merge(mergeCommand("""{"entries":[],"tombstones":[]}"""))

        assertFalse(result.ok)
        coVerify(exactly = 0) { expensesRepo.allExpensesSnapshot() }
    }

    // --- sync level, forced pushes, paging ---

    @Test
    fun `the manual level volunteers nothing but the records explicitly pushed`() = runTest {
        every { settingsRepo.getSnapshot() } returns
            ExpensesSettings(isBiometricRequired = false, syncLevel = SyncLevel.MANUAL.name)
        every { expensesRepo.allWithDetails } returns flowOf(listOf(
            withDetails(expense(uid = "quiet", updatedAt = 2000L)),
            withDetails(expense(uid = "pushed", updatedAt = 2000L))
        ))
        coEvery { expensesRepo.tombstonesSince(any()) } returns listOf(ExpenseTombstone("gone", 3000L))

        val result = handler.export(exportCommand(uids = listOf("pushed")))
        val json = JSONObject(result.text)

        assertEquals(listOf("pushed"), exportedUids(result.text))
        // A pushed copy belongs to the receiving device — a later local deletion is not its business.
        assertEquals(0, json.getJSONArray("tombstones").length())
    }

    @Test
    fun `a forced uid travels even when it is older than the watermark and out of scope`() = runTest {
        every { settingsRepo.getSnapshot() } returns
            ExpensesSettings(isBiometricRequired = false, syncLevel = SyncLevel.SHARED.name)
        every { expensesRepo.allWithDetails } returns flowOf(listOf(
            withDetails(expense(uid = "ancient", updatedAt = 1L))
        ))

        val result = handler.export(exportCommand(since = 9999L, scopeNames = emptyList(), uids = listOf("ancient")))

        assertEquals(listOf("ancient"), exportedUids(result.text))
    }

    @Test
    fun `an export page names its continuation and the last one does not`() = runTest {
        every { expensesRepo.allWithDetails } returns flowOf(
            (1..3).map { withDetails(expense(uid = "e$it", updatedAt = it * 100L)) }
        )

        val first = JSONObject(handler.export(exportCommand(limit = 2)).text)
        assertEquals(listOf("e1", "e2"), exportedUids(first.toString()))
        val cursor = first.getString(SyncDeltaKeys.NEXT_CURSOR)

        val second = JSONObject(handler.export(exportCommand(limit = 2, cursor = cursor)).text)
        assertEquals(listOf("e3"), exportedUids(second.toString()))
        assertFalse(second.has(SyncDeltaKeys.NEXT_CURSOR))
    }

    // --- the wire's null-versus-absent contract ---

    @Test
    fun `a key the delta omits keeps the local value, and an explicit null clears it`() = runTest {
        val local = expense(uid = "a", updatedAt = 100L).copy(
            id = 42, vendor = "Kaufland", location = "Cluj", netAmount = 8.4
        )
        coEvery { expensesRepo.allExpensesSnapshot() } returns listOf(local)
        coEvery { expensesRepo.getIdByUid("a") } returns 42L
        coEvery { expensesRepo.updateSyncedExpense(any(), any()) } just Runs

        // vendor absent (an older peer never knew it), location explicitly null (really cleared).
        val payload = """{"entries":[{"uid":"a","totalAmount":9.0,"currencyCode":"RON","dateTime":0,
            "createdAt":100,"updatedAt":200,"location":null}],"tombstones":[]}"""
        handler.merge(mergeCommand(payload))

        coVerify(exactly = 1) {
            expensesRepo.updateSyncedExpense(
                match { it.vendor == "Kaufland" && it.location == null && it.netAmount == 8.4 },
                any()
            )
        }
    }

    @Test
    fun `an entry with no line items key leaves the local ones alone`() = runTest {
        coEvery { expensesRepo.allExpensesSnapshot() } returns listOf(expense(uid = "a", updatedAt = 100L).copy(id = 42))
        coEvery { expensesRepo.getIdByUid("a") } returns 42L
        coEvery { expensesRepo.updateSyncedExpense(any(), any()) } just Runs

        val payload = """{"entries":[{"uid":"a","totalAmount":9.0,"currencyCode":"RON","dateTime":0,"createdAt":100,"updatedAt":200}],"tombstones":[]}"""
        handler.merge(mergeCommand(payload))

        coVerify(exactly = 1) { expensesRepo.updateSyncedExpense(any(), null) }
    }

    @Test
    fun `every field the entity carries survives an export-merge round trip`() = runTest {
        val rich = expense(uid = "a", updatedAt = 100L).copy(
            title = "Weekly shop", vendor = "Kaufland", location = "Cluj",
            previousBalanceAmount = 3.0, invoiceOwnAmount = 4.0, netAmount = 8.4, vatAmount = 1.6,
            originsJson = """{"vendor":"SCAN"}""", comments = "note", isStub = true,
            manuallyEdited = true, archivedAt = 777L
        )
        every { expensesRepo.allWithDetails } returns flowOf(listOf(withDetails(rich)))
        coEvery { expensesRepo.insertSyncedExpense(any(), any()) } returns 1L

        val delta = handler.export(exportCommand()).text
        handler.merge(mergeCommand(delta))

        coVerify(exactly = 1) {
            expensesRepo.insertSyncedExpense(
                match {
                    it.title == "Weekly shop" && it.vendor == "Kaufland" && it.location == "Cluj" &&
                        it.previousBalanceAmount == 3.0 && it.invoiceOwnAmount == 4.0 &&
                        it.netAmount == 8.4 && it.vatAmount == 1.6 &&
                        it.originsJson == """{"vendor":"SCAN"}""" && it.comments == "note" &&
                        it.isStub && it.manuallyEdited && it.archivedAt == 777L
                },
                any()
            )
        }
    }

    // --- provenance ---

    @Test
    fun `an inserted row is stamped with the device it came from`() = runTest {
        coEvery { expensesRepo.insertSyncedExpense(any(), any()) } returns 1L

        val payload = """{"entries":[{"uid":"a","totalAmount":5.0,"currencyCode":"RON","dateTime":0,"createdAt":100,"updatedAt":100}],"tombstones":[]}"""
        handler.merge(mergeCommand(payload, sourceDeviceId = "peer-1", sourceDeviceName = "Madi"))

        coVerify(exactly = 1) {
            expensesRepo.insertSyncedExpense(
                match { it.originDeviceId == "peer-1" && it.originDeviceName == "Madi" },
                any()
            )
        }
    }

    @Test
    fun `an update never rewrites the stamp an existing row already carries`() = runTest {
        coEvery { expensesRepo.allExpensesSnapshot() } returns listOf(
            expense(uid = "a", updatedAt = 100L).copy(id = 42, originDeviceId = "peer-1", originDeviceName = "Madi")
        )
        coEvery { expensesRepo.getIdByUid("a") } returns 42L
        coEvery { expensesRepo.updateSyncedExpense(any(), any()) } just Runs

        val payload = """{"entries":[{"uid":"a","totalAmount":9.0,"currencyCode":"RON","dateTime":0,"createdAt":100,"updatedAt":200}],"tombstones":[]}"""
        handler.merge(mergeCommand(payload, sourceDeviceId = "peer-2", sourceDeviceName = "Other"))

        coVerify(exactly = 1) {
            expensesRepo.updateSyncedExpense(
                match { it.originDeviceId == "peer-1" && it.originDeviceName == "Madi" },
                any()
            )
        }
    }

    @Test
    fun `the merge result reports its counts as numbers`() = runTest {
        coEvery { expensesRepo.insertSyncedExpense(any(), any()) } returns 1L

        val payload = """{"entries":[{"uid":"a","totalAmount":5.0,"currencyCode":"RON","dateTime":0,"createdAt":100,"updatedAt":100}],"tombstones":[]}"""
        val counts = JSONObject(handler.merge(mergeCommand(payload)).text)

        assertEquals(1, counts.getInt(SyncDeltaKeys.INSERTED))
        assertEquals(0, counts.getInt(SyncDeltaKeys.UPDATED))
        assertEquals(0, counts.getInt(SyncDeltaKeys.DELETED))
    }
}
