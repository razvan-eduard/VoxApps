package com.voxapps.datahygiene

import kotlin.math.abs

/** How a rule's selected fields (or a rule set's rules) combine into one boolean outcome. */
enum class RuleCombinator { AND, OR }

/**
 * One comparable field an app registers for its entity, for use with [RuleBasedDuplicateChecker] —
 * this module has no reflection or entity-specific knowledge of its own; each app hand-writes its own
 * field list (same spirit as every existing per-entity [DuplicateChecker] already hand-writing field
 * comparisons directly). [id] is the stable identifier persisted in a [DuplicateRule]'s [DuplicateRule.fieldIds];
 * [labelKey] is a localization key the caller's own LanguageManager resolves — this module deliberately
 * has no localization dependency of its own.
 */
data class RuleField<T>(
    val id: String,
    val labelKey: String,
    val matches: (candidate: T, existing: T) -> Boolean
)

/** A user-defined duplicate-detection rule: which fields (by [RuleField.id]) must be considered, and
 *  whether all of them ([RuleCombinator.AND]) or any one of them ([RuleCombinator.OR]) matching is
 *  enough for this rule to be satisfied. */
data class DuplicateRule(val fieldIds: List<String>, val combinator: RuleCombinator)

/**
 * Generic, entity-agnostic [DuplicateChecker] built from a set of user-defined [DuplicateRule]s
 * evaluated against a caller-registered [RuleField] list — the "email filter" model: each rule
 * combines its own selected fields via its own [DuplicateRule.combinator], then the rules themselves
 * combine via [globalCombinator]. A rule referencing no resolvable field IDs (or an empty [rules]
 * list) never contributes a match rather than throwing — validating that a rule has at least one
 * field before it's ever saved is the caller's/UI's responsibility, not this evaluator's.
 */
class RuleBasedDuplicateChecker<T>(
    private val fields: List<RuleField<T>>,
    private val rules: List<DuplicateRule>,
    private val globalCombinator: RuleCombinator
) : DuplicateChecker<T> {

    /** Built once per checker rather than per [isDuplicateOf] call — the O(n²) whole-list scans in
     *  vox-expenses call this once per expense *pair*, so rebuilding the map inside made it O(n² · f)
     *  map constructions for a lookup table that never changes. */
    private val fieldsById = fields.associateBy { it.id }

    override fun isDuplicateOf(candidate: T, existing: T): Boolean {
        if (rules.isEmpty()) return false
        val results = rules.map { rule ->
            val selected = rule.fieldIds.mapNotNull { fieldsById[it] }
            if (selected.isEmpty()) {
                false
            } else when (rule.combinator) {
                RuleCombinator.AND -> selected.all { it.matches(candidate, existing) }
                RuleCombinator.OR -> selected.any { it.matches(candidate, existing) }
            }
        }
        return when (globalCombinator) {
            RuleCombinator.AND -> results.all { it }
            RuleCombinator.OR -> results.any { it }
        }
    }
}

/**
 * Reusable [RuleField] builders — the comparator *logic* (exact/fuzzy-string/time-window) lives here
 * once, shared by every entity, so registering a new entity's rule fields is a one-line-per-field
 * accessor list rather than hand-written comparison code each time. What still can't be automated,
 * and is deliberately left to the caller: a human-readable [RuleField.labelKey] (a property name like
 * `vendor` isn't a translatable string) and *which* builder fits a field (a raw `Long` could mean
 * "compare exactly," like a category id, or "compare within a window," like a timestamp — same type,
 * different intent, not inferable from a database schema alone).
 */

/** Exact equality after [FieldCleaner] normalization, fuzzy-matched via [FuzzyMatcher] when
 *  [fuzzyMatchEnabled] is on. A field that cleans to null/blank on either side never matches — a
 *  blank field is never "the same" as anything, including another blank field. */
fun <T> stringField(
    id: String,
    labelKey: String,
    fuzzyMatchEnabled: Boolean,
    fuzzyMatcher: FuzzyMatcher = FuzzyMatcher.NONE,
    accessor: (T) -> String?
): RuleField<T> = RuleField(id, labelKey) { candidate, existing ->
    val a = FieldCleaner.clean(accessor(candidate)) ?: return@RuleField false
    val b = FieldCleaner.clean(accessor(existing)) ?: return@RuleField false
    if (fuzzyMatchEnabled && fuzzyMatcher != FuzzyMatcher.NONE) fuzzyMatcher.namesMatch(a, b) else a.equals(b, ignoreCase = true)
}

/** Plain `==` on whatever [accessor] returns — the right default for numbers, enums, and other
 *  exact-or-nothing values (amount, currency code, category id, direction, ...). A null on either
 *  side never matches, even null-vs-null — an unset field is never "the same" as another unset one. */
fun <T> exactField(id: String, labelKey: String, accessor: (T) -> Any?): RuleField<T> =
    RuleField(id, labelKey) { candidate, existing ->
        val a = accessor(candidate) ?: return@RuleField false
        val b = accessor(existing) ?: return@RuleField false
        a == b
    }

/** Matches when the two millis-since-epoch values fall within [windowMillis] of each other — for a
 *  timestamp field where "the same moment" should mean "close enough," not bit-identical. */
fun <T> timeWindowField(id: String, labelKey: String, windowMillis: Long, accessor: (T) -> Long): RuleField<T> =
    RuleField(id, labelKey) { candidate, existing -> abs(accessor(candidate) - accessor(existing)) <= windowMillis }

/** Pluggable fuzzy-name comparator for [stringField] — [:core:datahygiene] has no dependency on any
 *  specific fuzzy-matching library, so a caller (e.g. vox-expenses, backed by `:core:textmatch`'s
 *  `FuzzyNameMatcher`) supplies its own. [NONE] disables fuzzy matching regardless of the
 *  `fuzzyMatchEnabled` flag, for fields where fuzzy comparison never makes sense. */
fun interface FuzzyMatcher {
    fun namesMatch(a: String, b: String): Boolean

    companion object {
        val NONE = FuzzyMatcher { _, _ -> false }
    }
}
