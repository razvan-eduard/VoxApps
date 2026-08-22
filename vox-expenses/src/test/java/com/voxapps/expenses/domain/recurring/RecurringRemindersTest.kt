package com.voxapps.expenses.domain.recurring

import com.voxapps.expenses.data.RecurringPayment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/** When a reminder fires, and — mostly — when it does not. */
class RecurringRemindersTest {

    private fun at(year: Int, month: Int, day: Int): Long =
        Calendar.getInstance().apply { clear(); set(year, month - 1, day) }.timeInMillis

    /** Last paid 15 Jan 2026, so the next one is due 15 Feb. */
    private fun digi(
        confirmed: Boolean = true,
        notifiedFor: Long? = null,
        dismissed: Boolean = false
    ) = RecurringPayment(
        id = 1, vendorKey = "digi", vendorLabel = "Digi", dueDayOfMonth = 15,
        expectedAmount = 49.0, currency = "RON", lastSeenAt = at(2026, 1, 15), occurrences = 2,
        confirmedAt = if (confirmed) 1L else null, notifiedForDueAt = notifiedFor, dismissed = dismissed
    )

    @Test
    fun `a bill inside the notice window is reminded about`() {
        val due = RecurringReminders.due(listOf(digi()), at(2026, 2, 14))
        assertEquals(1, due.size)
        assertEquals(at(2026, 2, 15), due.first().dueAtMillis)
    }

    @Test
    fun `a bill still weeks away is not`() {
        assertTrue(RecurringReminders.due(listOf(digi()), at(2026, 2, 1)).isEmpty())
    }

    /** The job wakes every day; the bill does not become newly due each time it does. */
    @Test
    fun `a bill already reminded about is not reminded about again`() {
        val payment = digi(notifiedFor = at(2026, 2, 15))
        assertTrue(RecurringReminders.due(listOf(payment), at(2026, 2, 14)).isEmpty())
        assertTrue(RecurringReminders.due(listOf(payment), at(2026, 2, 15)).isEmpty())
    }

    /**
     * Past its grace the row is already red in the list. A "coming up" notice about a date that has
     * gone reads as the app having lost track of what day it is.
     */
    @Test
    fun `nothing is announced as upcoming once it is overdue`() {
        assertTrue(RecurringReminders.due(listOf(digi()), at(2026, 2, 25)).isEmpty())
    }

    @Test
    fun `an observation nobody confirmed reminds nobody`() {
        assertTrue(RecurringReminders.due(listOf(digi(confirmed = false)), at(2026, 2, 14)).isEmpty())
    }

    @Test
    fun `a dismissed arrangement reminds nobody`() {
        assertTrue(RecurringReminders.due(listOf(digi(dismissed = true)), at(2026, 2, 14)).isEmpty())
    }

    @Test
    fun `the soonest bill is named first`() {
        val early = digi().copy(id = 2, vendorKey = "orange", vendorLabel = "Orange", lastSeenAt = at(2026, 1, 13), dueDayOfMonth = 13)
        val due = RecurringReminders.due(listOf(digi(), early), at(2026, 2, 12), noticeDays = 5)
        assertEquals(listOf("Orange", "Digi"), due.map { it.payment.vendorLabel })
    }
}
