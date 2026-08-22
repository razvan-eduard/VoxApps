package com.voxapps.expenses.domain.recurring

import com.voxapps.expenses.data.RecurrenceFrequency
import com.voxapps.expenses.data.RecurringPayment
import com.voxapps.expenses.data.RecurringPaymentDao
import com.voxapps.logging.Logger
import kotlinx.coroutines.flow.Flow

private const val TAG = "RecurringPayments"

/**
 * Notices that payments to a vendor keep coming back, and remembers what a person decided about it.
 *
 * The whole of it is an observation until somebody confirms: [observe] counts, [propose] answers
 * whether it has counted enough, and only [confirm] turns it into something that predicts. Nothing
 * here writes an expense, and nothing here confirms on the app's own authority — the app noticing a
 * pattern is not the same as the pattern being real, and money is the wrong place to blur that.
 */
class RecurringPaymentRepository(private val dao: RecurringPaymentDao) {

    val all: Flow<List<RecurringPayment>> = dao.observeAll()

    /**
     * Records that a payment to [vendor] landed at [atMillis].
     *
     * Counted only when it falls one interval after the last one — see
     * [RecurrenceDetector.looksLikeOneInterval]. A second payment in the same fortnight is a second
     * payment, not a second occurrence, and counting it would call every frequented shop a
     * subscription. A payment that fits no known rhythm starts its own record with one sighting,
     * which costs a row and buys the chance to notice next month.
     *
     * Returns the record as it now stands, or null when there is nothing to key on.
     */
    suspend fun observe(
        vendor: String?,
        atMillis: Long,
        amount: Double?,
        currency: String?,
        categoryId: Long?
    ): RecurringPayment? {
        val key = RecurringPayment.vendorKeyOf(vendor) ?: return null
        val label = vendor?.trim().orEmpty()
        val existing = dao.find(key, RecurrenceFrequency.MONTHLY)

        if (existing == null) {
            val fresh = RecurringPayment(
                vendorKey = key,
                vendorLabel = label,
                dueDayOfMonth = RecurrenceDetector.dayOfMonth(atMillis),
                expectedAmount = amount,
                currency = currency,
                categoryId = categoryId,
                lastSeenAt = atMillis,
                occurrences = 1
            )
            return fresh.copy(id = dao.insert(fresh))
        }

        // A payment already confirmed has arrived: the cycle resets and any missed run is over.
        // Otherwise it only counts if it sits where the rhythm says it should.
        val onRhythm = RecurrenceDetector.looksLikeOneInterval(
            existing.lastSeenAt, atMillis, existing.frequency, existing.interval
        )
        if (!onRhythm && !existing.confirmed) {
            // Out of rhythm and unconfirmed: treat it as the new anchor rather than evidence. Two
            // unrelated visits should not accumulate into a claim.
            val reanchored = existing.copy(
                vendorLabel = label.ifEmpty { existing.vendorLabel },
                dueDayOfMonth = RecurrenceDetector.dayOfMonth(atMillis),
                lastSeenAt = atMillis,
                expectedAmount = amount ?: existing.expectedAmount,
                occurrences = 1
            )
            dao.update(reanchored)
            return reanchored
        }

        val updated = existing.copy(
            vendorLabel = label.ifEmpty { existing.vendorLabel },
            lastSeenAt = atMillis,
            // The amount is not identity, but the latest one is the best guess at the next one.
            expectedAmount = amount ?: existing.expectedAmount,
            currency = currency ?: existing.currency,
            categoryId = categoryId ?: existing.categoryId,
            occurrences = if (onRhythm) existing.occurrences + 1 else existing.occurrences,
            missedCycles = 0
        )
        dao.update(updated)
        if (onRhythm) Logger.d(TAG, "${updated.vendorLabel}: seen ${updated.occurrences} time(s) on rhythm")
        return updated
    }

    /**
     * The observations worth putting to a person, given [threshold].
     *
     * A threshold of zero is not "propose everything" — it is the off switch, and reading it as
     * anything else would turn a preference to be left alone into a stream of suggestions.
     */
    suspend fun proposals(threshold: Int): List<RecurringPayment> {
        if (threshold <= 0) return emptyList()
        return dao.observeAllOnce().filter { !it.confirmed && !it.dismissed && it.occurrences >= threshold }
    }

    /** The arrangements a person has confirmed — the only ones that predict or remind. */
    suspend fun confirmedArrangements(): List<RecurringPayment> = dao.confirmed()

    /** Every row for backup, dismissals included — see [RecurringPaymentDao.allRows]. */
    suspend fun snapshot(): List<RecurringPayment> = dao.allRows()

    /** Puts a backed-up row back as it was, keeping nothing of the old id. */
    suspend fun restore(payment: RecurringPayment): Long = dao.insert(payment.copy(id = 0))

    suspend fun delete(payment: RecurringPayment) = dao.delete(payment)

    /** A person said it recurs. From here on it predicts, and only from here on. */
    suspend fun confirm(id: Long, frequency: RecurrenceFrequency? = null, interval: Int? = null) {
        val existing = dao.byId(id) ?: return
        dao.update(
            existing.copy(
                confirmedAt = System.currentTimeMillis(),
                frequency = frequency ?: existing.frequency,
                interval = interval ?: existing.interval,
                missedCycles = 0
            )
        )
        Logger.d(TAG, "${existing.vendorLabel} confirmed as recurring")
    }

    /** A person said it does not. Never proposed again — asking twice is not taking the answer. */
    suspend fun dismiss(id: Long) {
        dao.byId(id)?.let { dao.update(it.copy(dismissed = true, confirmedAt = null)) }
    }

    /**
     * Brings every arrangement's count of missed due dates up to date as of [nowMillis].
     *
     * Recomputed from the dates, not tallied as they pass — see
     * [RecurrenceDetector.missedCyclesSince]. Nothing is removed here whatever the count reaches: at
     * the threshold the arrangement is put back to the person as "this seems to have stopped", and
     * that symmetry is the point. It should take as much to stop believing as it took to start.
     */
    suspend fun refreshMissedCycles(nowMillis: Long) {
        dao.confirmed().forEach { payment ->
            val missed = RecurrenceDetector.missedCyclesSince(payment, nowMillis)
            if (missed != payment.missedCycles) {
                dao.update(payment.copy(missedCycles = missed))
                Logger.d(TAG, "${payment.vendorLabel}: $missed due date(s) passed unpaid")
            }
        }
    }

    /** Remembers that the reminder for [dueAtMillis] has gone out, so the next daily pass stays quiet. */
    suspend fun markReminded(id: Long, dueAtMillis: Long) {
        dao.byId(id)?.let { dao.update(it.copy(notifiedForDueAt = dueAtMillis)) }
    }

    /** Confirmed arrangements that have missed enough cycles to be worth questioning. */
    suspend fun stale(threshold: Int): List<RecurringPayment> {
        if (threshold <= 0) return emptyList()
        return dao.confirmed().filter { it.missedCycles >= threshold }
    }
}
