package com.voxapps.expenses.data

import com.voxapps.expenses.domain.accounts.BankAccountTree
import com.voxapps.recordflow.FieldWeight
import com.voxapps.suggestions.AcceptMode
import com.voxapps.suggestions.SuggestableField
import com.voxapps.suggestions.SuggestionTarget

/**
 * Which of an expense's fields may be proposed, and what accepting one is allowed to touch.
 *
 * **The screen writes nothing.** Tapping a proposal on the edit page puts the value in the draft
 * being edited and nowhere else; the database is reached by Save, and by nothing else on that page.
 * That is what [AcceptMode.STAGES] declares, and why core never asks this target to write: an accept
 * that reached the record would survive Discard, and a change made in an editing session that its
 * own Cancel cannot take back is the one shape of edit a person has no way to undo. Core allows
 * either — [AcceptMode.WRITES] is for records edited in place. An expense is not one of those.
 *
 * The store is still what holds them, shows them and disposes of what produced them; only the
 * writing stays where the screen's own rule puts it.
 */
class ExpenseSuggestionTarget(
    private val repository: ExpensesRepository
) : SuggestionTarget {

    override val suggestableFields: List<SuggestableField> = listOf(
        // HEAD: short enough to check at a glance, so a rung allowed to write them may.
        SuggestableField(KEY_TITLE, "title", FieldWeight.HEAD),
        SuggestableField(KEY_VENDOR, "vendor", FieldWeight.HEAD),
        SuggestableField(KEY_BANK, "bank", FieldWeight.HEAD),
        SuggestableField(KEY_CATEGORY, "category", FieldWeight.HEAD),
        SuggestableField(KEY_LOCATION, "location", FieldWeight.HEAD),
        SuggestableField(KEY_AMOUNT, "amount", FieldWeight.HEAD),
        SuggestableField(KEY_CURRENCY, "currency", FieldWeight.HEAD),
        SuggestableField(KEY_DATE_TIME, "date", FieldWeight.HEAD),
        SuggestableField(KEY_COMMENTS, "comments", FieldWeight.HEAD),
        // BODY: a whole list of line items is one field, and nobody checks it at a glance.
        SuggestableField(KEY_ITEMS, "items", FieldWeight.BODY)
    )

    /**
     * What the saved record says, for core's "a proposal equal to what is there is not a
     * suggestion" rule. Compared against the record rather than the draft on purpose — the draft is
     * the screen's, and a value typed but not saved has not become what the record says yet.
     */
    override suspend fun currentValue(recordId: Long, fieldKey: String): String? {
        val e = repository.getExpenseById(recordId)?.expense ?: return null
        return when (fieldKey) {
            KEY_TITLE -> e.title
            KEY_VENDOR -> e.vendor
            KEY_BANK -> BankAccountTree.bankNameFor(e.bankAccountId, repository.bankAccountsSnapshot())
            KEY_LOCATION -> e.location
            KEY_COMMENTS -> e.comments
            KEY_AMOUNT -> e.totalAmount.toString()
            KEY_CURRENCY -> e.currencyCode
            KEY_DATE_TIME -> e.dateTime.toString()
            // Category and items are compared by the screen, which has the live category list and
            // the draft's items; neither is a column to read back.
            else -> null
        }
    }

    /**
     * The edit page holds a draft: a tapped proposal goes into it, and Save is what reaches the
     * record. Declared rather than enforced by refusing to write, so core never asks in the first
     * place — see [AcceptMode].
     */
    override val acceptMode: AcceptMode = AcceptMode.STAGES

    /**
     * The photographs a rescan was made from, once the last thing it proposed is gone. Left attached
     * they would be a scan with nothing left to offer, which is indistinguishable from a scan
     * somebody took deliberately.
     */
    override suspend fun discardSource(recordId: Long, sourceTag: String) {
        repository.deleteAttachmentGroup(recordId, sourceTag)
    }

    companion object {
        const val KEY_TITLE = "title"
        const val KEY_VENDOR = "vendor"
        const val KEY_BANK = "bank"
        const val KEY_CATEGORY = "category"
        const val KEY_LOCATION = "location"
        const val KEY_AMOUNT = "totalAmount"
        const val KEY_CURRENCY = "currencyCode"
        const val KEY_DATE_TIME = "dateTime"
        const val KEY_COMMENTS = "comments"
        const val KEY_ITEMS = "items"
    }
}
