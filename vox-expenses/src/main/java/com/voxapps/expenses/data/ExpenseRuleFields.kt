package com.voxapps.expenses.data

import com.voxapps.expenses.domain.accounts.BankAccountTree
import com.voxapps.datahygiene.FuzzyMatcher
import com.voxapps.datahygiene.RuleField
import com.voxapps.datahygiene.exactField
import com.voxapps.datahygiene.stringField
import com.voxapps.datahygiene.timeWindowField
import com.voxapps.textmatch.FuzzyNameMatcher

/**
 * The candidate fields a user can build [com.voxapps.datahygiene.DuplicateRule]s from, in the
 * duplicate-rules UI. Excludes `id`/`uid`/`createdAt`/`updatedAt`/`receiptImageName`/`isStub` — the
 * same identity/audit fields the old `ExpenseDuplicateChecker` deliberately excluded, since none of
 * them describe the real-world transaction itself. One line per field via `:core:datahygiene`'s
 * builders — adding a new comparable field, or porting this same registry shape to another entity
 * (Note, CalendarEntry, ...), is exactly this: no bespoke comparison logic to write.
 *
 * [fuzzyMatchEnabled] applies to every string field uniformly — one shared toggle, not a per-field
 * choice, to keep the rule-builder UI to exactly two axes (which fields, AND/OR). [timeWindowMillis]
 * is [dateTime]'s tolerance — a selectable field like any other now, not an unconditional
 * prerequisite (previously every near-duplicate check silently required a same-window match no
 * matter what; now that's only true if a rule actually includes it).
 */
class ExpenseRuleFields(
    fuzzyMatchEnabled: Boolean,
    timeWindowMillis: Long,
    /** The accounts, so a rule about a bank can be answered: a record carries the account it went
     *  through, and the bank is that account's name. Empty leaves the field answering null, which
     *  is what a screen listing the available fields wants anyway. */
    private val accounts: List<BankAccount> = emptyList()
) {
    private val fuzzyMatcher = FuzzyMatcher(FuzzyNameMatcher::namesMatch)

    val all: List<RuleField<Expense>> = listOf(
        stringField(ID_TITLE, "duplicate_rule_field_title", fuzzyMatchEnabled, fuzzyMatcher) { it.title },
        stringField(ID_VENDOR, "duplicate_rule_field_vendor", fuzzyMatchEnabled, fuzzyMatcher) { it.vendor },
        stringField(ID_BANK, "duplicate_rule_field_bank", fuzzyMatchEnabled, fuzzyMatcher) {
            BankAccountTree.bankNameFor(it.bankAccountId, accounts)
        },
        stringField(ID_LOCATION, "duplicate_rule_field_location", fuzzyMatchEnabled, fuzzyMatcher) { it.location },
        stringField(ID_COMMENTS, "duplicate_rule_field_comments", fuzzyMatchEnabled, fuzzyMatcher) { it.comments },
        exactField(ID_TOTAL_AMOUNT, "duplicate_rule_field_amount") { it.totalAmount },
        exactField(ID_CURRENCY_CODE, "duplicate_rule_field_currency") { it.currencyCode },
        exactField(ID_CATEGORY_ID, "duplicate_rule_field_category") { it.categoryId },
        exactField(ID_DIRECTION, "duplicate_rule_field_direction") { it.direction },
        timeWindowField(ID_DATE_TIME, "duplicate_rule_field_date_time", timeWindowMillis) { it.dateTime }
    )

    companion object {
        const val ID_TITLE = "title"
        const val ID_VENDOR = "vendor"
        const val ID_BANK = "bank"
        const val ID_LOCATION = "location"
        const val ID_COMMENTS = "comments"
        const val ID_TOTAL_AMOUNT = "totalAmount"
        const val ID_CURRENCY_CODE = "currencyCode"
        const val ID_CATEGORY_ID = "categoryId"
        const val ID_DIRECTION = "direction"
        const val ID_DATE_TIME = "dateTime"
    }
}
