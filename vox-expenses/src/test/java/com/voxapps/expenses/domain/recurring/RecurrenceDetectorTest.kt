package com.voxapps.expenses.domain.recurring

import com.voxapps.expenses.data.RecurrenceFrequency
import com.voxapps.expenses.data.RecurringPayment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * Date arithmetic is the kind of thing that is quietly wrong for a fortnight before anyone notices,
 * and here being wrong means either inventing a payment or missing one. Every judgement the feature
 * makes is reachable from here without a device.
 */
class RecurrenceDetectorTest {

    private fun at(year: Int, month: Int, day: Int): Long =
        Calendar.getInstance().apply {
            clear(); set(year, month - 1, day)
        }.timeInMillis

    // --- what counts as one interval apart ---

    @Test
    fun `two bills a month apart are one interval`() {
        assertTrue(RecurrenceDetector.looksLikeOneInterval(at(2026, 1, 15), at(2026, 2, 15)))
    }

    /** Bills slip. A direct debit due on a Sunday is taken on the Monday. */
    @Test
    fun `a bill that slipped by a few days is still the same bill`() {
        assertTrue(RecurrenceDetector.looksLikeOneInterval(at(2026, 1, 15), at(2026, 2, 18)))
        assertTrue(RecurrenceDetector.looksLikeOneInterval(at(2026, 1, 15), at(2026, 2, 12)))
    }

    /** Two shopping trips in one month are not a subscription. */
    @Test
    fun `two payments in the same fortnight are not a recurrence`() {
        assertFalse(RecurrenceDetector.looksLikeOneInterval(at(2026, 1, 3), at(2026, 1, 17)))
    }

    /** Nor are two visits a season apart. */
    @Test
    fun `payments too far apart are not one monthly interval`() {
        assertFalse(RecurrenceDetector.looksLikeOneInterval(at(2026, 1, 15), at(2026, 4, 15)))
    }

    @Test
    fun `a quarterly bill is one interval when the interval says three`() {
        assertTrue(
            RecurrenceDetector.looksLikeOneInterval(
                at(2026, 1, 15), at(2026, 4, 15), RecurrenceFrequency.MONTHLY, interval = 3
            )
        )
    }

    @Test
    fun `order matters — the later payment has to be later`() {
        assertFalse(RecurrenceDetector.looksLikeOneInterval(at(2026, 2, 15), at(2026, 1, 15)))
    }

    // --- when the next one is expected ---

    private fun monthly(day: Int, lastSeen: Long) = RecurringPayment(
        vendorKey = "digi", vendorLabel = "Digi", dueDayOfMonth = day,
        lastSeenAt = lastSeen, occurrences = 2, confirmedAt = 1L
    )

    @Test
    fun `the next due date is one month on, on the same day`() {
        val next = RecurrenceDetector.nextDueAfter(monthly(15, at(2026, 1, 15)), at(2026, 1, 20))
        val cal = Calendar.getInstance().apply { timeInMillis = next }
        assertEquals(Calendar.FEBRUARY, cal.get(Calendar.MONTH))
        assertEquals(15, cal.get(Calendar.DAY_OF_MONTH))
    }

    /**
     * The 31st in a 30-day month is the 30th, not the 1st of the next one. Spilling forward would
     * file the bill under a month it does not belong to and make both months' totals wrong.
     */
    @Test
    fun `a bill due on the 31st lands on the last day of a shorter month`() {
        val next = RecurrenceDetector.nextDueAfter(monthly(31, at(2026, 1, 31)), at(2026, 2, 1))
        val cal = Calendar.getInstance().apply { timeInMillis = next }
        assertEquals(Calendar.FEBRUARY, cal.get(Calendar.MONTH))
        assertEquals(28, cal.get(Calendar.DAY_OF_MONTH))
    }

    /** And clamping must not stick: March gets its 31st back. */
    @Test
    fun `clamping is per month, not permanent`() {
        val afterFeb = RecurrenceDetector.nextDueAfter(monthly(31, at(2026, 2, 28)), at(2026, 3, 1))
        val cal = Calendar.getInstance().apply { timeInMillis = afterFeb }
        assertEquals(Calendar.MARCH, cal.get(Calendar.MONTH))
        assertEquals(31, cal.get(Calendar.DAY_OF_MONTH))
    }

    /** A payment noticed late in its cycle must not predict a date already gone. */
    @Test
    fun `the next due date is always in the future`() {
        val from = at(2026, 5, 20)
        val next = RecurrenceDetector.nextDueAfter(monthly(15, at(2026, 1, 15)), from)
        assertTrue("predicted $next which is not after $from", next > from)
    }

    // --- overdue, and the grace before it ---

    @Test
    fun `a payment is not overdue the moment its day arrives`() {
        val due = at(2026, 3, 10)
        assertFalse(RecurrenceDetector.isOverdue(due, due))
        assertFalse(RecurrenceDetector.isOverdue(due, due + TimeUnit.DAYS.toMillis(2)))
    }

    @Test
    fun `past the grace, it is overdue`() {
        val due = at(2026, 3, 10)
        assertTrue(RecurrenceDetector.isOverdue(due, due + TimeUnit.DAYS.toMillis(6)))
    }

    // --- how many due dates went by unpaid ---

    @Test
    fun `a due date that has only just arrived has not been missed`() {
        val payment = monthly(15, at(2026, 1, 15))
        assertEquals(0, RecurrenceDetector.missedCyclesSince(payment, at(2026, 2, 16)))
    }

    @Test
    fun `past the grace, one due date has been missed`() {
        val payment = monthly(15, at(2026, 1, 15))
        assertEquals(1, RecurrenceDetector.missedCyclesSince(payment, at(2026, 2, 25)))
    }

    /**
     * The count comes from the dates, so a phone that was off for a season still reports the season.
     * A running tally kept by a daily job would report whatever days it happened to be awake for.
     */
    @Test
    fun `months of silence count as months, not as however often anything checked`() {
        val payment = monthly(15, at(2026, 1, 15))
        assertEquals(4, RecurrenceDetector.missedCyclesSince(payment, at(2026, 5, 25)))
    }

    @Test
    fun `asking twice on different days gives the same answer`() {
        val payment = monthly(15, at(2026, 1, 15))
        assertEquals(
            RecurrenceDetector.missedCyclesSince(payment, at(2026, 3, 25)),
            RecurrenceDetector.missedCyclesSince(payment, at(2026, 3, 28))
        )
    }

    // --- identity ---

    @Test
    fun `one vendor spelled two ways is one payment`() {
        assertEquals(
            RecurringPayment.vendorKeyOf("Digi"),
            RecurringPayment.vendorKeyOf("  digi ")
        )
    }

    @Test
    fun `no vendor is no identity`() {
        assertEquals(null, RecurringPayment.vendorKeyOf(null))
        assertEquals(null, RecurringPayment.vendorKeyOf("   "))
    }

    /** An observation predicts nothing until a person says it recurs. */
    @Test
    fun `an unconfirmed payment is not confirmed`() {
        assertFalse(monthly(15, at(2026, 1, 15)).copy(confirmedAt = null).confirmed)
        assertTrue(monthly(15, at(2026, 1, 15)).confirmed)
    }
}
