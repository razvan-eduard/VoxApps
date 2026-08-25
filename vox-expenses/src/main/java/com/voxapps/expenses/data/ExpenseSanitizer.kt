package com.voxapps.expenses.data

import com.voxapps.datahygiene.DirtyField
import com.voxapps.datahygiene.FieldCleaner
import com.voxapps.datahygiene.RecordSanitizer

/**
 * Cleans [Expense]'s LLM/user-derived nullable string fields. `receiptImageName`/`currencyCode`
 * are deliberately excluded — machine-derived (filesystem name, currency code), not free-text the
 * LLM or a user typed, so not at risk of the "null"/garbage-literal bug this exists to catch.
 */
object ExpenseSanitizer : RecordSanitizer<Expense> {
    override fun sanitize(record: Expense): Expense = record.copy(
        title = FieldCleaner.clean(record.title, "title", recordLabel(record)),
        vendor = FieldCleaner.clean(record.vendor, "vendor", recordLabel(record)),
        location = FieldCleaner.clean(record.location, "location", recordLabel(record)),
        comments = FieldCleaner.clean(record.comments, "comments", recordLabel(record))
    )

    override fun dirtyFields(record: Expense): List<DirtyField> = listOfNotNull(
        FieldCleaner.dirtyValue(record.title)?.let { DirtyField("title", it) },
        FieldCleaner.dirtyValue(record.vendor)?.let { DirtyField("vendor", it) },
        FieldCleaner.dirtyValue(record.location)?.let { DirtyField("location", it) },
        FieldCleaner.dirtyValue(record.comments)?.let { DirtyField("comments", it) }
    )

    private fun recordLabel(record: Expense) = "Expense#${record.id}"
}
