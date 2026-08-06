package com.voxapps.expenses.data

/** Shared constants for the generic per-expense attachments feature (see :core:attachments) — kept
 *  in one place since the dir name/authority/record-type tag need to match exactly wherever they're
 *  used. The original scan photo's file still lives in its own `filesDir/receipts/` dir (not [DIR],
 *  which is manually-added attachments only) and its filename is still cached on
 *  `Expense.receiptImageName` for cheap reads — but its row in the shared `attachments` table
 *  (tagged [com.voxapps.attachments.AttachmentSource.SCANNED]) is what [DIR]-based deletion logic
 *  keys off of for reference-counted cleanup. See [ExpensesRepository.deleteAttachmentsFor]. */
object ExpensesAttachments {
    const val DIR = "attachments"
    const val FILE_PROVIDER_AUTHORITY = "com.voxapps.expenses.fileprovider"
    const val RECORD_TYPE = "expense"
}
