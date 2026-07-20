package com.voxapps.expenses.domain.llm

import com.voxapps.datahygiene.optCleanString
import com.voxapps.schema.VoxExtractionSchema
import org.json.JSONArray
import org.json.JSONObject

/**
 * Parses Commander's structured reply to an [ExpenseParsePromptBuilder] request. [totalAmount] is
 * the only field required to succeed — an expense can't be saved without one (see `Expense.kt`) — so
 * [parse] returns null (discard, don't create a broken expense) if it's missing or unparseable.
 */
object ExpenseParseResultParser {
    data class ParsedItem(
        val name: String,
        val quantity: Double,
        val unitPrice: Double,
        val netAmount: Double? = null,
        val vatAmount: Double? = null,
        val grossAmount: Double? = null
    )

    @VoxExtractionSchema(version = 1)
    data class Parsed(
        val title: String?,
        val totalAmount: Double,
        val currency: String?,
        val vendor: String?,
        val bank: String?,
        // Only ever populated by the OCR scan-cleanup task (a receipt's printed store address, city
        // only) — the voice-parse prompt never asks the LLM for this, so it's always null there;
        // LlmResultReceiver falls back to ExpensesLocationHelper's GPS-derived city in that case.
        val location: String?,
        val category: String?,
        val date: String?, // YYYY-MM-DD format
        val time: String?, // HH:mm format
        val items: List<ParsedItem>
    ) {
        val itemsSumMismatch: Boolean =
            ExpenseAmountMismatch.isGrossMismatch(totalAmount, items.sumOf { it.quantity * it.unitPrice })
    }

    private fun JSONObject.optNullableDouble(key: String): Double? {
        if (isNull(key) || !has(key)) return null

        // Try standard optDouble first (handles Int, Double, Long, and clean Strings)
        val d = optDouble(key)
        if (!d.isNaN()) return d

        // Handle noisy strings (e.g. "12,50 lei" or "100.00 RON")
        val value = opt(key)
        if (value is String) {
            // Replace decimal comma with period only if between digits
            val normalized = value.replace(Regex("(\\d),(\\d)"), "$1.$2")
            // Keep only digits, periods, and minus sign
            val digitsOnly = normalized.replace(Regex("[^0-9.-]"), "")
            return digitsOnly.toDoubleOrNull()
        }

        return null
    }

    fun parse(json: String): Parsed? = try {
        val o = JSONObject(json)
        val totalAmount = o.optNullableDouble("totalAmount") ?: return null

        val itemsArray = o.optJSONArray("items") ?: JSONArray()
        val items = (0 until itemsArray.length()).mapNotNull { i ->
            val item = itemsArray.optJSONObject(i) ?: return@mapNotNull null
            val name = item.optCleanString("name") ?: return@mapNotNull null
            val quantity = item.optNullableDouble("quantity") ?: 1.0
            val unitPrice = item.optNullableDouble("unitPrice") ?: return@mapNotNull null
            ParsedItem(
                name = name,
                quantity = quantity,
                unitPrice = unitPrice,
                netAmount = item.optNullableDouble("netAmount"),
                vatAmount = item.optNullableDouble("vatAmount"),
                grossAmount = item.optNullableDouble("grossAmount")
            )
        }

        Parsed(
            title = o.optCleanString("title"),
            totalAmount = totalAmount,
            currency = o.optCleanString("currency"),
            vendor = o.optCleanString("vendor"),
            bank = o.optCleanString("bank"),
            location = o.optCleanString("location"),
            category = o.optCleanString("category"),
            date = o.optCleanString("date"),
            time = o.optCleanString("time"),
            items = items
        )
    } catch (e: Exception) {
        null
    }
}
