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
        rootsOf(all).flatMap { root ->
            listOf(Entry(root, depth = 0)) + childrenOf(root.id, all).map { Entry(it, depth = 1) }
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
        candidateParent.parentId != null -> false
        childrenOf(child.id, all).isNotEmpty() -> false
        else -> true
    }
}
