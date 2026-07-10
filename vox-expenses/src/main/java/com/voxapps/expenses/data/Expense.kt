package com.voxapps.expenses.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A single expense. [totalAmount] is the only mandatory field — it's the explicit, persisted total
 * (computed from [ExpenseLineItem] subtotals when items exist, still user-editable/overridable; a
 * plain free-form number when there are no items). Everything else is optional. [categoryId] is null
 * when uncategorized; deleting a category nulls it in code (same convention as vox-notes), not via a
 * DB foreign key.
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
    @ColumnInfo(name = "categoryId") val categoryId: Long? = null
)
