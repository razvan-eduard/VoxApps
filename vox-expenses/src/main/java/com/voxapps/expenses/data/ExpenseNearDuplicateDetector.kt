package com.voxapps.expenses.data

import com.voxapps.datahygiene.DuplicateChecker
import com.voxapps.datahygiene.FieldCleaner
import com.voxapps.textmatch.FuzzyNameMatcher
import kotlin.math.abs

/**
 * Direct-DB-level (non-AI) near-duplicate detector, layered *alongside* [ExpenseDuplicateChecker] —
 * never replaces it, and is only ever consulted once that exact checker finds nothing. Catches what
 * an all-fields-exact checker structurally cannot: two different capture sources describing the same
 * real-world transaction with different title/vendor wording, recorded a short time apart rather than
 * at the identical instant.
 *
 * Deliberately does NOT compare [Expense.bank]/[Expense.location]/[Expense.categoryId] — those are
 * exactly the fields two different sources are expected to disagree on (their own app identity, their
 * own guessed category), so requiring them to match would defeat the point of this check.
 */
class ExpenseNearDuplicateDetector(
    private val fuzzyMatchEnabled: Boolean,
    private val timeWindowMillis: Long
) : DuplicateChecker<Expense> {

    override fun isDuplicateOf(candidate: Expense, existing: Expense): Boolean {
        if (candidate.totalAmount != existing.totalAmount) return false
        if (candidate.currencyCode != existing.currencyCode) return false
        if (candidate.direction != existing.direction) return false
        if (abs(candidate.dateTime - existing.dateTime) > timeWindowMillis) return false

        val candidateName = FieldCleaner.clean(candidate.title) ?: FieldCleaner.clean(candidate.vendor) ?: return false
        val existingName = FieldCleaner.clean(existing.title) ?: FieldCleaner.clean(existing.vendor) ?: return false

        return if (fuzzyMatchEnabled) {
            FuzzyNameMatcher.namesMatch(candidateName, existingName)
        } else {
            candidateName.equals(existingName, ignoreCase = true)
        }
    }
}

/**
 * Fills any blank/missing field on [existing] with [candidate]'s value for that same field — never
 * overwrites a field [existing] already has real content in, so the first-arrived record stays
 * authoritative wherever it already says something and only gains data it was missing. Excludes
 * identity/audit fields and every field the detector already required to match exactly (amount,
 * currency, direction, dateTime) — those are never "missing data" to enrich. Bumps [Expense.updatedAt]
 * only when something actually changed.
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
