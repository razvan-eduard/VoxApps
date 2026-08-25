package com.voxapps.expenses.domain.bulk

/**
 * What a set of records agrees on for one field, if anything.
 *
 * The distinction that matters is between agreeing on nothing — every record's vendor is empty —
 * and disagreeing, which look identical if a field is only ever shown as a value. One is a fact
 * about the selection worth showing; the other is the reason a field must not be filled in on
 * anybody's behalf.
 */
data class Shared<T>(val value: T?, val agreed: Boolean) {

    companion object {
        /** Empty in, agreed on nothing out: a selection of no records contradicts itself nowhere. */
        fun <R, T> across(records: List<R>, of: (R) -> T?): Shared<T> {
            val values = records.map(of).distinct()
            return if (values.size <= 1) Shared(values.firstOrNull(), true) else Shared(null, false)
        }
    }
}
