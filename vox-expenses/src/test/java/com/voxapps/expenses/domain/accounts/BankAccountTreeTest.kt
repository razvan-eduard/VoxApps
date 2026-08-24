package com.voxapps.expenses.domain.accounts

import com.voxapps.expenses.data.BankAccount
import com.voxapps.textmatch.extract.AccountIdentifiers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * An account and the cards that draw on it, treated as one thing where it matters.
 *
 * Spending on a card is spending from the account behind it, so a filter on the account that
 * excluded its cards would exclude nearly everything — a notification names a card and never the
 * account.
 */
class BankAccountTreeTest {

    private fun account(id: Long, digits: String, parent: Long? = null) = BankAccount(
        id = id, digits = digits, kind = AccountIdentifiers.Kind.IBAN.name,
        parentId = parent, currencyCode = "RON", createdAt = 0L
    )

    private fun card(id: Long, digits: String, parent: Long? = null) = BankAccount(
        id = id, digits = digits, kind = AccountIdentifiers.Kind.CARD_TAIL.name,
        parentId = parent, currencyCode = "RON", createdAt = 0L
    )

    private val iban = account(1, "RO49AAAA1B31007593840000")
    private val cardA = card(2, "4535", parent = 1)
    private val cardB = card(3, "9999", parent = 1)
    private val loose = card(4, "1234")
    private val all = listOf(iban, cardA, cardB, loose)

    @Test
    fun `an account reaches its cards`() {
        assertEquals(setOf(1L, 2L, 3L), BankAccountTree.familyOf(1L, all))
    }

    /** Asking about one card is asking about that card, not about its siblings. */
    @Test
    fun `a card reaches only itself`() {
        assertEquals(setOf(2L), BankAccountTree.familyOf(2L, all))
    }

    @Test
    fun `a card belonging to nothing reaches only itself`() {
        assertEquals(setOf(4L), BankAccountTree.familyOf(4L, all))
    }

    @Test
    fun `an id nothing matches reaches only itself`() {
        assertEquals(setOf(99L), BankAccountTree.familyOf(99L, all))
    }

    /** A stored parent can be made to point anywhere by an edit, a restore or a sync. */
    @Test
    fun `a cycle terminates instead of walking for ever`() {
        val a = card(1, "11", parent = 2)
        val b = card(2, "22", parent = 1)
        assertEquals(setOf(1L, 2L), BankAccountTree.familyOf(1L, listOf(a, b)))
    }

    // --- what a list shows ---

    @Test
    fun `roots are the accounts nothing sits under`() {
        assertEquals(listOf(1L, 4L), BankAccountTree.rootsOf(all).map { it.id })
    }

    /** Losing an account must not hide the cards that pointed at it. */
    @Test
    fun `a card whose account is gone becomes a root`() {
        val orphan = card(5, "7777", parent = 404)
        assertTrue(BankAccountTree.rootsOf(listOf(orphan)).map { it.id }.contains(5L))
    }

    @Test
    fun `the display is each account then its cards`() {
        val shown = BankAccountTree.display(all)
        assertEquals(listOf(1L, 2L, 3L, 4L), shown.map { it.account.id })
        assertEquals(listOf(0, 1, 1, 0), shown.map { it.depth })
    }

    @Test
    fun `every account appears exactly once`() {
        val shown = BankAccountTree.display(all)
        assertEquals(all.size, shown.size)
        assertEquals(all.map { it.id }.toSet(), shown.map { it.account.id }.toSet())
    }

    // --- which links may be made ---

    @Test
    fun `a loose card may join an account`() {
        assertTrue(BankAccountTree.canParent(loose, iban, all))
    }

    @Test
    fun `nothing may parent itself`() {
        assertFalse(BankAccountTree.canParent(iban, iban, all))
    }

    /** One level: a card cannot hold cards. */
    @Test
    fun `a card may not become an account's account`() {
        assertFalse(BankAccountTree.canParent(loose, cardA, all))
    }

    @Test
    fun `an account holding cards may not become one`() {
        assertFalse(BankAccountTree.canParent(iban, account(9, "OTHER"), all))
    }

    /** Nothing new is filed under an account nobody uses any more. */
    @Test
    fun `an archived account may not be a parent`() {
        val retired = BankAccount(id = 1L, digits = "RO49", kind = "IBAN", currencyCode = "RON", createdAt = 0L, archived = true)
        val card = BankAccount(id = 2L, digits = "4535", kind = "CARD_TAIL", currencyCode = "RON", createdAt = 0L)
        assertFalse(BankAccountTree.canParent(card, retired, listOf(retired, card)))
        assertTrue(BankAccountTree.canParent(card, retired.copy(archived = false), listOf(retired, card)))
    }

    /** What is still in use is read first, at both levels. */
    @Test
    fun `retired rows sink to the bottom of the list`() {
        val account = BankAccount(id = 1L, digits = "RO49", kind = "IBAN", currencyCode = "RON", createdAt = 0L)
        val oldCard = BankAccount(id = 2L, digits = "4535", kind = "CARD_TAIL", currencyCode = "RON", createdAt = 0L, parentId = 1L, archived = true)
        val newCard = oldCard.copy(id = 3L, digits = "9999", archived = false)
        val shown = BankAccountTree.display(listOf(account, oldCard, newCard)).map { it.account.id }
        assertEquals(listOf(1L, 3L, 2L), shown)
    }
}
