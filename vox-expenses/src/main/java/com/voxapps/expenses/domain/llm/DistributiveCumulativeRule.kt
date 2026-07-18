package com.voxapps.expenses.domain.llm

/**
 * The one piece of reasoning genuinely identical between [ExpenseParsePromptBuilder] (spoken
 * utterances) and [ExpenseScanCleanupPromptBuilder] (OCR receipt text): a DISTRIBUTIVE (per-unit)
 * price is never divided by quantity, a CUMULATIVE (total) price is. Everything else the two prompts
 * teach the model to recognize a distributive vs. cumulative price *from* is intentionally kept
 * separate and domain-specific (spoken semantic-role markers vs. printed line-item notation) — those
 * aren't the same reasoning wearing different words, they're genuinely different input shapes. Only
 * this invariant is a single source of truth, referenced by both builders instead of hand-copied.
 */
object DistributiveCumulativeRule {
    const val INVARIANT: String =
        "Invariant: a DISTRIBUTIVE (per-unit) price is NEVER divided by quantity — copy it as-is as " +
            "the per-unit value. A CUMULATIVE (total) price is divided by quantity to derive the " +
            "per-unit value. These are mutually exclusive for a single item/line."
}
