package com.voxapps.datahygiene

/**
 * Applies user-taught re-map rules to a record: WHEN the rule's match fields all equal its stored
 * values, THEN its set fields are written. The engine owns only the matching and precedence — which
 * fields exist, how a set-value is parsed, and where rules are stored belong to the app, injected
 * as descriptors the same way [RuleBasedDuplicateChecker] takes its [RuleField]s.
 *
 * Matching is exact over [RemapValueKey.normalize]d values unless a match entry carries a fuzz
 * level, in which case the injected matcher resolves it — the app decides what each level means.
 * A record that doesn't match is simply left alone.
 *
 * Rules never chain: every rule is matched against the record as it arrived, not against another
 * rule's output — chained rewriting would make the outcome depend on evaluation order in ways no
 * rule author can see.
 */
object RemapValueKey {
    /** Exact normalized equality — trim and case only, deliberately not fuzzy (see class doc). */
    fun normalize(value: String?): String? = value?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
}

/** A field a rule may match on. [valueOf] reads the record's current value; the engine normalizes. */
data class RemapMatchField<T>(
    val id: String,
    val labelKey: String,
    val valueOf: (T) -> String?
)

/**
 * A field a rule may write. [apply] returns the record with the value written, or null to decline —
 * a setter that can't honor the stored value (an id pointing at a deleted category, unparseable
 * content) declines that field and the record keeps what it had.
 */
data class RemapSetField<T>(
    val id: String,
    val labelKey: String,
    val apply: (T, String) -> T?
)

/** [match]: fieldId → normalized value that must equal the record's. [set]: fieldId → value to
 *  write. [fuzz]: fieldId → fuzziness level for that match entry (absent or 0 = exact; 1..3 =
 *  progressively easier matches, resolved by the engine's injected matcher). [sortOrder] orders
 *  rules the user ranked; [id] is the storage identity. */
data class RemapRule(
    val id: Long,
    val match: Map<String, String>,
    val set: Map<String, String>,
    val sortOrder: Int = 0,
    val fuzz: Map<String, Int> = emptyMap()
)

class RemapEngine<T>(
    private val matchFields: List<RemapMatchField<T>>,
    private val setFields: List<RemapSetField<T>>,
    /** Resolves a match entry whose fuzz level is above 0 — (record value, rule value, level).
     *  Injected like [FuzzyMatcher] on the duplicate engine, so this module stays matcher-free;
     *  null means every entry compares exact regardless of its stored level. */
    private val leveledMatcher: ((String, String, Int) -> Boolean)? = null
) {

    /**
     * [record] with every winning rule's set fields applied.
     *
     * The precedence total order, enforced here and nowhere else:
     *  1. more match fields before fewer — the more specific condition wins;
     *  2. [RemapRule.sortOrder] ascending — the order the user ranked the rules;
     *  3. [RemapRule.id] ascending as the final deterministic tiebreak.
     *
     * Winning is per set-field: the first matching rule in that order to set a field owns it, and
     * later matching rules may still fill set-fields nobody has claimed. All matching runs against
     * the pre-remap [record] snapshot (no chaining, see file doc).
     */
    fun apply(record: T, rules: List<RemapRule>): T {
        val fieldsById = matchFields.associateBy { it.id }
        val ordered = rules
            .filter { it.match.isNotEmpty() && it.set.isNotEmpty() }
            .sortedWith(compareBy({ -it.match.size }, { it.sortOrder }, { it.id }))
        var out = record
        val claimed = mutableSetOf<String>()
        for (rule in ordered) {
            val matches = rule.match.all { (fieldId, expected) ->
                val field = fieldsById[fieldId] ?: return@all false
                val actual = field.valueOf(record)
                val level = rule.fuzz[fieldId] ?: 0
                if (level > 0 && leveledMatcher != null) {
                    actual != null && leveledMatcher.invoke(actual, expected, level)
                } else {
                    RemapValueKey.normalize(actual) == expected
                }
            }
            if (!matches) continue
            for ((fieldId, value) in rule.set) {
                if (fieldId in claimed) continue
                val setter = setFields.firstOrNull { it.id == fieldId } ?: continue
                val applied = setter.apply(out, value) ?: continue
                out = applied
                claimed += fieldId
            }
        }
        return out
    }
}
