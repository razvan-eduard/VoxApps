package com.voxapps.expenses.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.voxapps.textmatch.extract.AccountIdentifiers

/**
 * An account money moved through, or a card that reaches one.
 *
 * There is no separate notion of a bank. "ING" is not a thing anybody owns beside their accounts —
 * it is the name of one, whose IBAN may not be known yet. So a row here is either an account (its
 * name, its number where a message gave one, its currency) or a card filed under one, and nothing
 * else: two levels, and the bank is a field on the first of them.
 *
 * A number is not required for a row to exist, only for a message to *find* it. An IBAN, a card
 * number and a masked tail are published formats and are either matched or not — never guessed,
 * never learned, no vocabulary and no proposals — but an account named by a message that carried no
 * number at all is still an account, and a row it can be recognised by next time.
 */
@Entity(
    tableName = "bank_accounts",
    indices = [Index(value = ["digits"], unique = true)]
)
data class BankAccount(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    /**
     * The number it is known by: the IBAN of an account, the trailing digits of a card — or null,
     * for an account a message named without giving one.
     *
     * A card is held by its tail because that is the part every source agrees on — a receipt shows
     * sixteen digits, a notification shows two, and both have to reach one card. Keeping less is
     * also the safer failure, since a tail cannot be spent.
     */
    val digits: String? = null,

    /** Which of the three formats it was read as, or null where no number was read at all — see
     *  [AccountIdentifiers.Kind]. */
    val kind: String? = null,

    /**
     * The account this card belongs to, where somebody has said so.
     *
     * One level, and optional at every point: a card with no account is the ordinary case, since a
     * notification only ever carries a tail and nothing in it says which account the card draws on.
     * An account with no cards is equally ordinary.
     *
     * Never inferred. A document naming an account and a card together is as likely to be a payment
     * *from* one *to* the other as it is to be a statement listing both, and this whole feature is
     * built on not guessing — so the link is something a person makes.
     */
    val parentId: Long? = null,

    /** What the person calls it. Empty until they name it; the digits stand in until then. */
    val label: String? = null,

    /**
     * The currency this account is denominated in.
     *
     * What it is *not* is the currency of everything filed against it. A card denominated here can
     * be charged abroad, and that record keeps the currency it was charged in — so a budget is keyed
     * by account *and* currency (see [AccountBudget]), an account can carry one per currency it sees,
     * and [com.voxapps.expenses.domain.budget.BudgetMath] matches a record against the budget's
     * currency rather than against this one. Matching on this would subtract 100 EUR from a RON
     * budget as though it were 100 RON.
     *
     * Its work is elsewhere: it is part of what the app has been told it deals in, which is what
     * lets a capture resolve a spelling that names several currencies — "lei" reads as RON on an
     * install whose accounts are in RON (see [com.voxapps.textmatch.extract.CurrencyCodes]) — and it
     * is the currency offered first when a budget is added here. New accounts take the default from
     * settings so an install with one currency never has to answer the question.
     */
    val currencyCode: String,

    /**
     * The bank it belongs to, when a message named one.
     *
     * Filled from the vocabulary reading of the same message that carried the digits, never from
     * the digits themselves — a card number's issuer range is not something this app decodes.
     */
    val bankName: String? = null,

    /** A short piece of text identifying it in a list, as a category has — see [Category.icon]. */
    val icon: String? = null,

    val createdAt: Long,

    /**
     * Whether the app made this by itself, or a person did.
     *
     * Kept because the two are undone differently: deleting one somebody created is deleting their
     * work, while deleting one the app created is switching the toggle off after the fact.
     */
    val autoCreated: Boolean = false,
    /**
     * Whether this card or account is still in use.
     *
     * A card expires and comes back with four different digits, under the same account: two rows,
     * both real, only one of them yours to spend from. Deleting the old one would take its records'
     * only account with it, so it is archived instead — kept, still matched by a message that names
     * it, still counted by its account's budget, and simply no longer offered anywhere the question
     * is "which card is this going to be".
     *
     * A fact about presentation, deliberately not about resolution: identity is identity, and a
     * notification carrying an archived card's digits belongs to that card rather than to a new row
     * with the same number.
     */
    val archived: Boolean = false
) {
    /** Whether this row is an account rather than a card under one. */
    val isAccount: Boolean get() = parentId == null

    /**
     * This row as a reading, so stored rows and freshly-read ones compare the same way — or null
     * where there is no number, which no reading can ever match.
     */
    fun asRef(): AccountIdentifiers.AccountRef? {
        val number = digits?.takeIf { it.isNotBlank() } ?: return null
        return AccountIdentifiers.AccountRef(
            kind = runCatching { AccountIdentifiers.Kind.valueOf(kind.orEmpty()) }
                .getOrDefault(AccountIdentifiers.Kind.CARD_TAIL),
            digits = number
        )
    }

    /** What a person calls it: their own name for it, else the bank's, else its number. */
    fun name(): String = label?.takeIf { it.isNotBlank() }
        ?: bankName?.takeIf { it.isNotBlank() }
        ?: digits?.takeIf { it.isNotBlank() }?.let { defaultLabel(kind, it) }
        ?: UNNAMED

    /**
     * The first line of a row in a list: what it is called, and — for an account — the currency it
     * is denominated in, which is the one thing about an account you cannot work out from its name.
     */
    fun title(): String =
        if (isAccount && currencyCode.isNotBlank()) "${name()} (${currencyCode.uppercase()})" else name()

    /**
     * The second line: the number, where there is one and it is not already the name. An account
     * shows its IBAN in full — it is what a person compares against a statement — and a card shows
     * its tail behind a mask.
     */
    fun subtitle(): String? {
        val number = digits?.takeIf { it.isNotBlank() } ?: return null
        val shown = if (isAccount) number else "••${number.takeLast(4)}"
        return shown.takeIf { it != name() }
    }

    /**
     * What to show where it has to name itself.
     *
     * What you called it, else the bank and the last four — "ING ••4535" is what a person recognises,
     * and 24 characters of IBAN is what they scroll past. The number in full remains for a row
     * nothing else identifies: it is then the only thing there is to know it by.
     */
    fun displayName(): String = label?.takeIf { it.isNotBlank() }
        ?: bankName?.takeIf { it.isNotBlank() }?.let { bank ->
            digits?.takeIf { it.isNotBlank() }?.let { "$bank ••${it.takeLast(4)}" } ?: bank
        }
        ?: digits?.takeIf { it.isNotBlank() }?.let { defaultLabel(kind, it) }
        ?: UNNAMED

    companion object {
        /** A row with no name and no number — nothing a person could recognise, and nothing this
         *  app creates; only a hand-made row emptied of everything can reach it. */
        const val UNNAMED = "—"

        /**
         * The name an unnamed account wears: an IBAN in full, a card as its tail behind a mask.
         *
         * Written the way the message that produced it was written, so a person recognises the
         * account from the notification they remember rather than from a number they never saw.
         */
        fun defaultLabel(kind: String?, digits: String): String =
            if (kind == AccountIdentifiers.Kind.IBAN.name) digits else "••$digits"
    }
}
