package com.voxapps.expenses.domain.accounts

import com.voxapps.expenses.data.BankAccount

/**
 * An account and the cards that draw on it, treated as one thing where it matters.
 *
 * The nesting exists because that is how the money works: a card is a way of reaching an account,
 * so spending on the card is spending from the account. Anything asking "what went through this
 * account" therefore has to mean the cards too — otherwise the answer excludes almost all of it,
 * since a notification names a card and never the account behind it.
 *
 * One level deep by construction: a card belongs to an account, and an account belongs to nothing.
 * The walk below still guards against a cycle, because a parent is a stored id and a stored id can
 * be made to point anywhere by an edit, a restore, or a sync.
 */
object BankAccountTree {

    /**
     * Every account id a filter on [accountId] should match: the account itself and its cards.
     *
     * A card filters to itself alone — asking about one card is asking about that card, and widening
     * it to its siblings would answer a question nobody asked.
     */
    fun familyOf(accountId: Long, all: List<BankAccount>): Set<Long> {
        val family = mutableSetOf(accountId)
        var frontier = listOf(accountId)
        // Bounded by the number of rows rather than by the shape of the tree, so a parent pointing
        // at its own descendant terminates instead of walking for ever.
        repeat(all.size) {
            val next = all.filter { it.parentId in frontier && it.id !in family }.map { it.id }
            if (next.isEmpty()) return family
            family += next
            frontier = next
        }
        return family
    }

    /**
     * The account a row belongs to: itself if it is one, else the account its card hangs under.
     *
     * Bounded by the number of rows, like [familyOf], so a cycle somebody made by hand cannot hang
     * the walk.
     */
    fun rootOf(account: BankAccount, all: List<BankAccount>): BankAccount {
        var current = account
        repeat(all.size) {
            val parent = current.parentId?.let { id -> all.firstOrNull { it.id == id } } ?: return current
            current = parent
        }
        return current
    }

    /** The bank a row is with — the name of the account it belongs to. A record's bank is this and
     *  nothing else: there is no bank apart from the account it names. */
    fun bankNameOf(account: BankAccount, all: List<BankAccount>): String? =
        rootOf(account, all).let { it.bankName?.takeIf { n -> n.isNotBlank() } ?: it.label?.takeIf { l -> l.isNotBlank() } }

    /**
     * The bank a record is with, from the row it points at.
     *
     * The record keeps no bank of its own: the bank is the name of the account, so a record that
     * points at nothing is with no bank. Everything that used to read a column asks this instead.
     */
    fun bankNameFor(pointedAt: Long?, all: List<BankAccount>): String? {
        val row = pointedAt?.let { id -> all.firstOrNull { it.id == id } } ?: return null
        return bankNameOf(row, all)
    }

    /**
     * The account and the card a record's single pointer stands for.
     *
     * A record names one row, because one row is what a message identifies — but a person thinks in
     * two, "which account, and on which card". A pointer at a card is both: the card, and the
     * account it hangs under.
     */
    data class Chosen(val accountId: Long?, val cardId: Long?)

    fun chosen(pointedAt: Long?, all: List<BankAccount>): Chosen {
        val row = pointedAt?.let { id -> all.firstOrNull { it.id == id } } ?: return Chosen(null, null)
        return if (row.isAccount) Chosen(row.id, null) else Chosen(row.parentId, row.id)
    }

    /** The cards under [accountId], in the order they are stored. */
    fun childrenOf(accountId: Long, all: List<BankAccount>): List<BankAccount> =
        all.filter { it.parentId == accountId }

    /** Accounts nothing sits under — the roots a list is drawn from. */
    fun rootsOf(all: List<BankAccount>): List<BankAccount> {
        val ids = all.map { it.id }.toSet()
        // A row whose parent is missing is a root rather than invisible: losing an account should
        // not hide the cards that pointed at it.
        return all.filter { it.parentId == null || it.parentId !in ids }
    }

    /**
     * The whole list as it is shown: each root, then its cards.
     *
     * Flattened rather than nested so a caller renders one list and reads the depth off each entry,
     * which is what keeps a settings row and a picklist row the same shape.
     */
    fun display(all: List<BankAccount>): List<Entry> =
        // What is still in use first, at both levels: a replaced card belongs under the account it
        // was on, but under the card that replaced it.
        rootsOf(all).sortedBy { it.archived }.flatMap { root ->
            listOf(Entry(root, depth = 0)) +
                childrenOf(root.id, all).sortedBy { it.archived }.map { Entry(it, depth = 1) }
        }

    data class Entry(val account: BankAccount, val depth: Int)

    /**
     * Whether [candidateParent] may become [child]'s account.
     *
     * A card cannot hold cards, nothing may parent itself, and an account already holding cards
     * cannot become a card — each would make a shape the one-level rule does not describe, and the
     * filter that walks it would start answering questions it was not asked.
     */
    fun canParent(child: BankAccount, candidateParent: BankAccount, all: List<BankAccount>): Boolean = when {
        child.id == candidateParent.id -> false
        // Filing a card under an account nobody uses any more is filing it nowhere.
        candidateParent.archived -> false
        candidateParent.parentId != null -> false
        childrenOf(child.id, all).isNotEmpty() -> false
        else -> true
    }
}
