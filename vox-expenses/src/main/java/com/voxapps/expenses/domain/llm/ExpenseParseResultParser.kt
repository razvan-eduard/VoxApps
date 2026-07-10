package com.voxapps.expenses.domain.llm

import org.json.JSONArray
import org.json.JSONObject

/**
 * Parses Commander's structured reply to an [ExpenseParsePromptBuilder] request. [totalAmount] is
 * the only field required to succeed — an expense can't be saved without one (see `Expense.kt`) — so
 * [parse] returns null (discard, don't create a broken expense) if it's missing or unparseable.
 */
object ExpenseParseResultParser {
    data class ParsedItem(val name: String, val quantity: Double, val unitPrice: Double)

    data class Parsed(
        val title: String?,
        val totalAmount: Double,
        val currency: String?,
        val vendor: String?,
        val category: String?,
        val items: List<ParsedItem>
    )

    fun parse(json: String): Parsed? = try {
        val o = JSONObject(json)
        val totalAmount = if (o.isNull("totalAmount") || !o.has("totalAmount")) {
            null
        } else {
            o.optDouble("totalAmount").takeIf { !it.isNaN() }
        } ?: return null

        val itemsArray = o.optJSONArray("items") ?: JSONArray()
        val items = (0 until itemsArray.length()).mapNotNull { i ->
            val item = itemsArray.optJSONObject(i) ?: return@mapNotNull null
            val name = item.optString("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val quantity = item.optDouble("quantity", 1.0).takeIf { !it.isNaN() } ?: 1.0
            val unitPrice = item.optDouble("unitPrice").takeIf { !it.isNaN() } ?: return@mapNotNull null
            ParsedItem(name = name, quantity = quantity, unitPrice = unitPrice)
        }

        Parsed(
            title = o.optString("title").takeIf { it.isNotBlank() },
            totalAmount = totalAmount,
            currency = o.optString("currency").takeIf { it.isNotBlank() },
            vendor = o.optString("vendor").takeIf { it.isNotBlank() },
            category = o.optString("category").takeIf { it.isNotBlank() },
            items = items
        )
    } catch (e: Exception) {
        null
    }
}
