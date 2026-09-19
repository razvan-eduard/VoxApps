package com.voxapps.expenses.domain.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PendingNotificationExpenseRepositoryTest {

    private fun entry(
        id: Long,
        sourceKey: String? = "key",
        totalAmount: Double? = 10.0,
        vendor: String? = "Shop"
    ) = PendingNotificationExpense(
        id = id,
        title = vendor,
        totalAmount = totalAmount,
        currency = "RON",
        vendor = vendor,
        category = null,
        capturedAt = 1_000L,
        sourceKey = sourceKey
    )

    @Test
    fun `a repeat capture of the same notification does not grow the list`() {
        // Reproduces the reported bug: the same still-shade notification, handed to addPending
        // again on every listener reconnect or force-check, appended a fresh row every time.
        val first = entry(id = 1L)
        val second = entry(id = 2L)

        val afterFirst = mergeBySourceKey(emptyList(), first)
        val afterSecond = mergeBySourceKey(afterFirst, second)

        assertEquals(1, afterSecond.size)
    }

    @Test
    fun `a better second capture replaces the row but keeps its id and capturedAt`() {
        val existing = entry(id = 1L, totalAmount = null, vendor = null).copy(capturedAt = 500L)
        val better = entry(id = 2L, totalAmount = 19.83, vendor = "Shop").copy(capturedAt = 999L)

        val merged = mergeBySourceKey(listOf(existing), better)

        assertEquals(1, merged.size)
        assertEquals(1L, merged[0].id)
        assertEquals(500L, merged[0].capturedAt)
        assertEquals(19.83, merged[0].totalAmount!!, 0.0)
        assertEquals("Shop", merged[0].vendor)
    }

    @Test
    fun `a second capture with no amount never overwrites one already resolved`() {
        val existing = entry(id = 1L, totalAmount = 19.83)
        val regressed = entry(id = 2L, totalAmount = null)

        val merged = mergeBySourceKey(listOf(existing), regressed)

        assertEquals(1, merged.size)
        assertEquals(19.83, merged[0].totalAmount!!, 0.0)
    }

    @Test
    fun `two different notifications never merge`() {
        val a = entry(id = 1L, sourceKey = "key-a")
        val b = entry(id = 2L, sourceKey = "key-b")

        val merged = mergeBySourceKey(listOf(a), b)

        assertEquals(2, merged.size)
        assertEquals(listOf(1L, 2L), merged.map { it.id })
    }

    @Test
    fun `a null source key always appends, even against other null-keyed entries`() {
        val legacy = entry(id = 1L, sourceKey = null)
        val alsoLegacy = entry(id = 2L, sourceKey = null)

        val merged = mergeBySourceKey(listOf(legacy), alsoLegacy)

        assertEquals(2, merged.size)
        assertNull(merged[0].sourceKey)
        assertNull(merged[1].sourceKey)
    }
}
