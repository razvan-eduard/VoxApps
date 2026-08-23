package com.voxapps.expenses.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.voxapps.textmatch.extract.AccountIdentifiers

/**
 * A card or account money moved through.
 *
 * Unlike a bank or a merchant, this is never learned: an IBAN, a card number and a masked tail are
 * published formats, so a message either names one or does not — see [AccountIdentifiers]. There is
 * therefore no vocabulary here, nothing to teach, no list supplied with the app and no proposal to
 * accept. A record either matched a format or it did not.
 */
@Entity(
    tableName = "bank_accounts",
    indices = [Index(value = ["digits"], unique = true)]
)
data class BankAccount(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    /**
     * What identifies it: the IBAN, or a card's trailing digits.
     *
     * A card is held by its tail because that is the part every source agrees on — a receipt shows
     * sixteen digits, a notification shows two, and both have to reach one account. Keeping less is
     * also the safer failure, since a tail cannot be spent.
     */
    val digits: String,

    /** Which of the three formats it was read as — see [AccountIdentifiers.Kind]. */
    val kind: String,

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
     * The one currency this account holds.
     *
     * One per account rather than one per record: a card is denominated, and a record filed against
     * it in another currency is a conversion rather than a second currency the account has. New
     * accounts take the default from settings so an install with one currency never has to answer
     * the question.
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
    val autoCreated: Boolean = false
) {
    /** This account as a reading, so stored accounts and freshly-read ones compare the same way. */
    fun asRef(): AccountIdentifiers.AccountRef =
        AccountIdentifiers.AccountRef(
            kind = runCatching { AccountIdentifiers.Kind.valueOf(kind) }
                .getOrDefault(AccountIdentifiers.Kind.CARD_TAIL),
            digits = digits
        )

    /** What to show where it has to name itself: what it was called, or what it is. */
    fun displayName(): String = label?.takeIf { it.isNotBlank() } ?: defaultLabel(kind, digits)

    companion object {
        /**
         * The name an unnamed account wears: an IBAN in full, a card as its tail behind a mask.
         *
         * Written the way the message that produced it was written, so a person recognises the
         * account from the notification they remember rather than from a number they never saw.
         */
        fun defaultLabel(kind: String, digits: String): String =
            if (kind == AccountIdentifiers.Kind.IBAN.name) digits else "••$digits"
    }
}
