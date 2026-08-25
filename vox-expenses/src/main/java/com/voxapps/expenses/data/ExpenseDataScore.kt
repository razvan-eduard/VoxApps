package com.voxapps.expenses.data

import com.voxapps.datahygiene.RecordProvenance
import com.voxapps.datahygiene.recordScore

/**
 * How an expense's data was originally captured — feeds [RecordProvenance.trustTier] for
 * [Expense.dataScore], not to be confused with `:core:datahygiene`'s [com.voxapps.datahygiene.RecordSource]
 * (LLM/HUB_IMPORT/MANUAL_UI), which routes a save through the sanitize-or-confirm policy rather than
 * ranking data trustworthiness. Ordering (manual > scan > notification > voice) is a judgment call:
 * scan/notification text both come from a document/bank's own printed text (reasonably reliable),
 * voice goes through speech-to-text + LLM interpretation and is the most error-prone for proper nouns
 * (vendor names) — see this session's few-shot literal-leakage fixes for a concrete example.
 */
enum class ExpenseSource(override val trustTier: Int) : RecordProvenance {
    MANUAL(400),
    SCAN(300),
    NOTIFICATION(200),
    VOICE(100)
}

/**
 * "Which of two duplicate expenses has the better data" — used to pick a merge winner instead of
 * always trusting whichever record arrived first (see [enrichWithNearDuplicate]/
 * [com.voxapps.expenses.data.ExpensesRepository.applyExpenseDeduplication]). [Expense.manuallyEdited]
 * always outranks source/completeness; among fields, only the genuinely optional/enrichable ones count
 * toward completeness — identity/audit fields (id, uid, createdAt, ...) don't vary in "quality".
 */
fun Expense.dataScore(): Int =
    recordScore(manuallyEdited, source, listOf(title, vendor, bankAccountId, location, comments, categoryId))
