package com.voxapps.expenses.data

import com.voxapps.datahygiene.RemapEngine
import com.voxapps.datahygiene.RemapMatchField
import com.voxapps.datahygiene.RemapSetField
import com.voxapps.textmatch.FuzzyNameMatcher

/**
 * The candidate fields for [com.voxapps.datahygiene.RemapEngine] rules on a newly captured expense
 * — mirrors [ExpenseRuleFields]' registry shape for the duplicate engine. Every non-numeric field
 * participates on both sides; the deliberate absences are the transaction's facts
 * (`totalAmount`/`currencyCode`/`dateTime` — a rule silently rewriting facts is a falsification,
 * not a re-map of descriptive identity) and `direction`, owned by the template memory
 * (see [com.voxapps.expenses.domain.llm.TemplateDirectionMemory]) — two writers for one field
 * would make the record depend on evaluation order.
 *
 * The engine runs before category resolution, so rules operate on [Draft] — the parsed values as
 * they arrive. Category appears twice, on purpose: as the TRIGGER field [ID_CATEGORY] it is the
 * parsed category NAME (all a pre-resolution draft has); as the SET field [ID_CATEGORY_ID] it is
 * a resolved id whose setter validates against the live category list and declines an id that no
 * longer exists, which is what lets [ExpensesRepository.addParsedExpense] fall through to normal
 * resolution.
 */
object ExpenseRemapFields {

    const val ID_TITLE = ExpenseRuleFields.ID_TITLE
    const val ID_VENDOR = ExpenseRuleFields.ID_VENDOR
    const val ID_BANK = ExpenseRuleFields.ID_BANK
    const val ID_LOCATION = ExpenseRuleFields.ID_LOCATION
    const val ID_COMMENTS = ExpenseRuleFields.ID_COMMENTS
    const val ID_CATEGORY = "category"
    const val ID_AMOUNT = "totalAmount"
    const val ID_CATEGORY_ID = ExpenseRuleFields.ID_CATEGORY_ID

    data class Draft(
        /** Match-only, and never written — see the class note on why facts are not re-mapped. */
        val totalAmount: Double? = null,
        val title: String?,
        val vendor: String?,
        val bank: String?,
        val location: String?,
        val comments: String?,
        val category: String?,
        val categoryId: Long? = null
    )

    /**
     * An amount as text the engine can compare.
     *
     * The engine matches normalized strings, so a number needs one spelling or 160 and 160.00 are
     * different triggers. Cents, as a plain integer: no separator to disagree about, no rounding to
     * argue with, and equality means the same thing on both sides. The editor writes the user's
     * typing through the same function, so what is stored is what a record will be compared against.
     */
    fun amountKey(value: Double?): String? =
        value?.takeIf { it > 0.0 }?.let { Math.round(it * 100).toString() }

    /** Parses what a person typed — "160", "160.5", "160,50" — into [amountKey]'s form. */
    fun amountKeyOf(typed: String): String? =
        amountKey(typed.trim().replace(',', '.').toDoubleOrNull())

    val matchFields: List<RemapMatchField<Draft>> = listOf(
        // Triggering on an amount is not re-mapping it: the rule reads the figure and writes
        // something descriptive. Setting one would be a falsification, which is why this field
        // appears here and has no counterpart in setFields.
        RemapMatchField(ID_AMOUNT, "duplicate_rule_field_amount") { amountKey(it.totalAmount) },
        RemapMatchField(ID_TITLE, "duplicate_rule_field_title") { it.title },
        RemapMatchField(ID_VENDOR, "duplicate_rule_field_vendor") { it.vendor },
        RemapMatchField(ID_BANK, "duplicate_rule_field_bank") { it.bank },
        RemapMatchField(ID_LOCATION, "duplicate_rule_field_location") { it.location },
        RemapMatchField(ID_COMMENTS, "duplicate_rule_field_comments") { it.comments },
        RemapMatchField(ID_CATEGORY, "duplicate_rule_field_category") { it.category }
    )

    fun setFields(categories: List<Category>): List<RemapSetField<Draft>> = listOf(
        RemapSetField(ID_TITLE, "duplicate_rule_field_title") { d, v -> d.copy(title = v) },
        RemapSetField(ID_VENDOR, "duplicate_rule_field_vendor") { d, v -> d.copy(vendor = v) },
        RemapSetField(ID_BANK, "duplicate_rule_field_bank") { d, v -> d.copy(bank = v) },
        RemapSetField(ID_LOCATION, "duplicate_rule_field_location") { d, v -> d.copy(location = v) },
        RemapSetField(ID_COMMENTS, "duplicate_rule_field_comments") { d, v -> d.copy(comments = v) },
        RemapSetField(ID_CATEGORY_ID, "duplicate_rule_field_category") { d, v ->
            v.toLongOrNull()?.takeIf { id -> categories.any { it.id == id } }?.let { d.copy(categoryId = it) }
        }
    )

    fun engine(categories: List<Category>): RemapEngine<Draft> =
        RemapEngine(matchFields, setFields(categories), FuzzyNameMatcher::namesMatchLeveled)
}
