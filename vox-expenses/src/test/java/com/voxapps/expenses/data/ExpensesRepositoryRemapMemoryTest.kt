package com.voxapps.expenses.data

import com.voxapps.datahygiene.RemapCondition
import android.content.Context
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ExpensesRepositoryRemapMemoryTest {

    private lateinit var categoryDao: CategoryDao
    private lateinit var remapRuleDao: RemapRuleDao
    private lateinit var sightingDao: FakeSightingDao
    private lateinit var bankAccountDao: BankAccountDao
    private lateinit var repository: ExpensesRepository

    private val groceries = Category(id = 5L, name = "Groceries", colorArgb = 0L, createdAt = 0L)
    private val transport = Category(id = 9L, name = "Transport", colorArgb = 0L, createdAt = 0L)

    /** In-memory DAO — the learner's read-back of what it just wrote is the behavior under test. */
    private class FakeSightingDao : RemapPatternSightingDao {
        val rows = mutableMapOf<Pair<String, Long>, RemapPatternSighting>()
        override suspend fun getForPattern(patternKey: String) =
            rows.values.filter { it.patternKey == patternKey }
        override suspend fun upsert(sighting: RemapPatternSighting) {
            rows[sighting.patternKey to sighting.recordId] = sighting
        }
        override suspend fun deleteForPattern(patternKey: String) {
            rows.keys.filter { it.first == patternKey }.forEach { rows.remove(it) }
        }
        override suspend fun deleteAll() = rows.clear()
    }

    private fun details(id: Long, vendor: String?, categoryId: Long? = null, title: String? = "t") =
        ExpenseWithDetails(
            expense = Expense(
                id = id, title = title, totalAmount = 1.0, currencyCode = "RON",
                vendor = vendor, location = null, dateTime = 0L, bankAccountId = 5L,
                comments = null, categoryId = categoryId
            ),
            items = emptyList()
        )

    private fun edited(id: Long, vendor: String?, categoryId: Long? = null, title: String? = "t") =
        details(id, vendor, categoryId, title).expense

    /** The conditions a stored trigger holds, flattened — every proposal drafts one group. */
    private fun triggerOf(rule: RemapRuleEntity): List<Pair<String, String>> =
        RemapConditionsJson.decode(rule.matchJson).flatten().map { it.fieldId to it.value }

    @Before
    fun setup() {
        categoryDao = mockk(relaxed = true)
        remapRuleDao = mockk(relaxed = true)
        sightingDao = FakeSightingDao()
        bankAccountDao = mockk(relaxed = true)
        // The bank a capture "also carried" is the name of the account it points at.
        coEvery { bankAccountDao.getAll() } returns listOf(
            BankAccount(id = 5, currencyCode = "RON", createdAt = 0L, bankName = "ING")
        )
        repository = ExpensesRepository(
            mockk(relaxed = true), categoryDao, mockk(relaxed = true), mockk(relaxed = true),
            remapRuleDao, sightingDao, mockk<Context>(), mockk(relaxed = true),
            mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), bankAccountDao
        )
        coEvery { categoryDao.getAll() } returns listOf(groceries, transport)
        coEvery { remapRuleDao.getByMatch(any()) } returns null
        coEvery { remapRuleDao.getAll() } returns emptyList()
    }

    @Test
    fun `an unchanged save records nothing`() = runTest {
        repository.recordRemapPatternSightings(details(1, "Lidl"), edited(1, "Lidl"), threshold = 2)
        assertTrue(sightingDao.rows.isEmpty())
        coVerify(exactly = 0) { remapRuleDao.upsert(any()) }
    }

    @Test
    fun `below threshold a rename records a sighting but drafts nothing`() = runTest {
        repository.recordRemapPatternSightings(details(1, "LAZAR IONUT PFA"), edited(1, "Lazar's Shop"), threshold = 2)
        assertEquals(1, sightingDao.rows.size)
        coVerify(exactly = 0) { remapRuleDao.upsert(any()) }
    }

    @Test
    fun `re-saving the same record never counts twice`() = runTest {
        repeat(5) {
            repository.recordRemapPatternSightings(details(1, "LAZAR IONUT PFA"), edited(1, "Lazar's Shop"), threshold = 2)
        }
        assertEquals(1, sightingDao.rows.size)
        coVerify(exactly = 0) { remapRuleDao.upsert(any()) }
    }

    @Test
    fun `the threshold-th distinct record drafts a DISABLED proposal and clears the sightings`() = runTest {
        repository.recordRemapPatternSightings(details(1, "LAZAR IONUT PFA"), edited(1, "Lazar's Shop"), threshold = 2)
        repository.recordRemapPatternSightings(details(2, "LAZAR IONUT PFA"), edited(2, "Lazar's Shop"), threshold = 2)

        coVerify(exactly = 1) {
            remapRuleDao.upsert(match { rule ->
                !rule.enabled && rule.origin == RemapRuleEntity.ORIGIN_PROPOSED &&
                    triggerOf(rule) == listOf("vendor" to "lazar ionut pfa") &&
                    RemapRuleJson.decode(rule.setJson) == mapOf("vendor" to "Lazar's Shop")
            })
        }
        assertTrue(sightingDao.rows.isEmpty())
    }

    @Test
    /**
     * Two sightings of one shop that disagree about the result propose nothing.
     *
     * Both are counted against the shop, since that is what identifies it — but a set survives only
     * where every sighting made the same edit, and renaming to two different names twice is not a
     * habit, it is indecision.
     */
    fun `sightings that disagree about the result propose nothing`() = runTest {
        repository.recordRemapPatternSightings(details(1, "LAZAR IONUT PFA"), edited(1, "Lazar's Shop"), threshold = 2)
        repository.recordRemapPatternSightings(details(2, "LAZAR IONUT PFA"), edited(2, "Lazar Market"), threshold = 2)
        coVerify(exactly = 0) { remapRuleDao.upsert(any()) }
    }

    @Test
    fun `consistent companions ride along, inconsistent ones stay out`() = runTest {
        // Both sightings rename the vendor AND set category to Groceries; titles diverge.
        repository.recordRemapPatternSightings(
            details(1, "LAZAR IONUT PFA", categoryId = null, title = "a"),
            edited(1, "Lazar's Shop", categoryId = groceries.id, title = "x"),
            threshold = 2
        )
        repository.recordRemapPatternSightings(
            details(2, "LAZAR IONUT PFA", categoryId = transport.id, title = "b"),
            edited(2, "Lazar's Shop", categoryId = groceries.id, title = "y"),
            threshold = 2
        )

        coVerify {
            remapRuleDao.upsert(match { rule ->
                val set = RemapRuleJson.decode(rule.setJson)
                triggerOf(rule) == listOf("vendor" to "lazar ionut pfa") &&
                    set["vendor"] == "Lazar's Shop" &&
                    set["categoryId"] == groceries.id.toString() &&
                    "title" !in set
            })
        }
    }

    /**
     * Recategorising is triggered by the shop, never by the category it was moved out of.
     *
     * The old category is what the app guessed, not something true about the spending: a rule keyed
     * on it would fire on every record that guess was ever wrong about, and would read as
     * "Transport becomes Groceries", which is not a thing anybody meant to say.
     */
    @Test
    fun `recategorising is triggered by the shop, not by the category left behind`() = runTest {
        repository.recordRemapPatternSightings(
            details(1, "Lidl", categoryId = transport.id), edited(1, "Lidl", categoryId = groceries.id), threshold = 1
        )
        coVerify(exactly = 1) {
            remapRuleDao.upsert(match { rule ->
                !rule.enabled &&
                    triggerOf(rule) == listOf("vendor" to "lidl") &&
                    RemapRuleJson.decode(rule.setJson) == mapOf("categoryId" to groceries.id.toString())
            })
        }
    }

    /** With nothing identifying the record, there is nothing a later capture could be recognised
     *  by — so no rule is drafted at all. */
    @Test
    fun `an edit on a record with no vendor drafts nothing`() = runTest {
        repository.recordRemapPatternSightings(
            details(1, null, categoryId = transport.id), edited(1, null, categoryId = groceries.id), threshold = 1
        )
        assertTrue(sightingDao.rows.isEmpty())
        coVerify(exactly = 0) { remapRuleDao.upsert(any()) }
    }

    @Test
    fun `an existing rule for the trigger suppresses the proposal`() = runTest {
        coEvery { remapRuleDao.getByMatch(any()) } returns RemapRuleEntity(
            id = 1, name = "existing",
            matchJson = RemapConditionsJson.encode(listOf(listOf(RemapCondition("vendor", "lazar ionut pfa")))),
            setJson = "{}", origin = RemapRuleEntity.ORIGIN_USER, updatedAt = 0L
        )
        repository.recordRemapPatternSightings(details(1, "LAZAR IONUT PFA"), edited(1, "Lazar's Shop"), threshold = 1)

        coVerify(exactly = 0) { remapRuleDao.upsert(any()) }
        assertTrue(sightingDao.rows.isEmpty())
    }

    /**
     * A proposal triggers on the shop alone and offers the rest.
     *
     * Everything those captures also had in common is a narrowing somebody might want — the same
     * shop, but only on that card. Putting it into the trigger unasked would write a rule nobody
     * wrote; leaving it out entirely would throw away the only evidence there is.
     */
    @Test
    fun `what every capture also carried is offered, not imposed`() = runTest {
        repository.recordRemapPatternSightings(
            details(1, "Lidl", categoryId = null).let {
                it.copy(expense = it.expense.copy(location = "Cluj"))
            },
            edited(1, "Lidl", categoryId = groceries.id), threshold = 1
        )
        coVerify(exactly = 1) {
            remapRuleDao.upsert(match { rule ->
                triggerOf(rule) == listOf("vendor" to "lidl") &&
                    RemapRuleJson.decode(rule.suggestJson) ==
                    mapOf("bank" to "ing", "location" to "cluj", "totalAmount" to "100", "title" to "t")
            })
        }
    }

    @Test
    fun `a value only one capture carried is not offered`() = runTest {
        repository.recordRemapPatternSightings(
            details(1, "Lidl").let { it.copy(expense = it.expense.copy(location = "Cluj")) },
            edited(1, "Lidl", categoryId = groceries.id), threshold = 2
        )
        repository.recordRemapPatternSightings(
            details(2, "Lidl").let { it.copy(expense = it.expense.copy(location = "Oradea")) },
            edited(2, "Lidl", categoryId = groceries.id), threshold = 2
        )
        coVerify(exactly = 1) {
            remapRuleDao.upsert(match { rule ->
                val offered = RemapRuleJson.decode(rule.suggestJson)
                offered["bank"] == "ing" && "location" !in offered
            })
        }
    }

    /** A corrected field is a result, not identity: offering it would ask a later capture to
     *  arrive already fixed. */
    @Test
    fun `a field the rule writes is never offered as a condition`() = runTest {
        repository.recordRemapPatternSightings(
            details(1, "Lidl").let { it.copy(expense = it.expense.copy(location = "Cluj")) },
            edited(1, "Lidl").copy(location = "Cluj-Napoca"), threshold = 1
        )
        coVerify(exactly = 1) {
            remapRuleDao.upsert(match { rule ->
                val offered = RemapRuleJson.decode(rule.suggestJson)
                "location" !in offered && "vendor" !in offered
            })
        }
    }

    @Test
    fun `filling an empty field or clearing one is not a rename`() = runTest {
        repository.recordRemapPatternSightings(details(1, null), edited(1, "Lidl"), threshold = 1)
        repository.recordRemapPatternSightings(details(2, "Lidl"), edited(2, null), threshold = 1)
        assertTrue(sightingDao.rows.isEmpty())
        coVerify(exactly = 0) { remapRuleDao.upsert(any()) }
    }
}
