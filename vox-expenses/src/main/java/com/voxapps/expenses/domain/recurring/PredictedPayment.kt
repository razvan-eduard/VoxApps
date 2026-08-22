package com.voxapps.expenses.domain.recurring

import com.voxapps.expenses.data.RecurringPayment

/**
 * A payment that is expected but has not arrived.
 *
 * Derived, never stored. It would have been easier to write a row and mark it somehow, and that is
 * exactly why it is not done: a predicted expense in the ledger is money nobody spent, and every
 * total, report and widget would then have to remember to leave it out. One forgotten exclusion and
 * the app is lying about how much was spent.
 *
 * Deriving it instead makes the awkward cases disappear rather than need handling. "When it arrives
 * it becomes a normal payment" needs no conversion — the real expense lands and the prediction stops
 * being produced, because the thing that produced it was its absence. Turning the feature off leaves
 * nothing to clean up. And a prediction can never be edited into something that outlives the
 * arrangement it came from.
 */
data class PredictedPayment(
    val payment: RecurringPayment,
    val dueAtMillis: Long,
    /** Past its grace with nothing seen — shown in the list as overdue rather than merely expected. */
    val overdue: Boolean
) {
    val vendorLabel: String get() = payment.vendorLabel
    val expectedAmount: Double? get() = payment.expectedAmount
    val currency: String? get() = payment.currency
}

/**
 * What is expected and has not turned up.
 *
 * [seenSinceCycleStart] answers, per payment id, whether a real expense has already landed for the
 * current cycle — the caller reads that from the ledger, since only it knows what "landed" means for
 * its own records. A payment that has been seen produces nothing: it arrived, and an arrival is an
 * expense, not a prediction.
 */
object PaymentPredictor {

    /**
     * How far ahead a prediction is worth showing.
     *
     * A month of futures in the list is a list about next month rather than this one. Five days is
     * enough to be a warning and short enough that what you see is still mostly what happened.
     */
    const val LOOK_AHEAD_DAYS = 5

    fun predict(
        confirmed: List<RecurringPayment>,
        nowMillis: Long,
        seenSinceCycleStart: (RecurringPayment) -> Boolean
    ): List<PredictedPayment> = confirmed
        .asSequence()
        .filter { it.confirmed && !it.dismissed }
        .filterNot(seenSinceCycleStart)
        .mapNotNull { payment ->
            val due = RecurrenceDetector.nextDueAfter(payment, payment.lastSeenAt)
            val lookAhead = nowMillis + LOOK_AHEAD_DAYS * 24L * 60 * 60 * 1000
            // Only what is imminent or already missed. A due date further out than the look-ahead is
            // a fact about the future, not something to put in a ledger of the present.
            if (due > lookAhead) null
            else PredictedPayment(payment, due, RecurrenceDetector.isOverdue(due, nowMillis))
        }
        .sortedBy { it.dueAtMillis }
        .toList()
}
