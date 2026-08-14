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

    /** [presetAmount] is a deterministically pre-resolved amount the prompt told the model not to
     *  produce (see NotificationPreParse) — the reply lacking one is then by design, not a failed
     *  parse, and the preset value is the record's amount. */
    /** [presetIsPayment]: the template memory confirmed this shape is a transaction and the
     *  prompt neither asked the question nor offered the escape — a reply without the field is
     *  then by design, not a rejection. */
    fun parse(json: String, presetAmount: Double? = null, presetIsPayment: Boolean = false): Parsed? = try {
        val o = JSONObject(json)
        if (!presetIsPayment && !o.optBoolean("isPayment", false)) {
            null
        } else {
            val totalAmount = presetAmount ?: (if (o.has("totalAmount") && !o.isNull("totalAmount")) {
                o.optDouble("totalAmount").takeIf { !it.isNaN() }
            } else {
                null
            } ?: return null)

            Parsed(
                title = o.optCleanString("title").dropIfExamplePlaceholder(),
                totalAmount = totalAmount,
                currency = o.optCleanString("currency"),
                vendor = o.optCleanString("vendor").dropIfExamplePlaceholder(),
                category = o.optCleanString("category"),
                bank = o.optCleanString("bank"),
                direction = o.optTransactionDirection()
            )
        }
    } catch (e: Exception) {
        null
    }

    /**
     * A prompt-level "don't copy this" instruction can't be trusted to actually stop a small local
     * model from leaking few-shot example content (confirmed on-device: a real notification with its
     * own distinct merchant name still came back with the example's literal placeholder vendor). This
     * is the deterministic backstop — strip the field back out in code regardless of what the model
     * does, rather than relying on wording alone.
     */
    private fun String?.dropIfExamplePlaceholder(): String? =
        if (this != null && this.equals(NotificationExpenseParsePromptBuilder.EXAMPLE_VENDOR_PLACEHOLDER, ignoreCase = true)) {
            null
        } else {
            this
        }
}
