package com.voxapps.expenses.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.voxapps.datahygiene.DuplicateRule
import com.voxapps.datahygiene.RuleCombinator

/**
 * A user-defined duplicate-detection rule (see [com.voxapps.datahygiene.RuleBasedDuplicateChecker]) —
 * [fieldIds] are [ExpenseRuleFields] ids, [combinator] is a [RuleCombinator] name. Persisted here
 * (not `:core:datahygiene`) because Room entities aren't shared across app databases in this
 * codebase — each app owns its own table, same as [SpendingLimit].
 *
 * [appliesAutomatically]: whether this rule is used at insert time (silent auto-merge — see
 * [ExpensesRepository.addExpense]) in addition to manual "Check for duplicates now"/scheduled checks,
 * which always use every *enabled* rule regardless of this flag (those are staged for review, so a
 * broader/riskier rule is safe there even when it's too aggressive to trust unattended at save time).
 * On by default, matching every rule's behavior before this flag existed.
 *
 * [fuzzyMatchEnabled]: per-rule, not global — whether this rule's string fields (title/vendor/bank/
 * location/comments) compare exact (case-insensitive) or fuzzy (containment/similarity, via
 * [com.voxapps.textmatch.FuzzyNameMatcher]). Lets one rule stay strict while another is looser,
 * instead of one setting affecting every rule uniformly.
 */
@Entity(tableName = "duplicate_rules")
data class DuplicateRuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val fieldIds: List<String>,
    val combinator: String,
    val enabled: Boolean = true,
    val sortOrder: Int = 0,
    val appliesAutomatically: Boolean = true,
    val fuzzyMatchEnabled: Boolean = true
) {
    fun toDuplicateRule(): DuplicateRule = DuplicateRule(
        fieldIds = fieldIds,
        combinator = runCatching { RuleCombinator.valueOf(combinator) }.getOrDefault(RuleCombinator.AND)
    )
}
