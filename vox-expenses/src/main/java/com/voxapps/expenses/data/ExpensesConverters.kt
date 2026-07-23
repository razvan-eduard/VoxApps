package com.voxapps.expenses.data

import androidx.room.TypeConverter

/** Room can't natively persist enums — stores [TransactionDirection] by its [Enum.name]. */
class ExpensesConverters {
    @TypeConverter
    fun fromDirection(direction: TransactionDirection): String = direction.name

    @TypeConverter
    fun toDirection(value: String): TransactionDirection = TransactionDirection.valueOf(value)
}
