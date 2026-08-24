package com.voxapps.expenses.domain.accounts

import com.voxapps.expenses.data.BankAccount
import com.voxapps.textmatch.extract.AccountIdentifiers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What a text turns out to name, once the accounts already on file are taken into account. */
class BankAccountsTest {

    private fun account(digits: String, kind: AccountIdentifiers.Kind = AccountIdentifiers.Kind.CARD_TAIL, id: Long = 1) =
        BankAccount(id = id, digits = digits, kind = kind.name, currencyCode = "RON", createdAt = 0L)

    @Test
    fun `a text naming nothing resolves to nothing`() {
        assertEquals(BankAccounts.Outcome.None, BankAccounts.resolve("Payment received", emptyList()))
        assertEquals(BankAccounts.Outcome.None, BankAccounts.resolve(null, emptyList()))
    }

    @Test
    fun `a known card is recognised`() {
        val stored = account("4535")
        val outcome = BankAccounts.resolve("63,00 RON with ING Card ••4535", listOf(stored))
        assertEquals(BankAccounts.Outcome.Known(stored), outcome)
    }

    /** The tail from a notification finds the card a receipt filed in full. */
    @Test
    fun `a shorter tail finds the fuller account`() {
        val stored = account("1111", kind = AccountIdentifiers.Kind.CARD)
        val outcome = BankAccounts.resolve("card ••111", listOf(stored))
        assertEquals(BankAccounts.Outcome.Known(stored), outcome)
    }

    @Test
    fun `an unfamiliar card is a candidate, not a record`() {
        val outcome = BankAccounts.resolve("card ••9999", listOf(account("4535")))
        assertTrue(outcome is BankAccounts.Outcome.Unknown)
        assertEquals("9999", (outcome as BankAccounts.Outcome.Unknown).ref.digits)
    }

    /**
     * A transfer between two of a person's own accounts names both. Choosing one would file half of
     * them against the wrong account.
     */
    @Test
    fun `a text naming two accounts claims neither`() {
        assertEquals(
            BankAccounts.Outcome.None,
            BankAccounts.resolve("from ••4535 to ••9999", emptyList())
        )
    }

    /** Two stored cards ending the same way mean the tail cannot tell them apart. */
    @Test
    fun `a reading matching two stored accounts claims neither`() {
        val outcome = BankAccounts.resolve(
            "card ••00",
            listOf(account("1100", id = 1), account("2200", id = 2))
        )
        assertEquals(BankAccounts.Outcome.None, outcome)
    }

    @Test
    fun `an IBAN is recognised on its own terms`() {
        val stored = account("GB82WEST12345698765432", kind = AccountIdentifiers.Kind.IBAN)
        val outcome = BankAccounts.resolve("Transfer to GB82 WEST 1234 5698 7654 32", listOf(stored))
        assertEquals(BankAccounts.Outcome.Known(stored), outcome)
    }

    // --- whether an unknown one may be written ---

    @Test
    fun `each source is answered by its own switch`() {
        assertTrue(BankAccounts.shouldCreate(fromScan = true, scansEnabled = true, notificationsEnabled = false))
        assertFalse(BankAccounts.shouldCreate(fromScan = true, scansEnabled = false, notificationsEnabled = true))
        assertTrue(BankAccounts.shouldCreate(fromScan = false, scansEnabled = false, notificationsEnabled = true))
        assertFalse(BankAccounts.shouldCreate(fromScan = false, scansEnabled = true, notificationsEnabled = false))
    }

    // --- what an unknown one becomes ---

    @Test
    fun `a new account carries the reading, the currency and the bank that came with it`() {
        val ref = AccountIdentifiers.AccountRef(AccountIdentifiers.Kind.CARD_TAIL, "4535")
        val created = BankAccounts.newAccount(ref, "EUR", "ING", nowMillis = 7L)
        assertEquals("4535", created.digits)
        assertEquals(AccountIdentifiers.Kind.CARD_TAIL.name, created.kind)
        assertEquals("EUR", created.currencyCode)
        assertEquals("ING", created.bankName)
        assertEquals(7L, created.createdAt)
        assertTrue("the app made it, not a person", created.autoCreated)
    }

    @Test
    fun `a blank bank name is no bank name`() {
        val ref = AccountIdentifiers.AccountRef(AccountIdentifiers.Kind.CARD_TAIL, "4535")
        assertEquals(null, BankAccounts.newAccount(ref, "RON", "  ", 0L).bankName)
    }

    // --- knowing a card better than before ---

    @Test
    fun `a fuller reading widens the stored one`() {
        val stored = account("00")
        val fuller = AccountIdentifiers.AccountRef(AccountIdentifiers.Kind.CARD, "1100")
        assertTrue(BankAccounts.widens(stored, fuller))
    }

