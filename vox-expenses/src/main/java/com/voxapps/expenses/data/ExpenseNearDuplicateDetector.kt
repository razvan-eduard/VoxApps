package com.voxapps.expenses.data

import com.voxapps.datahygiene.FieldCleaner
import com.voxapps.datahygiene.RuleCombinator
import com.voxapps.design.toEnumOr
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
    globalCombinator = duplicateRuleSetGlobalCombinator.toEnumOr(RuleCombinator.OR)
)

/**
 * Merges [candidate] into [existing], field by field, preferring whichever side has the better data
 * per [Expense.dataScore] (manual edits pinned, then capture-source trust tier, then completeness) —
 * not always [existing] just because it arrived first. A field still only changes when the winning
 * side actually has content there; a field neither side filled stays blank. Row identity (id/uid/
 * createdAt) always stays [existing]'s regardless of which side's content wins, since [existing] is
 * the row that actually gets written back to — this is what makes it safe to fold across more than
 * two records (see [com.voxapps.expenses.data.ExpensesRepository.applyExpenseDeduplication]) without
 * ever reassigning identity mid-fold. Bumps [Expense.updatedAt] only when something actually changed.
 */
fun enrichWithNearDuplicate(existing: Expense, candidate: Expense): Expense {
    val preferCandidate = candidate.dataScore() > existing.dataScore()
    val primary = if (preferCandidate) candidate else existing
    val secondary = if (preferCandidate) existing else candidate
    val merged = existing.copy(
        title = FieldCleaner.clean(primary.title) ?: FieldCleaner.clean(secondary.title),
        vendor = FieldCleaner.clean(primary.vendor) ?: FieldCleaner.clean(secondary.vendor),
        bankAccountId = primary.bankAccountId ?: secondary.bankAccountId,
        location = FieldCleaner.clean(primary.location) ?: FieldCleaner.clean(secondary.location),
        comments = FieldCleaner.clean(primary.comments) ?: FieldCleaner.clean(secondary.comments),
        categoryId = primary.categoryId ?: secondary.categoryId,
        receiptImageName = primary.receiptImageName ?: secondary.receiptImageName
    )
    return if (merged == existing) existing else merged.copy(updatedAt = System.currentTimeMillis())
}
