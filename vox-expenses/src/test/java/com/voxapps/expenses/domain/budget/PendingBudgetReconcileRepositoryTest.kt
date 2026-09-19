package com.voxapps.expenses.domain.budget

import org.junit.Assert.assertEquals
import org.junit.Test

class PendingBudgetReconcileRepositoryTest {

    private fun entry(
        accountId: Long = 1L,
        currencyCode: String = "RON",
        remaining: Double = 850.0,
        statedAt: Long = 1_000L
    ) = PendingBudgetReconcile(accountId, currencyCode, remaining, statedAt)

    @Test
    fun `a repeat suggestion for the same account and currency replaces rather than appends`() {
        val first = entry(remaining = 850.0, statedAt = 1_000L)
        val second = entry(remaining = 720.0, statedAt = 2_000L)

        val merged = mergeByAccountKey(listOf(first), second)

        assertEquals(1, merged.size)
        assertEquals(720.0, merged[0].remaining, 0.0)
        assertEquals(2_000L, merged[0].statedAt)
    }

    @Test
    fun `an older statement never clobbers a newer pending one`() {
        val newer = entry(remaining = 720.0, statedAt = 2_000L)
        val stale = entry(remaining = 999.0, statedAt = 500L)

        val merged = mergeByAccountKey(listOf(newer), stale)

        assertEquals(1, merged.size)
        assertEquals(720.0, merged[0].remaining, 0.0)
        assertEquals(2_000L, merged[0].statedAt)
    }

    @Test
    fun `different accounts never merge`() {
        val a = entry(accountId = 1L)
        val b = entry(accountId = 2L)

        val merged = mergeByAccountKey(listOf(a), b)

        assertEquals(2, merged.size)
        assertEquals(listOf(1L, 2L), merged.map { it.accountId })
    }

    @Test
    fun `different currencies on the same account never merge`() {
        val ron = entry(accountId = 1L, currencyCode = "RON")
        val eur = entry(accountId = 1L, currencyCode = "EUR")

        val merged = mergeByAccountKey(listOf(ron), eur)

        assertEquals(2, merged.size)
        assertEquals(setOf("RON", "EUR"), merged.map { it.currencyCode }.toSet())
    }

    @Test
    fun `currency code matching is case-insensitive`() {
        val lower = entry(currencyCode = "ron", statedAt = 1_000L)
        val upper = entry(currencyCode = "RON", statedAt = 2_000L)

        val merged = mergeByAccountKey(listOf(lower), upper)

        assertEquals(1, merged.size)
    }
}
