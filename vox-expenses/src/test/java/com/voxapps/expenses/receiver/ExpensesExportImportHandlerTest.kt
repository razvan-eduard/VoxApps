package com.voxapps.expenses.receiver

import android.content.Context
import com.voxapps.attachments.AttachmentDao
import com.voxapps.attachments.AttachmentEntity
import com.voxapps.expenses.data.BankAccount
import com.voxapps.expenses.data.Category
import com.voxapps.expenses.data.DuplicateRuleDao
import com.voxapps.expenses.data.DuplicateRuleEntity
import com.voxapps.expenses.data.Expense
import com.voxapps.expenses.data.ExpensesAttachments
import com.voxapps.expenses.data.ExpenseWithDetails
import com.voxapps.expenses.data.ExpensesRepository
import com.voxapps.expenses.data.SpendingLimit
import com.voxapps.expenses.data.preferences.ExpensesSettings
import com.voxapps.expenses.data.preferences.ExpensesSettingsRepository
import com.voxapps.expenses.state.SessionManager
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import com.voxapps.expenses.domain.recurring.RecurringPaymentRepository
import org.junit.Before
import org.junit.Test

class ExpensesExportImportHandlerTest {

    private lateinit var context: Context
    private lateinit var settingsRepo: ExpensesSettingsRepository
    private lateinit var sessionManager: SessionManager
    private lateinit var expensesRepo: ExpensesRepository
    private lateinit var attachmentDao: AttachmentDao
    private lateinit var duplicateRuleDao: DuplicateRuleDao
    private lateinit var recurringPaymentRepo: RecurringPaymentRepository
    private lateinit var handler: ExpensesExportImportHandler

    @Before
    fun setup() {
        context = mockk(relaxed = true)
        settingsRepo = mockk()
        sessionManager = mockk()
        expensesRepo = mockk()
        attachmentDao = mockk(relaxed = true)
        duplicateRuleDao = mockk(relaxed = true)
        recurringPaymentRepo = mockk(relaxed = true)
        coEvery { recurringPaymentRepo.snapshot() } returns emptyList()
        handler = ExpensesExportImportHandler(
            context, settingsRepo, sessionManager, expensesRepo, attachmentDao, duplicateRuleDao,
            recurringPaymentRepo, "The expenses are locked. Unlock the app."
        )

        every { settingsRepo.getSnapshot() } returns ExpensesSettings(isBiometricRequired = false)
        every { expensesRepo.categories } returns flowOf(emptyList())
        every { expensesRepo.spendingLimits } returns flowOf(emptyList())
        every { expensesRepo.allWithDetails } returns flowOf(emptyList())
        coEvery { expensesRepo.allExpensesSnapshot() } returns emptyList()
        coEvery { expensesRepo.deleteExpenseById(any()) } just Runs
        coEvery { expensesRepo.deleteSpendingLimit(any()) } just Runs
        coEvery { expensesRepo.remapRulesSnapshot() } returns emptyList()
        coEvery { expensesRepo.learnedFieldCorrectionsSnapshot() } returns emptyList()
        coEvery { expensesRepo.setDefaultCategory(any()) } just Runs
        coEvery { expensesRepo.addCategory(any(), any(), any(), any(), any()) } returns 1L
        coEvery { expensesRepo.bankAccountsSnapshot() } returns emptyList()
        coEvery { expensesRepo.addBankAccount(any()) } returns 1L
        coEvery { expensesRepo.updateBankAccount(any()) } just Runs
        every { duplicateRuleDao.observeAll() } returns flowOf(emptyList())
        coEvery { duplicateRuleDao.getAll() } returns emptyList()
    }

    private fun expense(id: Long, totalAmount: Double = 10.0, createdAt: Long, receiptImageName: String? = null) = Expense(
        id = id,
        totalAmount = totalAmount,
        currencyCode = "RON",
        dateTime = 0L,
        createdAt = createdAt,
        receiptImageName = receiptImageName
    )

