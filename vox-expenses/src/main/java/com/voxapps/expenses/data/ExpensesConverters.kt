package com.voxapps.expenses.data

import androidx.room.TypeConverter

/** Room can't natively persist enums — stores [TransactionDirection] by its [Enum.name]. */
class ExpensesConverters {
    @TypeConverter
    fun fromDirection(direction: TransactionDirection): String = direction.name

    @TypeConverter
    fun toDirection(value: String): TransactionDirection = TransactionDirection.valueOf(value)

    /** [DuplicateRuleEntity.fieldIds] — a comma-joined string is enough for [ExpenseRuleFields]'
     *  plain-identifier ids (no commas possible in them), no Gson dependency needed for one column. */
    @TypeConverter
    fun fromFieldIds(fieldIds: List<String>): String = fieldIds.joinToString(",")

    @TypeConverter
    fun toFieldIds(value: String): List<String> = if (value.isEmpty()) emptyList() else value.split(",")
}
