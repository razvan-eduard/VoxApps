package com.voxapps.expenses.domain.names

/**
 * What the characters settled, whatever carried them.
 *
 * One rule, stated once: a field proved by reading the text is never asked of a model — not on the
 * message route, not on the page route. Asking anyway costs a key the model can only echo, a key it
 * can get wrong, and a longer prompt for an engine that may not hold one.
 *
 * A notification and a scanned page prove different things — a page has a printed date and a
 * letterhead, a message has a template a person already classified — but *which fields exist* and
 * *what suppressing one means* are the same on both sides. This is that vocabulary, so the two
 * prompt builders stop each keeping their own.
 *
 * Every field is null when the reading established nothing, which is the only thing that puts a
 * question back into a prompt.
 */
data class ProvedFields(
    val amount: Double? = null,
    val currency: String? = null,
    val vendor: String? = null,
    val bank: String? = null,
    val date: String? = null,
    val time: String? = null,
    /** "outgoing"/"incoming", where a person already classified this shape. */
    val direction: String? = null,
    /** Whether it is even a payment is settled too — see the template memory. */
    val knownPayment: Boolean = false
) {
    fun proves(field: String?): Boolean = field != null
}
