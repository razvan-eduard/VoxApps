package com.voxapps.expenses.domain.llm

import com.voxapps.expenses.data.Expense
import com.voxapps.textmatch.FuzzyNameMatcher

/**
 * Whether a name a capture could not place is another spelling of one already accepted.
 *
 * The alternative it exists to avoid: offering to add every rendering of one shop to the list, a
 * branch code at a time, until the list is a worse copy of the ledger. A shop already named needs no
 * second entry — it needs the new spelling pointed at the name already there.
 *
 * Fuzzy matching is allowed here, and is not allowed where a field is claimed. The difference is
 * what a wrong answer costs. Claiming a field on a bad match mislabels a record silently and stops
 * the other field resolving too; being wrong here shows a person a rename they can decline by not
 * touching it. So the same matcher that would be reckless one step earlier is the right tool one
 * step later — see [com.voxapps.textmatch.extract.TwoFieldPreParse], which matches on whole tokens
 * for exactly that reason.
 */
object NameAlreadyKnown {

    /**
     * Names worth comparing against, drawn from records rather than from a list.
     *
     * Only what someone has vouched for: a record they edited by hand, or one filed by a capture
     * that resolved the field deterministically. The vendor column also holds whatever a looser rule
     * once wrote there — a transfer sentence taken for a shop — and proposing to rename a real
     * merchant to a sentence would be worse than proposing nothing.
     */
    fun vouchedNames(records: List<Expense>, of: (Expense) -> String?): List<String> =
        records.asSequence()
            .filter { it.manuallyEdited }
            .mapNotNull { of(it)?.trim()?.takeIf { name -> name.isNotBlank() } }
            .distinct()
            .toList()

    /**
     * The accepted name [candidate] is another spelling of, or null.
     *
     * Exactly one, as everywhere in this reading: a candidate that resembles two accepted names has
     * not identified either, and picking the first would be picking by list order. An exact match
     * returns null too — there is nothing to rename, the capture simply did not look it up.
     */
    fun match(candidate: String?, accepted: Collection<String>, level: Int): String? {
        val name = candidate?.trim()?.takeIf { it.isNotBlank() } ?: return null
        if (accepted.any { it.equals(name, ignoreCase = true) }) return null
        val hits = accepted.filter { FuzzyNameMatcher.namesMatchLeveled(name, it, level) }.distinct()
        return hits.singleOrNull()
    }
}
