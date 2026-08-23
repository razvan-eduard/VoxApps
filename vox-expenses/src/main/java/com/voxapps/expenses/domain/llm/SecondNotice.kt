package com.voxapps.expenses.domain.llm

import com.voxapps.expenses.data.Expense
import com.voxapps.expenses.data.ExpenseSource
import java.util.concurrent.TimeUnit

/**
 * One payment announced twice.
 *
 * A card scheme and the wallet carrying its card both notify, and a bank and its wallet do the same.
 * Nothing correlates them: the two messages arrive from different packages with different keys, so
 * each is read as an unrelated capture and each is judged worth filing. The second then reaches the
 * insert and is turned away by the duplicate check at the storage layer.
 *
 * That check is a backstop against writing the same row twice, and it was doing a job it is wrong
 * for. It matches on coincidence — a figure and a clock — and it fires only after a record has been
 * built and offered, so which of the two announcements survives is decided by which arrived first
 * rather than by which said more. The one carrying the merchant is as likely to be second as first.
 *
 * Correlating here instead means one record is written, the announcements are folded into it in
 * either order, and the storage-layer check goes back to being what it should be: something that
 * never fires in ordinary use.
 */
object SecondNotice {

    /**
     * How long after a capture another announcement of it may arrive.
     *
     * Both messages are emitted by the same authorisation, so they land within seconds; the window
     * only has to cover a phone that delivered one of them late. Minutes rather than hours because
     * the test is a figure and a currency, and two genuinely separate payments of the same amount —
     * the second coffee of the morning — must stay two records.
     */
    val WINDOW_MILLIS: Long = TimeUnit.MINUTES.toMillis(3)

    /**
     * Whether [candidate] is another announcement of [existing] rather than a second payment.
     *
     * Deliberately strict. The amount must match to the cent and the currency exactly: these are two
     * renderings of one authorisation, so the figure is the same figure, not a similar one. Anything
     * looser starts folding real repeat purchases into each other, and a payment silently absorbed
     * is worse than a duplicate a person can see and delete.
     */
    fun isAnotherNoticeOf(existing: Expense, candidate: Expense): Boolean {
        if (existing.source != ExpenseSource.NOTIFICATION) return false
        if (candidate.source != ExpenseSource.NOTIFICATION) return false
        if (existing.currencyCode != candidate.currencyCode) return false
        if (!sameToTheCent(existing.totalAmount, candidate.totalAmount)) return false
        // A record someone has edited is theirs; folding a later announcement into it would rewrite
        // fields they set by hand.
        if (existing.manuallyEdited) return false
        return kotlin.math.abs(existing.dateTime - candidate.dateTime) <= WINDOW_MILLIS
    }

    /** Two renderings of one figure, compared as the minor units they were printed in. */
    private fun sameToTheCent(a: Double, b: Double): Boolean =
        Math.round(a * 100) == Math.round(b * 100)
}
