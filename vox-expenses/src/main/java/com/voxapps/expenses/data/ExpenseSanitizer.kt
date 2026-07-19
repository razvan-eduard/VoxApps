package com.voxapps.expenses.data

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
        bank = FieldCleaner.clean(record.bank, "bank", recordLabel(record)),
        location = FieldCleaner.clean(record.location, "location", recordLabel(record)),
        comments = FieldCleaner.clean(record.comments, "comments", recordLabel(record))
    )

    override fun isDirty(record: Expense): Boolean =
        FieldCleaner.isDirty(record.title) ||
            FieldCleaner.isDirty(record.vendor) ||
            FieldCleaner.isDirty(record.bank) ||
            FieldCleaner.isDirty(record.location) ||
            FieldCleaner.isDirty(record.comments)

    private fun recordLabel(record: Expense) = "Expense#${record.id}"
}
