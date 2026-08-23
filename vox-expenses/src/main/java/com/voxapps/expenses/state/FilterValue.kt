package com.voxapps.expenses.state

/**
 * How a list was narrowed: by a row somebody picked, or by words somebody typed.
 *
 * The two cannot be told apart from the text alone, and they must be. A picked row means that row,
 * so a name that another name contains must not be dragged in with it — the person can neither see
 * nor undo an inclusion they did not make. Typed words mean the opposite: they are offered because
 * no single row says what is wanted, so matching only what a row exactly says answers with the one
 * option that was already available.
 *
 * Carried rather than inferred. Deciding at match time — exact where the text equals some existing
 * value, containment otherwise — fails whenever a query is also a name, which for lists of shop
 * names is ordinary rather than a corner case.
 */
data class FilterValue(val text: String, val exact: Boolean) {

    fun matches(value: String?): Boolean {
        if (value == null) return false
        return if (exact) value.equals(text, ignoreCase = true)
        else value.contains(text, ignoreCase = true)
    }

    companion object {
        /** A row taken from the list: it means that row. */
        fun picked(text: String) = FilterValue(text, exact = true)

        /** Words applied to everything they find. */
        fun typed(text: String) = FilterValue(text.trim(), exact = false)
    }
}
