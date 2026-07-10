package com.voxapps.expenses.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One product-list row on an [Expense]. Subtotal ([quantity] * [unitPrice]) is computed in the
 * repository layer, never stored — [Expense.totalAmount] is the one persisted, user-facing total.
 * `CASCADE` delete: removing an expense removes its line items with it.
 */
@Entity(
    tableName = "expense_line_items",
    foreignKeys = [
        ForeignKey(
            entity = Expense::class,
            parentColumns = ["id"],
            childColumns = ["expenseId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("expenseId")]
)
data class ExpenseLineItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "expenseId") val expenseId: Long,
    val name: String,
    val quantity: Double,
    val unitPrice: Double,
    val position: Int = 0
) {
    val subtotal: Double get() = quantity * unitPrice
}
