package com.voxapps.expenses.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

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
 */
@Entity(
    tableName = "expenses",
    indices = [Index("categoryId")]
)
data class Expense(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String? = null,
    val totalAmount: Double,
    val currencyCode: String,
    val vendor: String? = null,
    val bank: String? = null,
    val location: String? = null,
    val dateTime: Long,
    val comments: String? = null,
    @ColumnInfo(name = "categoryId") val categoryId: Long? = null,
    val receiptImageName: String? = null,
    val isStub: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)