    @Test
    fun `a shorter or equal reading widens nothing`() {
        val stored = account("1100")
        assertFalse(BankAccounts.widens(stored, AccountIdentifiers.AccountRef(AccountIdentifiers.Kind.CARD_TAIL, "00")))
        assertFalse(BankAccounts.widens(stored, AccountIdentifiers.AccountRef(AccountIdentifiers.Kind.CARD_TAIL, "1100")))
    }

    @Test
    fun `a different card widens nothing`() {
        val stored = account("1100")
        assertFalse(BankAccounts.widens(stored, AccountIdentifiers.AccountRef(AccountIdentifiers.Kind.CARD, "9999")))
    }

    /** An IBAN is already complete; there is no fuller spelling of one. */
    @Test
    fun `an IBAN is never widened`() {
        val stored = account("GB82WEST12345698765432", kind = AccountIdentifiers.Kind.IBAN)
        val same = AccountIdentifiers.AccountRef(AccountIdentifiers.Kind.IBAN, "GB82WEST12345698765432")
        assertFalse(BankAccounts.widens(stored, same))
    }

    // --- where a newly seen card lands ---

    @Test
    fun `a card of the bank you keep one account with is filed under it`() {
        val ing = account("RO49AAAA1B31007593840000", kind = AccountIdentifiers.Kind.IBAN)
            .copy(id = 1L, bankName = "ING")
        val found = BankAccounts.soleAccountOf("ING", AccountIdentifiers.Kind.CARD_TAIL, listOf(ing))
        assertEquals(ing.id, found?.id)
        // However the message spelled it.
        assertEquals(ing.id, BankAccounts.soleAccountOf(" ing ", AccountIdentifiers.Kind.CARD_TAIL, listOf(ing))?.id)
    }

    /** Two accounts at one bank is a real ambiguity, and the bank's name cannot say which. */
    @Test
    fun `two accounts at the same bank leave the card where it landed`() {
        val one = account("RO49AAAA1B31007593840000", kind = AccountIdentifiers.Kind.IBAN).copy(id = 1L, bankName = "ING")
        val two = one.copy(id = 2L, digits = "RO49AAAA1B31007593840001")
        assertNull(BankAccounts.soleAccountOf("ING", AccountIdentifiers.Kind.CARD_TAIL, listOf(one, two)))
    }

    @Test
    fun `an account of another bank, or none named, adopts nothing`() {
        val ing = account("4535").copy(id = 1L, bankName = "ING")
        assertNull(BankAccounts.soleAccountOf("Revolut", AccountIdentifiers.Kind.CARD_TAIL, listOf(ing)))
        assertNull(BankAccounts.soleAccountOf(null, AccountIdentifiers.Kind.CARD_TAIL, listOf(ing)))
        assertNull(BankAccounts.soleAccountOf("  ", AccountIdentifiers.Kind.CARD_TAIL, listOf(ing)))
        assertNull(BankAccounts.soleAccountOf("ING", AccountIdentifiers.Kind.CARD_TAIL, emptyList()))
    }

    /** An IBAN names an account outright; it is not a way of reaching another one. */
    @Test
    fun `an account is never filed under another account`() {
        val ing = account("4535").copy(id = 1L, bankName = "ING")
        assertNull(BankAccounts.soleAccountOf("ING", AccountIdentifiers.Kind.IBAN, listOf(ing)))
    }

    /** A card already under an account is not itself somewhere to put cards — the one-level rule. */
    @Test
    fun `only a row nothing sits under can adopt`() {
        val account = account("RO49AAAA1B31007593840000", kind = AccountIdentifiers.Kind.IBAN).copy(id = 1L, bankName = "ING")
        val card = account.copy(id = 2L, digits = "4535", kind = AccountIdentifiers.Kind.CARD_TAIL.name, parentId = 1L)
        assertEquals(1L, BankAccounts.soleAccountOf("ING", AccountIdentifiers.Kind.CARD_TAIL, listOf(account, card))?.id)
    }

    // --- how an account names itself ---

    @Test
    fun `an unnamed card wears its tail behind a mask`() {
        assertEquals("••4535", account("4535").displayName())
    }

    @Test
    fun `an unnamed account wears its number`() {
        assertEquals("GB82WEST1", account("GB82WEST1", kind = AccountIdentifiers.Kind.IBAN).displayName())
    }

    @Test
    fun `a named one wears its name`() {
        assertEquals("Salary", account("4535").copy(label = "Salary").displayName())
        assertEquals("••4535", account("4535").copy(label = "  ").displayName())
    }

    /** "ING ••4535" is what a person recognises; 24 characters of IBAN is what they scroll past. */
    @Test
    fun `with a bank but no name of its own, it wears the bank and the last four`() {
        assertEquals("ING ••4535", account("4535").copy(bankName = "ING").displayName())
        assertEquals(
            "ING ••EST1",
            account("GB82WEST1", kind = AccountIdentifiers.Kind.IBAN).copy(bankName = "ING").displayName()
        )
    }

    @Test
    fun `a name of its own still outranks the bank`() {
        assertEquals("Salary", account("4535").copy(bankName = "ING", label = "Salary").displayName())
    }
}
