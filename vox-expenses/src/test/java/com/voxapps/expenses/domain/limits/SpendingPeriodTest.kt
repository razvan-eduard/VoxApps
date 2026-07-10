package com.voxapps.expenses.domain.limits

import com.voxapps.expenses.data.SpendingLimit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class SpendingPeriodTest {

    // Wednesday 2026-07-15
    private val wednesday = LocalDate.of(2026, 7, 15)

    @Test
    fun `weekly window starts on the previous or same Monday`() {
        val startMillis = SpendingPeriod.windowStartMillis(SpendingLimit.PERIOD_WEEKLY, wednesday)
        val startDate = java.time.Instant.ofEpochMilli(startMillis).atZone(ZoneId.systemDefault()).toLocalDate()
        assertEquals(LocalDate.of(2026, 7, 13), startDate) // the Monday of that week
    }

    @Test
    fun `monthly window starts on the 1st of the month`() {
        val startMillis = SpendingPeriod.windowStartMillis(SpendingLimit.PERIOD_MONTHLY, wednesday)
        val startDate = java.time.Instant.ofEpochMilli(startMillis).atZone(ZoneId.systemDefault()).toLocalDate()
        assertEquals(LocalDate.of(2026, 7, 1), startDate)
    }

    @Test
    fun `weekly period key changes across a week boundary`() {
        val thisWeek = SpendingPeriod.periodKey(SpendingLimit.PERIOD_WEEKLY, wednesday)
        val nextWeek = SpendingPeriod.periodKey(SpendingLimit.PERIOD_WEEKLY, wednesday.plusWeeks(1))
        assertTrue(thisWeek != nextWeek)
    }

    @Test
    fun `monthly period key is stable within the same month`() {
        val key1 = SpendingPeriod.periodKey(SpendingLimit.PERIOD_MONTHLY, LocalDate.of(2026, 7, 1))
        val key2 = SpendingPeriod.periodKey(SpendingLimit.PERIOD_MONTHLY, LocalDate.of(2026, 7, 31))
        assertEquals(key1, key2)
    }

    @Test
    fun `monthly period key changes across a month boundary`() {
        val july = SpendingPeriod.periodKey(SpendingLimit.PERIOD_MONTHLY, LocalDate.of(2026, 7, 31))
        val august = SpendingPeriod.periodKey(SpendingLimit.PERIOD_MONTHLY, LocalDate.of(2026, 8, 1))
        assertTrue(july != august)
    }
}
