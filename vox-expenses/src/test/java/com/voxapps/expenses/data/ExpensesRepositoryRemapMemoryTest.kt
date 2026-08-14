package com.voxapps.expenses.data

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
                vendor = vendor, bank = null, location = null, dateTime = 0L,
                comments = null, categoryId = categoryId
            ),
            items = emptyList()
        )

    private fun edited(id: Long, vendor: String?, categoryId: Long? = null, title: String? = "t") =
        details(id, vendor, categoryId, title).expense

    @Before
    fun setup() {
        categoryDao = mockk(relaxed = true)
        remapRuleDao = mockk(relaxed = true)
        sightingDao = FakeSightingDao()
        repository = ExpensesRepository(
            mockk(relaxed = true), categoryDao, mockk(relaxed = true), mockk(relaxed = true),
            remapRuleDao, sightingDao, mockk<Context>(), mockk(relaxed = true),
            mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true)
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
                    RemapRuleJson.decode(rule.matchJson) == mapOf("vendor" to "lazar ionut pfa") &&
                    RemapRuleJson.decode(rule.setJson) == mapOf("vendor" to "Lazar's Shop")
            })
        }
        assertTrue(sightingDao.rows.isEmpty())
    }

    @Test
    fun `a different after-value is a separate pattern`() = runTest {
        repository.recordRemapPatternSightings(details(1, "LAZAR IONUT PFA"), edited(1, "Lazar's Shop"), threshold = 2)
        repository.recordRemapPatternSightings(details(2, "LAZAR IONUT PFA"), edited(2, "Lazar Market"), threshold = 2)
        assertEquals(2, sightingDao.rows.size)
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
                RemapRuleJson.decode(rule.matchJson) == mapOf("vendor" to "lazar ionut pfa") &&
                    set["vendor"] == "Lazar's Shop" &&
                    set["categoryId"] == groceries.id.toString() &&
                    "title" !in set
            })
        }
    }

    @Test
    fun `a category change is a pattern of its own, name-triggered and id-set`() = runTest {
        repository.recordRemapPatternSightings(
            details(1, "Lidl", categoryId = transport.id), edited(1, "Lidl", categoryId = groceries.id), threshold = 1
        )
        coVerify(exactly = 1) {
            remapRuleDao.upsert(match { rule ->
                !rule.enabled &&
                    RemapRuleJson.decode(rule.matchJson) == mapOf("category" to "transport") &&
                    RemapRuleJson.decode(rule.setJson) == mapOf("categoryId" to groceries.id.toString())
            })
        }
    }

    @Test
    fun `an existing rule for the trigger suppresses the proposal`() = runTest {
        coEvery { remapRuleDao.getByMatch(any()) } returns RemapRuleEntity(
            id = 1, name = "existing", matchJson = RemapRuleJson.encode(mapOf("vendor" to "lazar ionut pfa")),
            setJson = "{}", origin = RemapRuleEntity.ORIGIN_USER, updatedAt = 0L
        )
        repository.recordRemapPatternSightings(details(1, "LAZAR IONUT PFA"), edited(1, "Lazar's Shop"), threshold = 1)

        coVerify(exactly = 0) { remapRuleDao.upsert(any()) }
        assertTrue(sightingDao.rows.isEmpty())
    }

    @Test
    fun `filling an empty field or clearing one is not a rename`() = runTest {
        repository.recordRemapPatternSightings(details(1, null), edited(1, "Lidl"), threshold = 1)
        repository.recordRemapPatternSightings(details(2, "Lidl"), edited(2, null), threshold = 1)
        assertTrue(sightingDao.rows.isEmpty())
        coVerify(exactly = 0) { remapRuleDao.upsert(any()) }
    }
}
