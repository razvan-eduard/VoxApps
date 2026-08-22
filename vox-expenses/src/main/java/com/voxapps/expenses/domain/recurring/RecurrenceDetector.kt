package com.voxapps.expenses.domain.recurring

import com.voxapps.expenses.data.RecurrenceFrequency
import com.voxapps.expenses.data.RecurringPayment
import java.util.Calendar
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Decides when payments to one vendor look like a standing arrangement, and when a confirmed one is
 * due.
 *
 * Kept free of Android and of the database on purpose: every judgement here is arithmetic over dates,
 * and arithmetic over dates is exactly the kind of thing that is wrong for a fortnight before anybody
 * notices. It is all reachable from a test with no device.
 */
object RecurrenceDetector {

    /**
     * How far from the expected day a payment may land and still be the same monthly bill.
     *
     * Bills move: a direct debit due on a Sunday is taken on the Monday, a card payment posts a day
     * late. Demanding the same date every month would find almost nothing; allowing a fortnight
     * would call two shopping trips a subscription. Four days is a working week's worth of slippage.
     */
    const val DAY_TOLERANCE = 4

    /** Below this, two payments are not far enough apart to be separate months at all. */
    private const val MIN_MONTHLY_GAP_DAYS = 20
    private const val MAX_MONTHLY_GAP_DAYS = 45

    /**
     * Whether [earlier] and [later] are one interval apart, within tolerance.
     *
     * Deliberately not "same day of the month": that is the thing being tested for, and using it as
     * the test would reject a bill that slipped by a day while accepting two visits eleven months
     * apart that happen to share a date.
     */
    fun looksLikeOneInterval(
        earlierMillis: Long,
        laterMillis: Long,
        frequency: RecurrenceFrequency = RecurrenceFrequency.MONTHLY,
        interval: Int = 1
    ): Boolean {
        if (laterMillis <= earlierMillis) return false
        val days = TimeUnit.MILLISECONDS.toDays(laterMillis - earlierMillis)
        return when (frequency) {
            RecurrenceFrequency.MONTHLY -> {
                val low = MIN_MONTHLY_GAP_DAYS.toLong() * interval
                val high = MAX_MONTHLY_GAP_DAYS.toLong() * interval
                days in low..high
            }
            RecurrenceFrequency.YEARLY ->
                abs(days - 365L * interval) <= DAY_TOLERANCE.toLong() + interval
        }
    }

    /**
     * When the next one is expected after [from], for a confirmed payment.
     *
     * The day is clamped to the target month rather than allowed to spill — see
     * [RecurringPayment.dueDayFor]. Returns a day-start; the hour a bill lands is not something this
     * knows or should pretend to.
     */
    fun nextDueAfter(payment: RecurringPayment, fromMillis: Long): Long {
        val cal = Calendar.getInstance().apply { timeInMillis = fromMillis }
        val step = when (payment.frequency) {
            RecurrenceFrequency.MONTHLY -> Calendar.MONTH
            RecurrenceFrequency.YEARLY -> Calendar.YEAR
        }
        // Start from the anchor's own month and walk forward until the result is actually after
        // `from` — a payment seen late in its own cycle must not predict a date already gone.
        cal.timeInMillis = payment.lastSeenAt
        do {
            cal.add(step, payment.interval)
            val day = RecurringPayment.dueDayFor(
                payment.dueDayOfMonth,
                cal.get(Calendar.YEAR),
                cal.get(Calendar.MONTH)
            )
            cal.set(Calendar.DAY_OF_MONTH, day)
            cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
        } while (cal.timeInMillis <= fromMillis)
        return cal.timeInMillis
    }

    /**
     * Whether a due date has been missed rather than merely arrived.
     *
     * The grace is the same tolerance a payment is allowed when it lands, and for the same reason:
     * a bill that is a day late is late, not absent. Turning a row red the moment the clock passes
     * midnight would cry wolf every month for payments that post the next morning.
     */
    fun isOverdue(dueAtMillis: Long, nowMillis: Long): Boolean =
        nowMillis > dueAtMillis + TimeUnit.DAYS.toMillis(DAY_TOLERANCE.toLong())

    /**
     * How many due dates have come and gone since the last real payment, as of [nowMillis].
     *
     * Counted from the dates rather than tallied as they pass. A running total kept by a daily job is
     * wrong twice over: it double-counts if the job runs twice, and it under-counts every day the
     * phone was off — so a bill that stopped while you were away would look like it was still being
     * paid. Recomputing from `lastSeenAt` gives the same answer whenever it is asked, however long
     * nothing asked.
     */
    fun missedCyclesSince(payment: RecurringPayment, nowMillis: Long): Int {
        var missed = 0
        var cursor = payment.lastSeenAt
        // Each pass takes the next due date after the previous one; a due date only counts once its
        // grace has run out, so the current cycle is not held against a bill that is merely due today.
        while (missed < MAX_COUNTED_MISSES) {
            val due = nextDueAfter(payment, cursor)
            if (!isOverdue(due, nowMillis)) break
            missed++
            cursor = due
        }
        return missed
    }

    /** Past this, the answer is the same — it stopped — and the loop should not walk years of dates. */
    private const val MAX_COUNTED_MISSES = 60

    /** The day of the month a payment seen at [millis] falls on. */
    fun dayOfMonth(millis: Long): Int =
        Calendar.getInstance().apply { timeInMillis = millis }.get(Calendar.DAY_OF_MONTH)
}
