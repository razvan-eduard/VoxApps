package com.voxapps.expenses.domain.recurring

import com.voxapps.expenses.data.RecurringPayment
import java.util.concurrent.TimeUnit

/** A bill about to fall due, and the date it falls due on. */
data class DueReminder(val payment: RecurringPayment, val dueAtMillis: Long)

/**
 * Which confirmed arrangements are worth a reminder today.
 *
 * Separate from the job that posts them so the decision can be tested at a date of the test's
 * choosing — a reminder that fires on the wrong day is not something to find out about by waiting
 * a month.
 */
object RecurringReminders {

    /**
     * How much warning a bill gets.
     *
     * Two days is enough to move money and short enough to still be about this bill. A week's notice
     * is a notification you learn to swipe away, which is worse than none — by the time the useful
     * one arrives the habit is already to dismiss it.
     */
    const val NOTICE_DAYS = 2

    fun due(confirmed: List<RecurringPayment>, nowMillis: Long, noticeDays: Int = NOTICE_DAYS): List<DueReminder> =
        confirmed
            .asSequence()
            .filter { it.confirmed && !it.dismissed }
            .mapNotNull { payment ->
                val due = RecurrenceDetector.nextDueAfter(payment, payment.lastSeenAt)
                val window = nowMillis + TimeUnit.DAYS.toMillis(noticeDays.toLong())
                when {
                    due > window -> null
                    // Keyed to the due date, so the daily pass reminds once per bill rather than once
                    // per day it wakes up and finds the same bill still unpaid.
                    payment.notifiedForDueAt == due -> null
                    // Nothing to warn about after the fact: once the grace has run out the row is
                    // already red in the list, and a "coming up" notice about a date gone by reads as
                    // the app having lost track of what day it is.
                    RecurrenceDetector.isOverdue(due, nowMillis) -> null
                    else -> DueReminder(payment, due)
                }
            }
            .sortedBy { it.dueAtMillis }
            .toList()
}
