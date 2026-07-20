package com.voxapps.expenses.data

import com.voxapps.calendar.CalendarDateUtils
import com.voxapps.datahygiene.DuplicateChecker
import com.voxapps.datahygiene.FieldCleaner

/**
 * An expense duplicates another when every user-meaningful field matches after normalization —
 * title/vendor/bank/location/comments go through [FieldCleaner] so e.g. a literal "null" string and
 * a genuinely blank field count as equal, exactly like [com.voxapps.datahygiene.RecordSanitizer]
 * treats them for cleaning. [Expense.id], [Expense.createdAt], [Expense.receiptImageName], and
 * [Expense.isStub] are identity/audit fields, deliberately excluded — two records can be the same
 * real-world expense even if one is a stub-then-retried version of the other's photo, or was
 * inserted milliseconds apart.
 *
 * [Expense.dateTime] is compared by calendar day, not exact millis, for that same reason: a
 * notification-sourced expense stamps `dateTime` with the capture instant
 * ([com.voxapps.expenses.receiver.LlmResultReceiver]'s `System.currentTimeMillis()`), which is never
 * the same twice — an exact-millis match would make this checker a no-op for that whole source,
 * exactly the case a "force re-check" retry of the same notification needs to catch.
 */
object ExpenseDuplicateChecker : DuplicateChecker<Expense> {
    override fun isDuplicateOf(candidate: Expense, existing: Expense): Boolean =
        FieldCleaner.clean(candidate.title) == FieldCleaner.clean(existing.title) &&
            candidate.totalAmount == existing.totalAmount &&
            candidate.currencyCode == existing.currencyCode &&
            FieldCleaner.clean(candidate.vendor) == FieldCleaner.clean(existing.vendor) &&
            FieldCleaner.clean(candidate.bank) == FieldCleaner.clean(existing.bank) &&
            FieldCleaner.clean(candidate.location) == FieldCleaner.clean(existing.location) &&
            CalendarDateUtils.millisToLocalDate(candidate.dateTime) == CalendarDateUtils.millisToLocalDate(existing.dateTime) &&
            FieldCleaner.clean(candidate.comments) == FieldCleaner.clean(existing.comments) &&
            candidate.categoryId == existing.categoryId
}
