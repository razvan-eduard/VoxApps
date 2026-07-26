package com.voxapps.expenses.data

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
 * [receiptImageName] stores the filename of the receipt photo in internal storage.
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
    indices = [Index("categoryId"), Index(value = ["uid"], unique = true)]
)
data class Expense(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uid: String = UUID.randomUUID().toString(),
    val title: String? = null,
    val totalAmount: Double,
    val currencyCode: String,
    val vendor: String? = null,
    val bank: String? = null,
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
    val manuallyEdited: Boolean = false
)
