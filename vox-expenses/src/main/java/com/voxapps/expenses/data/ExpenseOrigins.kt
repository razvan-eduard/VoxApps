package com.voxapps.expenses.data

import com.voxapps.recordflow.FieldOrigin

/**
 * How [Expense.originsJson] is written and read: `field:origin`, comma separated.
 *
 * Flat text rather than a table, because it is read whole or not at all — a record's provenance is
 * shown beside that record's fields and nothing ever queries across records by it. Unknown field
 * names and unknown origins are dropped on read, so a value written by a later version, or by a
 * version that knew a field this one does not, degrades to "nothing claimed" instead of to a crash.
 */
object ExpenseOrigins {

    const val FIELD_TITLE = "title"
    const val FIELD_AMOUNT = "totalAmount"
    const val FIELD_CURRENCY = "currency"
    const val FIELD_VENDOR = "vendor"
    const val FIELD_BANK = "bank"
    const val FIELD_LOCATION = "location"
    const val FIELD_CATEGORY = "category"
    const val FIELD_DATE = "date"
    const val FIELD_ITEMS = "items"

    fun encode(origins: Map<String, FieldOrigin>): String? =
        origins.entries
            .sortedBy { it.key }
            .joinToString(",") { "${it.key}:${it.value.stored}" }
            .takeIf { it.isNotEmpty() }

    fun decode(stored: String?): Map<String, FieldOrigin> {
        if (stored.isNullOrBlank()) return emptyMap()
        return stored.split(",").mapNotNull { pair ->
            val field = pair.substringBefore(':').trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val origin = FieldOrigin.of(pair.substringAfter(':', "").trim()) ?: return@mapNotNull null
            field to origin
        }.toMap()
    }

    /** The origins of [record], with everything a person has since edited marked as theirs. */
    fun withTyped(stored: String?, typedFields: Set<String>): String? =
        encode(decode(stored) + typedFields.associateWith { FieldOrigin.TYPED })
}
