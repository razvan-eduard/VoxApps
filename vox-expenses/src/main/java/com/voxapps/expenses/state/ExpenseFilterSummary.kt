package com.voxapps.expenses.state

import com.voxapps.design.filter.VoxRange

/**
 * Which narrowings are in force, named, in a fixed order.
 *
 * The order is the point: a person reads this button many times a day, and a summary that reorders
 * itself as filters come and go makes them re-read it every time. Category first because it is the
 * broadest, then the three names, then the dates, then the sort — which comes last because it
 * changes what the list *shows first*, not what it contains.
 */
object ExpenseFilterSummary {

    /** Between the two ends of a range. An en dash, which is what a range is written with. */
    private const val TO = " – "

    fun parts(
        category: String?,
        bank: FilterValue?,
        vendor: FilterValue?,
        location: FilterValue?,
        amount: VoxRange?,
        account: String?,
        currency: String?,
        dateFrom: Long?,
        dateTo: Long?,
        sort: SortMode,
        formatDate: (Long) -> String,
        formatAmount: (Double) -> String,
        sortLabel: (SortMode) -> String
    ): List<String?> = listOf(
        category?.takeIf { it.isNotBlank() },
        bank?.text?.takeIf { it.isNotBlank() },
        vendor?.text?.takeIf { it.isNotBlank() },
        location?.text?.takeIf { it.isNotBlank() },
        amount?.let { amountLabel(it, formatAmount) },
        // One of these at most: an account holds one currency, so the two questions overlap and
        // the state manager lets go of one when the other is chosen.
        account?.takeIf { it.isNotBlank() },
        currency?.takeIf { it.isNotBlank() },
        dateLabel(dateFrom, dateTo, formatDate),
        // Newest-first is the list's resting state rather than a choice somebody made, so naming it
        // would put a word in the summary that is true of every unfiltered list too.
        sort.takeIf { it != SortMode.NEWEST }?.let(sortLabel)
    )

    /**
     * A range, an open end, or nothing.
     *
     * One end set is a real narrowing and has to say so — "from the 3rd" excludes as much as any
     * range does, and a summary that stayed silent about it would be claiming the list is whole.
     */
    fun dateLabel(from: Long?, to: Long?, formatDate: (Long) -> String): String? = when {
        from != null && to != null && from == to -> formatDate(from)
        from != null && to != null -> formatDate(from) + TO + formatDate(to)
        from != null -> formatDate(from) + TO
        to != null -> TO + formatDate(to)
        else -> null
    }

    /**
     * A bracket, written the way its chip is written, so the button and the sheet agree.
     *
     * An open upper end reads as "500+" rather than as a range to infinity: a person asking for
     * everything above a figure has not named a second one, and printing one they did not choose
     * would be answering a different question on their behalf.
     */
    fun amountLabel(range: VoxRange, formatAmount: (Double) -> String): String =
        if (range.to.isFinite()) formatAmount(range.from) + TO + formatAmount(range.to)
        else formatAmount(range.from) + ABOVE

    private const val ABOVE = "+"

    /** Whether anything at all is narrowing the list — what decides if a clear is offered. */
    fun anyActive(
        category: Long?,
        bank: FilterValue?,
        vendor: FilterValue?,
        location: FilterValue?,
        amount: VoxRange?,
        accountId: Long?,
        currency: String?,
        dateFrom: Long?,
        dateTo: Long?,
        sort: SortMode
    ): Boolean = category != null || bank != null || vendor != null || location != null ||
        amount != null || accountId != null || currency != null ||
        dateFrom != null || dateTo != null || sort != SortMode.NEWEST
}
