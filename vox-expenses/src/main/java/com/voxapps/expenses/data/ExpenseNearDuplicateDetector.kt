package com.voxapps.expenses.data

import com.voxapps.datahygiene.FieldCleaner
import com.voxapps.datahygiene.RuleCombinator
import com.voxapps.expenses.data.preferences.ExpensesSettings
import java.util.concurrent.TimeUnit

/**
 * The remaining global knobs [ExpensesRepository.buildDuplicateChecker] needs to build a duplicate
 * checker for [Expense] — everything else, including per-rule fuzzy matching, lives in the
 * user-editable [DuplicateRuleEntity] table now (see its own doc comment). [timeWindowMillis] applies
 * uniformly wherever a rule references [ExpenseRuleFields.ID_DATE_TIME] — one shared setting, not
 * per-rule, per [ExpenseRuleFields]'s own doc comment.
 */
data class NearDuplicateConfig(
    val timeWindowMillis: Long,
    val globalCombinator: RuleCombinator = RuleCombinator.OR
)

fun ExpensesSettings.toNearDuplicateConfig(): NearDuplicateConfig = NearDuplicateConfig(
    timeWindowMillis = TimeUnit.MINUTES.toMillis(nearDuplicateTimeWindowMinutes.toLong()),
    globalCombinator = runCatching { RuleCombinator.valueOf(duplicateRuleSetGlobalCombinator) }.getOrDefault(RuleCombinator.OR)
)

/**
 * Fills any blank/missing field on [existing] with [candidate]'s value for that same field — never
 * overwrites a field [existing] already has real content in, so the first-arrived record stays
 * authoritative wherever it already says something and only gains data it was missing. Excludes
 * identity/audit fields. Which content fields (title/vendor/amount/...) the rule that matched actually
 * required is irrelevant here — enrichment only ever fills genuine gaps, which is safe regardless of
 * what caused the match. Bumps [Expense.updatedAt] only when something actually changed.
 */
fun enrichWithNearDuplicate(existing: Expense, candidate: Expense): Expense {
    val merged = existing.copy(
        title = FieldCleaner.clean(existing.title) ?: FieldCleaner.clean(candidate.title),
        vendor = FieldCleaner.clean(existing.vendor) ?: FieldCleaner.clean(candidate.vendor),
        bank = FieldCleaner.clean(existing.bank) ?: FieldCleaner.clean(candidate.bank),
        location = FieldCleaner.clean(existing.location) ?: FieldCleaner.clean(candidate.location),
        comments = FieldCleaner.clean(existing.comments) ?: FieldCleaner.clean(candidate.comments),
        categoryId = existing.categoryId ?: candidate.categoryId,
        receiptImageName = existing.receiptImageName ?: candidate.receiptImageName
    )
    return if (merged == existing) existing else merged.copy(updatedAt = System.currentTimeMillis())
}
