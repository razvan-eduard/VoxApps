package com.voxapps.expenses.domain.llm

import com.voxapps.expenses.data.TransactionDirection

/** An expense as sent to the dedup prompt — just enough to judge duplication. [direction] exists
 *  specifically so the model can tell an outgoing payment from an incoming top-up/refund of the same
 *  amount apart — without it, two genuinely opposite transactions (money out vs. money in) that happen
 *  to share an amount are indistinguishable from real duplicates in the prompt text. */
data class ExpenseSummary(
    val id: Long,
    val title: String?,
    val vendor: String?,
    val totalAmount: Double,
    val currencyCode: String,
    val dateTime: Long,
    val direction: TransactionDirection
)

/**
 * Builds the full prompt text sent to Commander's generic LLM hook for the expense-deduplication
 * feature (mirrors vox-notes' NoteDeduplicationPromptBuilder). No language parameter needed — the
 * expected output is purely numeric ids.
 *
 * Every expense in [expenses] is included in one shot — for accounts with very many expenses this can
 * grow large; not addressed here (same characteristic as vox-notes' note dedup).
 */
object ExpenseDeduplicationPromptBuilder {
    fun build(expenses: List<ExpenseSummary>): String {
        val expensesBlock = expenses.joinToString("\n") { e ->
            val label = e.title?.takeIf { it.isNotBlank() } ?: e.vendor ?: "(no title)"
            val directionLabel = if (e.direction == TransactionDirection.INCOMING) "incoming" else "outgoing"
            "id=${e.id} | $label | ${e.totalAmount} ${e.currencyCode} ($directionLabel) | ts=${e.dateTime}"
        }
        return """
            Evaluate the following list of expenses and identify groups that are genuinely duplicate
            entries — the exact same purchase logged more than once (same or near-identical amount,
            vendor, and timestamp within a short window). Do NOT group expenses just because they share
            a vendor or amount coincidentally on different occasions; only flag them if they clearly
            represent the same single transaction entered twice. When in doubt, omit the expense
            entirely rather than guessing.

            Every entry is tagged (incoming) or (outgoing).
            NEVER group an incoming entry with an outgoing one, even if the amount matches exactly — an
            incoming top-up/refund and an outgoing payment of the same amount are two different real
            transactions, not a duplicate, no matter how close in time.

            For each group, pick the single best entry to keep (the most complete one — e.g. the one
            with a title, vendor, or line items if others lack them) and list the rest as duplicates to
            remove. Return ONLY a JSON object of the shape
            {"groups": [{"keep": <id>, "duplicates": [<id>, ...]}]}, using the exact numeric ids given
            below — never invent an id. Omit expenses that have no duplicates. No prose, no markdown.

            Expenses:
            $expensesBlock
        """.trimIndent()
    }
}
