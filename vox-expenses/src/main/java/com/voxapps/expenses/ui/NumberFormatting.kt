package com.voxapps.expenses.ui

/**
 * Formats/parses editable decimal text fields (total, quantity, price, VAT breakdown) using the app's
 * own [com.voxapps.expenses.data.preferences.ExpensesSettings.decimalSeparator] setting — never the
 * device's default `Locale`. Kotlin's `String.toDoubleOrNull()` only ever accepts a period, so a field
 * pre-filled via a locale-formatted string (e.g. comma-decimal Romanian) would silently fail to parse
 * back, leaving Save permanently disabled. Always formats/parses against a canonical period-based
 * representation internally, swapping only the separator character for display/input.
 */
private const val COMMA = ','
private const val PERIOD = '.'

fun formatDecimal(value: Double, useComma: Boolean): String {
    val text = "%.2f".format(java.util.Locale.US, value)
    return if (useComma) text.replace(PERIOD, COMMA) else text
}

fun parseDecimalOrNull(text: String, useComma: Boolean): Double? {
    val normalized = if (useComma) text.replace(COMMA, PERIOD) else text
    return normalized.toDoubleOrNull()
}
