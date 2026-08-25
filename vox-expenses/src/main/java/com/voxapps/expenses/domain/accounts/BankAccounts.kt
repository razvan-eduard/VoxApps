package com.voxapps.expenses.domain.accounts

import com.voxapps.expenses.data.BankAccount
import com.voxapps.textmatch.extract.AccountIdentifiers

/**
 * Turning what a text says about an account into the account itself.
 *
 * The reading is [AccountIdentifiers]' job and needs nothing from this app. What is left is the part
 * that needs the records: whether the account read is one already known, and whether an unknown one
 * is allowed to become a record. Both input paths — a notification and a scan — come through here,
 * so a card cannot mean one thing when it arrives one way and another when it arrives the other.
 */
object BankAccounts {

    /** What a text turned out to name, once the existing accounts are taken into account. */
    sealed interface Outcome {
        /** Nothing in the text carried an account's format. */
        data object None : Outcome

        /** An account already on file. */
        data class Known(val account: BankAccount) : Outcome

        /**
         * A format-verified account not on file.
         *
         * Carried as a candidate rather than silently created, because whether it becomes a record
         * is a setting rather than a fact — see [shouldCreate].
         */
        data class Unknown(val ref: AccountIdentifiers.AccountRef) : Outcome
    }

    /**
     * Which account [text] names, against [existing].
     *
     * Exactly one, as everywhere a reading is turned into a claim: a message naming two accounts has
     * identified neither, and picking the first would be picking by position in a sentence. A
     * transfer between two of a person's own accounts is exactly that case, and guessing which one
     * the record belongs to would file half of them backwards.
     */
    fun resolve(text: String?, existing: List<BankAccount>): Outcome {
        val ref = AccountIdentifiers.single(text) ?: return Outcome.None
        val known = existing.filter { it.asRef()?.sameAs(ref) == true }
        // Two stored accounts matching one reading means the tail is too short to tell them apart —
        // "••00" against two cards ending 00. Claiming either would be a coin toss.
        if (known.size > 1) return Outcome.None
        return known.firstOrNull()?.let { Outcome.Known(it) } ?: Outcome.Unknown(ref)
    }

    /**
     * Whether an unknown account may be written, given where the text came from.
     *
     * Not a confidence question — the format either matched or it did not. It is only whether the
     * app may add to a list without being asked, which a person answers per source: a scan is
     * something they deliberately photographed, a notification arrives on its own.
     */
    fun shouldCreate(fromScan: Boolean, scansEnabled: Boolean, notificationsEnabled: Boolean): Boolean =
        if (fromScan) scansEnabled else notificationsEnabled

    /**
     * The record an unknown account becomes.
     *
     * [bankName] comes from the vocabulary reading of the same text, never from the digits: a card
     * number's issuer range is not something this app decodes, and a message that named a bank
     * beside the card has already answered the question.
     */
    fun newAccount(
        ref: AccountIdentifiers.AccountRef,
        currencyCode: String,
        bankName: String?,
        nowMillis: Long
    ): BankAccount = BankAccount(
        digits = ref.digits,
        kind = ref.kind.name,
        currencyCode = currencyCode,
        bankName = bankName?.takeIf { it.isNotBlank() },
        createdAt = nowMillis,
        autoCreated = true
    )

    /**
     * The account a newly seen card belongs under, when only one answer is possible.
     *
     * A card arrives naming its bank, and an account of that bank is where its money comes from — so
     * where exactly one such account exists, there is nothing to decide and leaving the card loose
     * would only mean asking a question with one answer. Two accounts at the same bank is a real
     * ambiguity and the card stays where it landed: the name of the bank cannot say which of them,
     * and the one thing worse than an unparented card is a card silently spending the wrong
     * account's budget.
     *
     * Only cards. An IBAN names an account outright and is not a way of reaching another one.
     */
    fun soleAccountOf(bankName: String?, kind: AccountIdentifiers.Kind, existing: List<BankAccount>): BankAccount? =
        if (kind == AccountIdentifiers.Kind.IBAN) null else accountNamed(bankName, existing)

    /**
     * The account a bank's name alone identifies.
     *
     * Exactly one, or nothing — the rule every reading in this app follows. Somebody with two ING
     * accounts has not been told which by a message that says "ING", and choosing either would file
     * half their records against the wrong one.
     *
     * An archived account does not count as one of them: two rows where one is retired still leaves
     * one place for this to mean.
     */
    fun accountNamed(bankName: String?, existing: List<BankAccount>): BankAccount? {
        val bank = bankName?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        return existing
            .filter { !it.archived && it.isAccount && it.bankName?.trim()?.lowercase() == bank }
            .singleOrNull()
    }

    /**
     * The account a bank named by itself becomes.
     *
     * No number, because the message gave none — and that is not a lesser kind of row. It is the
     * account at that bank, findable by name from now on, and the IBAN is written onto it the day a
     * message finally carries one.
     */
    fun newBankAccount(bankName: String, currencyCode: String, nowMillis: Long): BankAccount =
        BankAccount(
            digits = null,
            kind = null,
            currencyCode = currencyCode,
            bankName = bankName.trim(),
            createdAt = nowMillis,
            autoCreated = true
        )

    /**
     * Whether a stored account should take a fuller spelling of itself.
     *
     * A card first seen as "••00" and later as a full number is the same card known better. Widening
     * it keeps one row rather than two, and means the next two-digit message still finds it — a
     * longer tail still ends with the shorter one.
     */
    fun widens(stored: BankAccount, ref: AccountIdentifiers.AccountRef): Boolean =
        stored.asRef()?.sameAs(ref) == true &&
            ref.kind != AccountIdentifiers.Kind.IBAN &&
            ref.digits.length > stored.digits.orEmpty().length
}
