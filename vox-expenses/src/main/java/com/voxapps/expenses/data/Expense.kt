package com.voxapps.expenses.data

import com.voxapps.docread.ReceiptTotalRegexParser
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/** Whether money left the account (a purchase/payment/transfer sent) or arrived (a refund, an
 *  incoming transfer, or a top-up) — every record is one or the other, defaulting to [OUTGOING]
 *  since that's overwhelmingly the common case for an expense tracker. */
enum class TransactionDirection { OUTGOING, INCOMING }

/**
 * A single expense. [totalAmount] is the only mandatory field.
 * [receiptImageName] stores the filename of the receipt photo in internal storage (filesDir/
 * receipts/) — kept here as a denormalized pointer for cheap reads (thumbnails, edit screen,
 * export/import, P2P sync), but the file's actual lifetime is owned by a corresponding
 * [com.voxapps.attachments.AttachmentEntity] row (recordType = [ExpensesAttachments.RECORD_TYPE],
 * source = [com.voxapps.attachments.AttachmentSource.SCANNED]) inserted alongside this field
 * everywhere it's set — deletion goes through that row's reference-counted cleanup
 * ([ExpensesRepository.deleteAttachmentsFor]), the same guarded path every other attachment uses,
 * rather than a bespoke column-based check.
 * [isStub] marks a record created because the LLM failed to parse a scanned receipt — the photo
 * (and its sibling raw-OCR-text file) were kept so the user can retry or edit manually, rather than
 * losing the scan. Not detected via [title]/[totalAmount] matching, since [title] is a localized
 * string captured at creation time and would silently stop matching after a language change.
 * [createdAt] is a device-local insertion timestamp, distinct from [dateTime] (the user-editable
 * transaction date) — used by Hub's import "replace, not merge" logic to tell which rows already
 * existed when a backup was taken (safe to replace) from ones created since (must survive). Rows
 * from before this field existed backfill to 0 via the Room migration, deliberately (see
 * ExpensesDatabase's MIGRATION_4_5 doc comment).
 * [uid]/[updatedAt] back the peer-to-peer sync merge (see :core:datahygiene's merge helper) — [uid]
 * is a stable cross-device identity generated once at creation (unlike [id], a local Room sequence
 * that means nothing on another phone), and [updatedAt] is bumped on every field-level edit so two
 * devices can resolve a conflicting edit by last-write-wins. Rows from before these fields existed
 * backfill via ExpensesDatabase's MIGRATION_5_6 (a fresh generated [uid] per row, [updatedAt] copied
 * from [createdAt]).
 * [source]/[manuallyEdited] feed [dataScore] (see ExpenseDataScore.kt) — which of two duplicate
 * candidates has the better data, for picking a merge winner instead of always trusting whichever
 * record arrived first. Rows from before these fields existed backfill to [ExpenseSource.MANUAL]/
 * `false` via ExpensesDatabase's MIGRATION_12_13 — a reasonable "unknown, treat as baseline" default,
 * no worse than the no-scoring behavior every row had before this feature existed.
 */
@Entity(
    tableName = "expenses",
    // (archivedAt, dateTime) serves the main list's shape — WHERE archivedAt IS NULL ordered by
    // dateTime — as one ordered walk instead of a scan-and-sort.
    indices = [Index("categoryId"), Index(value = ["uid"], unique = true), Index("archivedAt", "dateTime")]
)
data class Expense(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uid: String = UUID.randomUUID().toString(),
    val title: String? = null,
    val totalAmount: Double,
    // Invoice-only extras (see ReceiptTotalRegexParser): the balance carried from before this
    // document and the invoice's OWN charges. Null on every non-invoice record; [totalAmount] is
    // the grand total (largest labelled figure — what actually gets paid). The own-charges column
    // keeps its original name from the field's first iteration; only the Kotlin name is semantic.
    val previousBalanceAmount: Double? = null,
    @ColumnInfo(name = "totalToPayAmount") val invoiceOwnAmount: Double? = null,
    /**
     * The document's own three figures: what it charges before tax, the tax on it, and the total it
     * already carries in [totalAmount]. Null where the document did not separate them — most
     * receipts do not — and never derived, so their presence is what tells the edit screen there is
     * a breakdown worth showing.
     */
    val netAmount: Double? = null,
    val vatAmount: Double? = null,
    val currencyCode: String,
    val vendor: String? = null,
    /**
     * The account this went through, or the card it was made with — see [BankAccount].
     *
     * The only thing a record says about where the money came from. There is no bank beside it: a
     * bank is the name of an account, so the bank of a record is the name of the account it points
     * at, read through [com.voxapps.expenses.domain.accounts.BankAccountTree.bankNameFor] rather
     * than stored twice. Null is ordinary — a payment in cash went through nothing.
     */
    val bankAccountId: Long? = null,
    /**
     * Who this paid, where the record is a bank transaction — see [Recipient].
     *
     * The pointer IS the transaction flag: a record with a recipient is a transaction, one without
     * is not, and no second flag exists to disagree with it. The counterparty's name is the row's,
     * read through the link rather than stored twice — the same rule [bankAccountId] follows for
     * the bank. Null is the ordinary case: a shop purchase has a vendor, not a recipient.
     */
    val recipientId: Long? = null,
    /**
     * Where each field's value came from, as `field:origin` pairs — see
     * [com.voxapps.recordflow.FieldOrigin] and [ExpenseOrigins].
     *
     * Written by whatever made the record, because that is the only moment it is known. A field
     * missing from here has no claim attached to it, which is the ordinary case for a record typed
     * by hand: nothing about it needs explaining.
     */
    val originsJson: String? = null,
    val location: String? = null,
    val dateTime: Long,
    val comments: String? = null,
    @ColumnInfo(name = "categoryId") val categoryId: Long? = null,
    val direction: TransactionDirection = TransactionDirection.OUTGOING,
    val receiptImageName: String? = null,
    val isStub: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val source: ExpenseSource = ExpenseSource.MANUAL,
    val manuallyEdited: Boolean = false,

    /**
     * When this record was put out of the way, or null while it is part of the ledger.
     *
     * Archiving is the answer to a record that should stop counting without being destroyed: it
     * leaves every list, every total and every budget, and it keeps everything it said. The moment
     * rather than a flag, because the archive can be set to empty itself after a while and "a while"
     * has to be measured from something.
     */
    val archivedAt: Long? = null
)
