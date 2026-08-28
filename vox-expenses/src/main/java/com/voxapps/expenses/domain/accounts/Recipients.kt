package com.voxapps.expenses.domain.accounts

import com.voxapps.expenses.data.Recipient

/**
 * How a recipient is recognised — the counterparty-side twin of [BankAccounts], and the same
 * discipline throughout: a match is exact or it is nothing, and nothing is ever guessed. An IBAN
 * either equals a stored one or it does not (checksums were already enforced by whoever extracted
 * it — see [com.voxapps.textmatch.extract.AccountIdentifiers]); a name either singles out one
 * active row or it names nobody.
 *
 * There is no widening here, because there is nothing to widen: cards lengthen from masked tails
 * to full numbers, IBANs arrive whole or not at all. The one asymmetry an IBAN has is absence —
 * a row made by hand before any document carried the number — and [fillsIban] covers exactly that:
 * fill the empty slot, never overwrite a different one.
 */
object Recipients {

    /** The row this IBAN identifies — exact match, case-insensitive, or nothing. Archived rows
     *  still match: a number names its owner whether or not the owner is still being offered. */
    fun byIban(iban: String?, existing: List<Recipient>): Recipient? {
        val wanted = iban?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return existing.filter { it.iban.equals(wanted, ignoreCase = true) }.singleOrNull()
    }

    /** The row this name alone identifies: exactly one ACTIVE row, or nothing — archived rows
     *  neither match nor count toward ambiguity, the [BankAccounts.accountNamed] rule. */
    fun named(name: String?, existing: List<Recipient>): Recipient? {
        val wanted = name?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        return existing
            .filter { !it.archived && it.name.trim().lowercase() == wanted }
            .singleOrNull()
    }

    /** Whether a name-matched row should take this document's IBAN: only while it has none.
     *  A row that already carries a different number was matched by name against a document naming
     *  another account — that is a fact to leave visible, not to paper over. */
    fun fillsIban(stored: Recipient, iban: String?): Boolean =
        stored.iban.isNullOrBlank() && !iban.isNullOrBlank()

    fun newRecipient(name: String, bankName: String?, iban: String?, nowMillis: Long): Recipient =
        Recipient(
            name = name.trim(),
            bankName = bankName?.trim()?.takeIf { it.isNotEmpty() },
            iban = iban?.trim()?.takeIf { it.isNotEmpty() },
            createdAt = nowMillis,
            autoCreated = true
        )
}
