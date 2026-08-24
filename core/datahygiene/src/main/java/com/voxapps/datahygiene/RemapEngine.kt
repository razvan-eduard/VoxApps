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

/** A field a rule may match on. [valueOf] reads the record's current value; the engine normalizes.
 *  [numeric] admits the ordering comparisons — a field whose values are quantities can be asked
 *  whether it is *over* something, which of a name would be a question with no answer. */
data class RemapMatchField<T>(
    val id: String,
    val labelKey: String,
    val numeric: Boolean = false,
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
/**
 * One thing a rule tests: a field, the value it must carry, and how exactly.
 *
 * A list rather than a map keyed by field, because the same field is asked about more than once in
 * the most ordinary rule there is — "vendor is Lidl OR vendor is Carrefour" is one question about
 * one field with two acceptable answers, and a map can only hold the last of them.
 */
data class RemapCondition(
    val fieldId: String,
    val value: String,
    val fuzz: Int = 0,
    val op: RemapOp = RemapOp.EQ
)

/**
 * How a condition compares.
 *
 * Equality is the whole of it for text: a shop is the one named or it is not. A quantity is
 * different — the useful thing to say about an amount is almost never that it equals a figure but
 * that it passed one, and there is no way to write that as a set of equalities.
 *
 * The symbols are the stored form, so a rule reads the same in the database as on screen.
 */
enum class RemapOp(val symbol: String) {
    EQ("="), GT(">"), GTE(">="), LT("<"), LTE("<=");

    companion object {
        fun of(symbol: String?): RemapOp = entries.firstOrNull { it.symbol == symbol } ?: EQ
    }
}

/**
 * A trigger: the alternatives, any one of which fires the rule.
 *
 * Sum of products — OR between groups, AND inside one — which is the shape that expresses what people
 * actually write. "Lidl or Carrefour" is one group per shop; "Lidl in Cluj" is one group of two;
 * "(Lidl and Cluj) or Carrefour" needs both at once, and a single AND/OR switch on the rule cannot
 * say it. The same shape Commander's fast-map rules already use for spoken triggers.
 *
 * One group of one condition is the ordinary rule, and reads as one.
 */
data class RemapTrigger(val groups: List<List<RemapCondition>>) {

    val isEmpty: Boolean get() = groups.none { it.isNotEmpty() }

    /**
     * How much a record has to satisfy for this trigger to fire, counted by its *weakest* group.
     *
     * A rule fires on whichever alternative is easiest to satisfy, so that is what says how
     * demanding it really is — adding a broad alternative to a narrow rule makes the whole rule
     * broader, and counting the total conditions would report the opposite.
     */
    val demand: Int get() = groups.filter { it.isNotEmpty() }.minOfOrNull { it.size } ?: 0

    companion object {
        /** The everyday case: every condition has to hold. */
        fun all(conditions: List<RemapCondition>) = RemapTrigger(listOf(conditions))

        /** One field, several acceptable answers. */
        fun anyOf(fieldId: String, values: List<String>, fuzz: Int = 0) =
            RemapTrigger(values.map { listOf(RemapCondition(fieldId, it, fuzz)) })
    }
}

/**
 * A rule: what it recognises, and what follows from that.
 *
 * [set] rewrites the record, [alert] says the person wants to hear about it, and a rule may carry
 * either or both. A rule that only alerts changes nothing at all about the record — it is the
 * standing request to be told, which is a real thing to want and was impossible to say while the
 * only expressible consequence was a rewrite.
 */
data class RemapRule(
    val id: Long,
    val trigger: RemapTrigger,
    val set: Map<String, String>,
    val sortOrder: Int = 0,
    val alert: Boolean = false
)

/** What a record's pass through the engine came to: the record as it stands, and the rules that
 *  recognised it — the second is how a consequence that is not a field gets acted on. */
data class RemapOutcome<T>(val record: T, val fired: List<RemapRule>)

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
    fun apply(record: T, rules: List<RemapRule>): T = evaluate(record, rules).record

    /** As [apply], and also names the rules that recognised the record — a consequence that is not
     *  a field (see [RemapRule.alert]) is the caller's to carry out, and it needs to know which. */
    fun evaluate(record: T, rules: List<RemapRule>): RemapOutcome<T> {
        val fieldsById = matchFields.associateBy { it.id }
        val ordered = rules
            .filter { !it.trigger.isEmpty && (it.set.isNotEmpty() || it.alert) }
            .sortedWith(compareBy({ -it.trigger.demand }, { it.sortOrder }, { it.id }))
        var out = record
        val claimed = mutableSetOf<String>()
        val fired = mutableListOf<RemapRule>()
        for (rule in ordered) {
            fun holds(condition: RemapCondition): Boolean {
                val field = fieldsById[condition.fieldId] ?: return false
                val actual = field.valueOf(record)
                return when {
                    condition.op != RemapOp.EQ -> compares(field, actual, condition)
                    condition.fuzz > 0 && leveledMatcher != null ->
                        actual != null && leveledMatcher.invoke(actual, condition.value, condition.fuzz)
                    else -> RemapValueKey.normalize(actual) == condition.value
                }
            }
            val matches = rule.trigger.groups.any { group ->
                group.isNotEmpty() && group.all { holds(it) }
            }
            if (!matches) continue
            fired += rule
            for ((fieldId, value) in rule.set) {
                if (fieldId in claimed) continue
                val setter = setFields.firstOrNull { it.id == fieldId } ?: continue
                val applied = setter.apply(out, value) ?: continue
                out = applied
                claimed += fieldId
            }
        }
        return RemapOutcome(out, fired)
    }

    /**
     * An ordering comparison, which only a numeric field answers.
     *
     * Anything unreadable as a number says no rather than guessing: a record with no amount is not
     * under every threshold, it is a record the question was never about.
     */
    private fun compares(field: RemapMatchField<T>, actual: String?, condition: RemapCondition): Boolean {
        if (!field.numeric) return false
        val left = actual?.trim()?.replace(',', '.')?.toDoubleOrNull() ?: return false
        val right = condition.value.trim().replace(',', '.').toDoubleOrNull() ?: return false
        return when (condition.op) {
            RemapOp.GT -> left > right
            RemapOp.GTE -> left >= right
            RemapOp.LT -> left < right
            RemapOp.LTE -> left <= right
            RemapOp.EQ -> left == right
        }
    }
}
