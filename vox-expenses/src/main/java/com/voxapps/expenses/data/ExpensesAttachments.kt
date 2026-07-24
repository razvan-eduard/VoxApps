package com.voxapps.expenses.data

/** Shared constants for the generic per-expense manual attachments feature (see :core:attachments)
 *  — kept in one place since the dir name/authority/record-type tag need to match exactly wherever
 *  they're used. The original scan photo stays in Expense.receiptImageName/filesDir/receipts/,
 *  unchanged — this dir is only for manually-added extra attachments. */
object ExpensesAttachments {
    const val DIR = "attachments"
    const val FILE_PROVIDER_AUTHORITY = "com.voxapps.expenses.fileprovider"
    const val RECORD_TYPE = "expense"
}