    @Test
    fun `export without includePhotos never touches zip building, attachmentUri stays null`() = runTest {
        every { expensesRepo.allWithDetails } returns flowOf(
            listOf(ExpenseWithDetails(expense(1, createdAt = 100L, receiptImageName = "rec_1.jpg"), items = emptyList()))
        )

        val result = handler.export(includePhotos = false)

        assertTrue(result.ok)
        assertNull(result.attachmentUri)
    }

    @Test
    fun `export serializes receiptImageName and createdAt on each expense`() = runTest {
        every { expensesRepo.allWithDetails } returns flowOf(
            listOf(ExpenseWithDetails(expense(1, createdAt = 12345L, receiptImageName = "rec_abc.jpg"), items = emptyList()))
        )

        val result = handler.export()
        val exported = JSONObject(result.text).getJSONArray("expenses").getJSONObject(0)

        assertEquals("rec_abc.jpg", exported.getString("receiptImageName"))
        assertEquals(12345L, exported.getLong("createdAt"))
    }

    @Test
    fun `import only deletes pre-existing expenses created at or before exported_at`() = runTest {
        // 100 existed at export time (createdAt <= exportedAt=1000) -> replaceable.
        // 2000 was created after the export -> an "in-limbo" record that must survive.
        coEvery { expensesRepo.allExpensesSnapshot() } returns listOf(
            expense(id = 100, createdAt = 500L),
            expense(id = 200, createdAt = 2000L)
        )
        coEvery { expensesRepo.addExpense(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns 1L

        val payload = """{"exported_at":1000,"expenses":[{"totalAmount":5.0,"currencyCode":"RON"}]}"""
        handler.import(payload)

        coVerify(exactly = 1) { expensesRepo.deleteExpenseById(100) }
        coVerify(exactly = 0) { expensesRepo.deleteExpenseById(200) }
    }

    @Test
    fun `import deletes nothing when exported_at is absent (fails safe)`() = runTest {
        coEvery { expensesRepo.allExpensesSnapshot() } returns listOf(expense(id = 100, createdAt = 500L))

        val payload = """{"expenses":[]}"""
        handler.import(payload)

        coVerify(exactly = 0) { expensesRepo.deleteExpenseById(any()) }
    }

    @Test
    fun `import preserves the imported row's original createdAt, not the current time`() = runTest {
        coEvery { expensesRepo.addExpense(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns 1L

        val payload = """{"exported_at":9999999999,"expenses":[{"totalAmount":5.0,"currencyCode":"RON","createdAt":42}]}"""
        handler.import(payload)

        coVerify(exactly = 1) {
            expensesRepo.addExpense(
                title = null, totalAmount = 5.0, currencyCode = "RON", vendor = null, location = null, dateTime = any(), comments = null, categoryId = null, items = emptyList(),
                imageName = null, createdAt = 42L, checkForDuplicate = false
            )
        }
    }

    @Test
    fun `import passes receiptImageName through to addExpense`() = runTest {
        coEvery { expensesRepo.addExpense(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns 1L

        val payload = """{"expenses":[{"totalAmount":5.0,"currencyCode":"RON","receiptImageName":"rec_x.jpg"}]}"""
        handler.import(payload)

        coVerify(exactly = 1) {
            expensesRepo.addExpense(
                title = null, totalAmount = 5.0, currencyCode = "RON", vendor = null, location = null, dateTime = any(), comments = null, categoryId = null, items = emptyList(),
                imageName = "rec_x.jpg", createdAt = any(), checkForDuplicate = false
            )
        }
    }

    @Test
    fun `import only deletes pre-existing spending limits created at or before exported_at`() = runTest {
        every { expensesRepo.spendingLimits } returns flowOf(
            listOf(
                SpendingLimit(id = 1, amountHomeCurrency = 100.0, period = SpendingLimit.PERIOD_MONTHLY, createdAt = 500L),
                SpendingLimit(id = 2, amountHomeCurrency = 200.0, period = SpendingLimit.PERIOD_WEEKLY, createdAt = 2000L)
            )
        )
        coEvery { expensesRepo.addSpendingLimit(any(), any(), any(), any()) } returns 1L

        val payload = """{"exported_at":1000,"spendingLimits":[]}"""
        handler.import(payload)

        coVerify(exactly = 1) { expensesRepo.deleteSpendingLimit(match { it.id == 1L }) }
        coVerify(exactly = 0) { expensesRepo.deleteSpendingLimit(match { it.id == 2L }) }
    }

    @Test
    fun `malformed payload returns a failure result without touching the repository`() = runTest {
        val result = handler.import("{ not json")

        assertFalse(result.ok)
        coVerify(exactly = 0) { expensesRepo.allExpensesSnapshot() }
    }

    @Test
    fun `export nests each expense's attachments under its own entry`() = runTest {
        every { expensesRepo.allWithDetails } returns flowOf(
            listOf(ExpenseWithDetails(expense(1, createdAt = 100L), items = emptyList()))
        )
        coEvery { attachmentDao.getFor(ExpensesAttachments.RECORD_TYPE, 1L) } returns listOf(
            AttachmentEntity(id = 9, recordType = ExpensesAttachments.RECORD_TYPE, recordId = 1L, fileName = "att_1.jpg", source = "manual", createdAt = 55L)
        )

        val result = handler.export(includePhotos = false)

        val expenseJson = JSONObject(result.text).getJSONArray("expenses").getJSONObject(0)
        val attachmentsJson = expenseJson.getJSONArray("attachments")
        assertEquals(1, attachmentsJson.length())
        assertEquals("att_1.jpg", attachmentsJson.getJSONObject(0).getString("fileName"))
    }

    @Test
    fun `import inserts each nested attachment against the newly created expense's id`() = runTest {
        coEvery {
            expensesRepo.addExpense(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns 42L

        val payload = """{"expenses":[{"totalAmount":5.0,"currencyCode":"RON","attachments":[
            {"fileName":"att_1.jpg","source":"manual","createdAt":10}
        ]}]}"""
        handler.import(payload)

        coVerify(exactly = 1) {
            attachmentDao.insert(
                AttachmentEntity(recordType = ExpensesAttachments.RECORD_TYPE, recordId = 42L, fileName = "att_1.jpg", source = "manual", createdAt = 10L)
            )
        }
    }

    @Test
    fun `import skips attachments with a blank fileName`() = runTest {
        coEvery {
            expensesRepo.addExpense(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns 3L

        handler.import("""{"expenses":[{"totalAmount":1.0,"currencyCode":"RON","attachments":[{"fileName":"","source":"manual","createdAt":1}]}]}""")

        coVerify(exactly = 0) { attachmentDao.insert(any()) }
    }

    @Test
    fun `settings export then import round-trips fields the old hand-written allowlist used to drop`() = runTest {
        // duplicateCheckModeAutomatic/todayEffectColor2 were both missing from the old
        // hand-maintained toJson() (only 20 of ~50 fields were exported) — this is exactly the
        // class of field the Gson-reflection switch fixes.
        val original = ExpensesSettings(
            isBiometricRequired = false,
            duplicateCheckModeAutomatic = ExpensesSettings.MODE_LOCAL_AND_AI,
            todayEffectColor2 = 0xFF00FF00L,
            appCacheJson = "{\"stale\":true}",
            onboardingCompleted = true
        )
        every { settingsRepo.getSnapshot() } returns original
        val restored = slot<ExpensesSettings>()
        coEvery { settingsRepo.restoreSettings(capture(restored)) } just Runs

        val exportResult = handler.export(includePhotos = false)
        handler.import(exportResult.text)

        assertEquals(original.duplicateCheckModeAutomatic, restored.captured.duplicateCheckModeAutomatic)
        assertEquals(original.todayEffectColor2, restored.captured.todayEffectColor2)
        // appCacheJson/onboardingCompleted are the two deliberate exclusions — neither should
        // survive the round-trip.
        assertNull(restored.captured.appCacheJson)
        assertFalse(restored.captured.onboardingCompleted)
    }

    @Test
    fun `duplicateRules merge by name instead of duplicating on repeat restores`() = runTest {
        every { duplicateRuleDao.observeAll() } returns flowOf(
            listOf(DuplicateRuleEntity(id = 1, name = "Same amount+title", fieldIds = listOf("amount"), combinator = "AND"))
        )
        coEvery { duplicateRuleDao.getAll() } returns listOf(DuplicateRuleEntity(id = 1, name = "Same amount+title", fieldIds = listOf("amount"), combinator = "AND"))

        val payload = """{"duplicateRules":[{"name":"Same amount+title","fieldIds":["amount","title"],"combinator":"OR"}]}"""
        handler.import(payload)

        coVerify(exactly = 0) { duplicateRuleDao.upsert(any()) }
        coVerify(exactly = 1) { duplicateRuleDao.update(match { it.fieldIds == listOf("amount", "title") && it.combinator == "OR" }) }
    }

    @Test
    fun `duplicateRules creates a new rule when no name matches`() = runTest {
        every { duplicateRuleDao.observeAll() } returns flowOf(emptyList())
        coEvery { duplicateRuleDao.getAll() } returns emptyList()

        val payload = """{"duplicateRules":[{"name":"New Rule","fieldIds":["vendor"],"combinator":"AND"}]}"""
        handler.import(payload)

        coVerify(exactly = 1) { duplicateRuleDao.upsert(match { it.name == "New Rule" }) }
    }

    // --- the category a record with no opinion falls back to ---

    private fun category(id: Long, name: String, isDefault: Boolean = false, icon: String? = null) =
        Category(id = id, name = name, colorArgb = 0xFF000000, position = 0, createdAt = 0L, isDefault = isDefault, icon = icon)

    @Test
    fun `export records which category is the fallback`() = runTest {
        every { expensesRepo.categories } returns flowOf(
            listOf(category(1, "Groceries", isDefault = true), category(2, "Uncategorised"))
        )

        val exported = JSONObject(handler.export(includePhotos = false).text).getJSONArray("categories")
        assertEquals(2, exported.length())
        assertTrue("the starred one says so", exported.getJSONObject(0).optBoolean("isDefault"))
        assertFalse("and the other does not", exported.getJSONObject(1).optBoolean("isDefault"))
    }

    @Test
    fun `export records a category's icon and omits it where there is none`() = runTest {
        every { expensesRepo.categories } returns flowOf(
            listOf(category(1, "Groceries", icon = "🛒"), category(2, "Uncategorised"))
        )

        val exported = JSONObject(handler.export(includePhotos = false).text).getJSONArray("categories")
        assertEquals("🛒", exported.getJSONObject(0).optString("icon"))
        assertFalse("nothing to say, nothing said", exported.getJSONObject(1).has("icon"))
    }

    /** The star moves onto whichever local category the marked one merged into. */
    @Test
    fun `import moves the fallback onto the matching local category`() = runTest {
        every { expensesRepo.categories } returns flowOf(
            listOf(category(7, "Groceries"), category(8, "Uncategorised", isDefault = true))
        )

        handler.import("""{"categories":[{"id":1,"name":"Groceries","isDefault":true},{"id":2,"name":"Uncategorised"}]}""")

        coVerify(exactly = 1) { expensesRepo.setDefaultCategory(7L) }
    }

    /** Created rather than matched, and the star still finds it. */
    @Test
    fun `import moves the fallback onto a category it had to create`() = runTest {
        every { expensesRepo.categories } returns flowOf(emptyList())
        coEvery { expensesRepo.addCategory(any(), any(), any(), any(), any()) } returns 42L

        handler.import("""{"categories":[{"id":1,"name":"Groceries","isDefault":true}]}""")

        coVerify(exactly = 1) { expensesRepo.setDefaultCategory(42L) }
    }

    /** Silence is not an instruction to change anything. */
    @Test
    fun `a backup marking no fallback leaves this device's own alone`() = runTest {
        every { expensesRepo.categories } returns flowOf(listOf(category(7, "Groceries", isDefault = true)))

        handler.import("""{"categories":[{"id":1,"name":"Groceries"},{"id":2,"name":"Utilities"}]}""")

        coVerify(exactly = 0) { expensesRepo.setDefaultCategory(any()) }
    }

    @Test
    fun `a marked category that did not survive the trip moves nothing`() = runTest {
        every { expensesRepo.categories } returns flowOf(listOf(category(7, "Groceries", isDefault = true)))
        coEvery { expensesRepo.addCategory(any(), any(), any(), any(), any()) } returns -1L

        handler.import("""{"categories":[{"id":1,"name":"Utilities","isDefault":true}]}""")

        coVerify(exactly = 0) { expensesRepo.setDefaultCategory(any()) }
    }

    @Test
    fun `import carries a category's icon onto the one it creates`() = runTest {
        every { expensesRepo.categories } returns flowOf(emptyList())
        coEvery { expensesRepo.addCategory(any(), any(), any(), any(), any()) } returns 42L

        handler.import("""{"categories":[{"id":1,"name":"Groceries","icon":"🛒"}]}""")

        coVerify(exactly = 1) { expensesRepo.addCategory("Groceries", any(), any(), any(), "🛒") }
    }

    // --- the cards and accounts money went through ---

    private fun account(id: Long, digits: String, parent: Long? = null) = BankAccount(
        id = id, digits = digits, kind = "CARD_TAIL", parentId = parent,
        currencyCode = "RON", label = "Everyday", createdAt = 0L
    )

    @Test
    fun `export carries the accounts and what each record went through`() = runTest {
        coEvery { expensesRepo.bankAccountsSnapshot() } returns listOf(account(7, "4535"))
        every { expensesRepo.allWithDetails } returns flowOf(
            listOf(
                ExpenseWithDetails(
                    expense = Expense(id = 1, totalAmount = 10.0, currencyCode = "RON", dateTime = 0L, bankAccountId = 7),
                    items = emptyList(), category = null
                )
            )
        )

        val json = JSONObject(handler.export().text)
        val accounts = json.getJSONArray("bankAccounts")
        assertEquals(1, accounts.length())
        assertEquals("4535", accounts.getJSONObject(0).getString("digits"))
        assertEquals("Everyday", accounts.getJSONObject(0).getString("label"))
        assertEquals(7L, json.getJSONArray("expenses").getJSONObject(0).getLong("bankAccountId"))
    }

    /** A card the device already knows is landed on, not collided with — the digits are unique. */
    @Test
    fun `import lands on an account this device already has`() = runTest {
        coEvery { expensesRepo.bankAccountsSnapshot() } returns listOf(account(99, "4535"))

        handler.import("""{"bankAccounts":[{"id":1,"digits":"4535","kind":"CARD_TAIL","currencyCode":"RON"}]}""")

        coVerify(exactly = 0) { expensesRepo.addBankAccount(any()) }
    }

    @Test
    fun `import creates an account this device has never seen`() = runTest {
        handler.import("""{"bankAccounts":[{"id":1,"digits":"9999","kind":"CARD_TAIL","currencyCode":"EUR","label":"Travel"}]}""")

        coVerify(exactly = 1) {
            expensesRepo.addBankAccount(match { it.digits == "9999" && it.label == "Travel" })
        }
    }

    /** A card may be listed before the account it belongs to, so the link is made in a second pass. */
    @Test
    fun `import restores which account a card belongs to`() = runTest {
        coEvery { expensesRepo.bankAccountsSnapshot() } returnsMany listOf(
            emptyList(),
            listOf(account(10, "RO49"), account(11, "4535")),
            listOf(account(10, "RO49"), account(11, "4535"))
        )
        coEvery { expensesRepo.addBankAccount(any()) } returnsMany listOf(11L, 10L)

        handler.import(
            """{"bankAccounts":[
                {"id":2,"digits":"4535","kind":"CARD_TAIL","currencyCode":"RON","parentId":1},
                {"id":1,"digits":"RO49","kind":"IBAN","currencyCode":"RON"}
            ]}"""
        )

        coVerify { expensesRepo.updateBankAccount(match { it.id == 11L && it.parentId == 10L }) }
    }
}
