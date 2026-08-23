package com.voxapps.textmatch.extract

import com.voxapps.textmatch.extract.AccountIdentifiers.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reading an account out of a string by its shape alone.
 *
 * Nothing is learned or guessed here — the formats are published, so a string either has the shape
 * or it does not. Both checksums are enforced, because a receipt is full of digit runs that are the
 * right length by coincidence.
 */
class AccountIdentifiersTest {

    private fun kinds(text: String) = AccountIdentifiers.find(text).map { it.kind }
    private fun digits(text: String) = AccountIdentifiers.find(text).map { it.digits }

    // --- IBAN ---

    @Test
    fun `a spaced IBAN is read and compacted`() {
        val found = AccountIdentifiers.find("Transfer to GB82 WEST 1234 5698 7654 32 completed")
        assertEquals(listOf(Kind.IBAN), found.map { it.kind })
        assertEquals("GB82WEST12345698765432", found.first().digits)
    }

    @Test
    fun `an unspaced IBAN is read`() {
        assertEquals(listOf("DE89370400440532013000"), digits("IBAN DE89370400440532013000"))
    }

    @Test
    fun `a lowercase IBAN is read and upper-cased`() {
        assertEquals(listOf("RO49AAAA1B31007593840000"), digits("catre ro49aaaa1b31007593840000"))
    }

    /** The check digits are the difference between an account and a plausible-looking string. */
    @Test
    fun `an IBAN whose checksum fails is not an IBAN`() {
        assertTrue(AccountIdentifiers.find("GB82 WEST 1234 5698 7654 33").none { it.kind == Kind.IBAN })
        assertFalse(AccountIdentifiers.ibanChecksumHolds("DE89370400440532013001"))
    }

    @Test
    fun `the checksum accepts the published examples`() {
        for (iban in listOf("GB82WEST12345698765432", "DE89370400440532013000", "RO49AAAA1B31007593840000")) {
            assertTrue(iban, AccountIdentifiers.ibanChecksumHolds(iban))
        }
    }

    // --- full card numbers ---

    @Test
    fun `a card number is kept as its last four digits`() {
        assertEquals(listOf("1111"), digits("card 4111111111111111"))
        assertEquals(listOf(Kind.CARD), kinds("card 4111111111111111"))
    }

    @Test
    fun `a grouped card number is read`() {
        assertEquals(listOf("4242"), digits("paid with 4242 4242 4242 4242"))
        assertEquals(listOf("4242"), digits("paid with 4242-4242-4242-4242"))
    }

    /** Luhn is what tells a card from an order number of the same length. */
    @Test
    fun `a sixteen-digit run that is not a card is not read as one`() {
        assertTrue(AccountIdentifiers.find("order 1234567812345678").none { it.kind == Kind.CARD })
        assertFalse(AccountIdentifiers.luhnHolds("4111111111111112"))
        assertTrue(AccountIdentifiers.luhnHolds("4111111111111111"))
    }

    // --- masked tails, which is what a notification usually carries ---

    @Test
    fun `the common masked forms are read`() {
        assertEquals(listOf("4535"), digits("63,00 RON with ING Card ••4535"))
        assertEquals(listOf("00"), digits("Plata 12 RON card **00"))
        assertEquals(listOf("1234"), digits("card *1234"))
        assertEquals(listOf("1234"), digits("card xxxx1234"))
        assertEquals(listOf("1234"), digits("card ...1234"))
        assertEquals(listOf("1234"), digits("card ####1234"))
    }

    @Test
    fun `a masked tail reports itself as a fragment`() {
        assertEquals(listOf(Kind.CARD_TAIL), kinds("with ING Card ••4535"))
    }

    /**
     * A single dot is a decimal separator. Without this every amount on every receipt line would
     * read as a card ending in its own cents.
     */
    @Test
    fun `a decimal amount is not a masked card`() {
        assertTrue(AccountIdentifiers.find("Total 12.34 RON").isEmpty())
        assertTrue(AccountIdentifiers.find("63,00 RON").isEmpty())
    }

    @Test
    fun `text naming no account yields none`() {
        assertTrue(AccountIdentifiers.find("Payment received").isEmpty())
        assertTrue(AccountIdentifiers.find(null).isEmpty())
        assertTrue(AccountIdentifiers.find("   ").isEmpty())
    }

    // --- one message, one account ---

    /** A number and its own masked tail in one message are one card, and the number wins. */
    @Test
    fun `a fuller reading absorbs the fragment it contains`() {
        val found = AccountIdentifiers.find("card 4111111111111111 ending ••1111")
        assertEquals(1, found.size)
        assertEquals(Kind.CARD, found.first().kind)
        assertEquals("1111", found.first().digits)
    }

    @Test
    fun `two different accounts in one message stay two`() {
        val found = AccountIdentifiers.find("from ••4535 to ••9999")
        assertEquals(2, found.size)
    }

    /** [AccountIdentifiers.single] is for callers that will only act on certainty. */
    @Test
    fun `single answers only when there is exactly one`() {
        assertEquals("4535", AccountIdentifiers.single("with ING Card ••4535")?.digits)
        assertNull(AccountIdentifiers.single("from ••4535 to ••9999"))
        assertNull(AccountIdentifiers.single("Payment received"))
    }

    // --- whether two readings are the same account ---

    @Test
    fun `a tail and the full number it came from are one card`() {
        val full = AccountIdentifiers.AccountRef(Kind.CARD, "4535")
        val tail = AccountIdentifiers.AccountRef(Kind.CARD_TAIL, "535")
        assertTrue(full.sameAs(tail))
        assertTrue("and the other way round", tail.sameAs(full))
    }

    @Test
    fun `two tails that disagree are two cards`() {
        val a = AccountIdentifiers.AccountRef(Kind.CARD_TAIL, "4535")
        val b = AccountIdentifiers.AccountRef(Kind.CARD_TAIL, "9999")
        assertFalse(a.sameAs(b))
    }

    @Test
    fun `IBANs must match exactly`() {
        val a = AccountIdentifiers.AccountRef(Kind.IBAN, "GB82WEST12345698765432")
        val b = AccountIdentifiers.AccountRef(Kind.IBAN, "DE89370400440532013000")
        assertFalse(a.sameAs(b))
        assertTrue(a.sameAs(AccountIdentifiers.AccountRef(Kind.IBAN, "gb82west12345698765432")))
    }

    /** An IBAN ending in the same digits as a card tail is not that card. */
    @Test
    fun `an IBAN is never the same as a card`() {
        val iban = AccountIdentifiers.AccountRef(Kind.IBAN, "GB82WEST12345698765432")
        val tail = AccountIdentifiers.AccountRef(Kind.CARD_TAIL, "5432")
        assertFalse(iban.sameAs(tail))
        assertFalse(tail.sameAs(iban))
    }
}
