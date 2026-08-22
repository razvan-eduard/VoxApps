package com.voxapps.expenses.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.Calendar

/** How often a payment comes back. Mirrors the calendar's own vocabulary, minus the shapes money
 *  does not take — nothing is billed by weekday. */
enum class RecurrenceFrequency { MONTHLY, YEARLY }

/**
 * A payment that comes back.
 *
 * Two states, and the difference between them is who said so. A payment that has repeated is an
 * *observation*: the app noticed, and may propose. A payment that is [confirmed] is a *declaration*:
 * you said it recurs, and only then is anything predicted or scheduled. Nothing here writes an
 * expense on its own authority — inventing money that was never spent is the worst way a ledger can
 * be wrong, and it is worse than missing a reminder.
 *
 * **The amount is not part of the identity.** A subscription that goes up is the same subscription,
 * and a shop you happen to visit monthly for wildly different sums is not one. So [vendor] and the
 * interval say what this is, and [expectedAmount] is only what to show before the real figure
 * arrives — a deviation from it is worth pointing at, never worth suppressing.
 */
@Entity(tableName = "recurring_payments", indices = [Index("vendorKey")])
data class RecurringPayment(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    /** Normalized vendor — the identity, alongside the interval. */
    val vendorKey: String,
    /** As last seen, for showing. */
    val vendorLabel: String,

    val frequency: RecurrenceFrequency = RecurrenceFrequency.MONTHLY,
    /** Step between occurrences: 3 + MONTHLY is quarterly. */
    val interval: Int = 1,

    /**
     * The day of the month it lands on, as observed.
     *
     * A payment due on the 31st has no 31st in February. [dueDayFor] resolves that by clamping to
     * the month's own last day rather than spilling into March: a bill dated the 31st is a bill at
     * the end of the month, and moving it forward would place it in the wrong month entirely.
     */
    val dueDayOfMonth: Int,

    /** What the last one cost. Shown, never enforced. */
    val expectedAmount: Double? = null,
    val currency: String? = null,
    val categoryId: Long? = null,

    /** When the most recent real payment was seen — the anchor every prediction counts from. */
    val lastSeenAt: Long,

    /**
     * How many times it has actually been seen. Two is what makes it proposable; the threshold
     * itself is a setting, since how much coincidence a person will tolerate is a matter of taste.
     */
    val occurrences: Int = 1,

    /**
     * Consecutive due dates that came and went with no payment. Counts up to the same threshold that
     * proposed it, and then proposes dropping it — the evidence to stop believing something should
     * be the evidence it took to start.
     */
    val missedCycles: Int = 0,

    /** Null while this is only an observation; set when a person says it recurs. */
    val confirmedAt: Long? = null,

    /** The due date a reminder has already gone out for. Keyed to the date rather than counted, so a
     *  job that runs every day reminds you once per bill instead of once per day. */
    val notifiedForDueAt: Long? = null,

    /** Once dismissed, never proposed again — a person saying "this is not recurring" is an answer,
     *  and asking twice is not respecting it. */
    val dismissed: Boolean = false,

    val createdAt: Long = System.currentTimeMillis()
) {
    val confirmed: Boolean get() = confirmedAt != null

    companion object {
        /**
         * [dueDayOfMonth] as it falls in a given month, clamped to that month's length.
         *
         * The 31st in a 30-day month is the 30th, not the 1st of the next one. Anything else files a
         * bill under a month it does not belong to, and every total for both months is then wrong.
         */
        fun dueDayFor(dueDayOfMonth: Int, year: Int, zeroBasedMonth: Int): Int {
            val cal = Calendar.getInstance().apply {
                clear()
                set(Calendar.YEAR, year)
                set(Calendar.MONTH, zeroBasedMonth)
            }
            return minOf(dueDayOfMonth, cal.getActualMaximum(Calendar.DAY_OF_MONTH))
        }

        /** Normalizes a vendor for identity — the same rule the re-map engine matches by, so two
         *  spellings of one shop are one payment. */
        fun vendorKeyOf(vendor: String?): String? =
            vendor?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
    }
}
