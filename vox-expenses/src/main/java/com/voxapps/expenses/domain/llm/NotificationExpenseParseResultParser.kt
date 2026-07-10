package com.voxapps.expenses.domain.llm

import org.json.JSONObject

/**
 * Parses Commander's reply to a [NotificationExpenseParsePromptBuilder] request. [parse] returns null
 * for BOTH "not a payment" responses AND genuinely malformed JSON — the caller only needs "is there a
 * usable expense here or not", it doesn't need to distinguish the two cases differently (both mean:
 * discard silently, no pending entry).
 */
object NotificationExpenseParseResultParser {
    data class Parsed(
        val title: String?,
        val totalAmount: Double,
        val currency: String?,
        val vendor: String?,
        val category: String?
    )

    fun parse(json: String): Parsed? = try {
        val o = JSONObject(json)
        if (!o.optBoolean("isPayment", false)) {
            null
        } else {
            val totalAmount = if (o.has("totalAmount") && !o.isNull("totalAmount")) {
                o.optDouble("totalAmount").takeIf { !it.isNaN() }
            } else {
                null
            } ?: return null

            Parsed(
                title = o.optString("title").takeIf { it.isNotBlank() },
                totalAmount = totalAmount,
                currency = o.optString("currency").takeIf { it.isNotBlank() },
                vendor = o.optString("vendor").takeIf { it.isNotBlank() },
                category = o.optString("category").takeIf { it.isNotBlank() }
            )
        }
    } catch (e: Exception) {
        null
    }
}
