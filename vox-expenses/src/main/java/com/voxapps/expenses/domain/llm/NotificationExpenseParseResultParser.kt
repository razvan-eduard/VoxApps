package com.voxapps.expenses.domain.llm

import com.voxapps.datahygiene.optCleanString
import com.voxapps.expenses.data.TransactionDirection
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
        val category: String?,
        val bank: String?,
        val direction: TransactionDirection = TransactionDirection.OUTGOING
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
                title = o.optCleanString("title"),
                totalAmount = totalAmount,
                currency = o.optCleanString("currency"),
                vendor = o.optCleanString("vendor"),
                category = o.optCleanString("category"),
                bank = o.optCleanString("bank"),
                direction = o.optTransactionDirection()
            )
        }
    } catch (e: Exception) {
        null
    }
}
