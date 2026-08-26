package com.voxapps.expenses.data

import androidx.room.TypeConverter
import com.voxapps.design.toEnumOr

/**
 * Room can't natively persist enums — stores each by its [Enum.name]. Reads fall back to each
 * field's declared default: a name this build doesn't know (a database written by a newer build)
 * must degrade to an ordinary row, not fail the query it appears in.
 */
class ExpensesConverters {
    @TypeConverter
    fun fromDirection(direction: TransactionDirection): String = direction.name

    @TypeConverter
    fun toDirection(value: String): TransactionDirection = value.toEnumOr(TransactionDirection.OUTGOING)

    @TypeConverter
    fun fromExpenseSource(source: ExpenseSource): String = source.name

    @TypeConverter
    fun toExpenseSource(value: String): ExpenseSource = value.toEnumOr(ExpenseSource.MANUAL)

    /** [DuplicateRuleEntity.fieldIds] — a comma-joined string is enough for [ExpenseRuleFields]'
     *  plain-identifier ids (no commas possible in them), no Gson dependency needed for one column. */
    @TypeConverter
    fun fromFieldIds(fieldIds: List<String>): String = fieldIds.joinToString(",")

    @TypeConverter
    fun toFieldIds(value: String): List<String> = if (value.isEmpty()) emptyList() else value.split(",")
}
