package com.voxapps.expenses.domain.llm

import com.voxapps.expenses.data.preferences.ExpensesSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.concurrent.TimeUnit

class ExpenseDeduplicationSchedulerTest {

    @Test
    fun `hourly resolves to one hour`() {
        assertEquals(TimeUnit.HOURS.toMillis(1), ExpenseDeduplicationScheduler.periodMillisFor(ExpensesSettings.INTERVAL_HOURLY))
    }

    @Test
    fun `daily resolves to one day`() {
        assertEquals(TimeUnit.DAYS.toMillis(1), ExpenseDeduplicationScheduler.periodMillisFor(ExpensesSettings.INTERVAL_DAILY))
    }

    @Test
    fun `weekly resolves to seven days`() {
        assertEquals(TimeUnit.DAYS.toMillis(7), ExpenseDeduplicationScheduler.periodMillisFor(ExpensesSettings.INTERVAL_WEEKLY))
    }

    @Test
    fun `monthly resolves to thirty days`() {
        assertEquals(TimeUnit.DAYS.toMillis(30), ExpenseDeduplicationScheduler.periodMillisFor(ExpensesSettings.INTERVAL_MONTHLY))
    }

    @Test
    fun `off resolves to no period (cancels scheduling)`() {
        assertNull(ExpenseDeduplicationScheduler.periodMillisFor(ExpensesSettings.INTERVAL_OFF))
    }

    @Test
    fun `unrecognized interval resolves to no period`() {
        assertNull(ExpenseDeduplicationScheduler.periodMillisFor("nonsense"))
    }
}
