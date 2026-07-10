package com.voxapps.expenses.domain.llm

/** An expense as sent to the dedup prompt — just enough to judge duplication. */
data class ExpenseSummary(
    val id: Long,
    val title: String?,
    val vendor: String?,
    val totalAmount: Double,
    val currencyCode: String,
    val dateTime: Long
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
            "id=${e.id} | $label | ${e.totalAmount} ${e.currencyCode} | ts=${e.dateTime}"
        }
        return """
            Evaluate the following list of expenses and identify groups that are genuinely duplicate
            entries — the exact same purchase logged more than once (same or near-identical amount,
            vendor, and timestamp within a short window). Do NOT group expenses just because they share
            a vendor or amount coincidentally on different occasions; only flag them if they clearly
            represent the same single transaction entered twice. When in doubt, omit the expense
            entirely rather than guessing.

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
