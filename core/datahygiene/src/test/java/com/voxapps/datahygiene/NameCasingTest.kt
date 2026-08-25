package com.voxapps.datahygiene

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** One shape for a name a person typed, so a list reads as one kind of thing. */
class NameCasingTest {

    @Test
    fun `a lowercase word becomes one capital and the rest small`() {
        assertEquals("Groceries", NameCasing.titleCased("groceries"))
    }

    @Test
    fun `every word, however short`() {
        assertEquals("Banca Transilvania", NameCasing.titleCased("banca transilvania"))
        assertEquals("Food And Drink", NameCasing.titleCased("food and drink"))
    }

    @Test
    fun `shouting is brought back down`() {
        assertEquals("Utilities", NameCasing.titleCased("UTILITIES"))
        assertEquals("Cec Bank", NameCasing.titleCased("CEC BANK"))
    }

    @Test
    fun `mixed case is normalised rather than preserved`() {
        assertEquals("Mcdonalds", NameCasing.titleCased("McDonalds"))
    }

    /** Romanian names keep their diacritics, and the letter carrying one is still capitalised. */
    @Test
    fun `diacritics survive and are cased`() {
        assertEquals("Știință", NameCasing.titleCased("știință"))
        assertEquals("Întreținere", NameCasing.titleCased("întreținere"))
        assertEquals("Cumpărături Mari", NameCasing.titleCased("CUMPĂRĂTURI MARI"))
    }

    @Test
    fun `stray whitespace does not survive`() {
        assertEquals("Food Drink", NameCasing.titleCased("  food    drink  "))
        assertEquals("Food Drink", NameCasing.titleCased("food\tdrink"))
    }

    @Test
    fun `nothing to case is nothing`() {
        assertNull(NameCasing.titleCased(null))
        assertNull(NameCasing.titleCased(""))
        assertNull(NameCasing.titleCased("   "))
    }

    @Test
    fun `a name already in shape is unchanged`() {
        assertEquals("Groceries", NameCasing.titleCased("Groceries"))
        assertEquals("Uncategorised", NameCasing.titleCased("Uncategorised"))
    }

    /** Applying it twice changes nothing — it has to be safe to run on every write. */
    @Test
    fun `casing is idempotent`() {
        for (name in listOf("groceries", "PUBLIC transport", "  food  ", "știință")) {
            val once = NameCasing.titleCased(name)
            assertEquals(once, NameCasing.titleCased(once))
        }
    }

    /** Digits and punctuation are not letters to capitalise, and must not be dropped either. */
    @Test
    fun `words that do not start with a letter are left intact`() {
        assertEquals("4 Wheels", NameCasing.titleCased("4 wheels"))
        assertEquals("Drive-thru", NameCasing.titleCased("drive-thru"))
    }

    @Test
    fun `capitalized lifts the first letter of every word`() {
        assertEquals("Dentist Ioana", NameCasing.capitalized("dentist ioana"))
        assertEquals("Dentist Ioana", NameCasing.capitalized("dentist Ioana"))
    }

    /** The case title casing gets wrong: capitals that are the name, not a hurried keystroke. */
    @Test
    fun `capitalized leaves the rest of the word alone`() {
        assertEquals("ING", NameCasing.capitalized("ING"))
        assertEquals("MEGA IMAGE SRL", NameCasing.capitalized("MEGA IMAGE SRL"))
        assertEquals("McDonalds", NameCasing.capitalized("McDonalds"))
        assertEquals("Banca Transilvania", NameCasing.capitalized("banca Transilvania"))
    }

    @Test
    fun `capitalized keeps the diacritic a capital`() {
        assertEquals("Întreținere", NameCasing.capitalized("întreținere"))
    }

    @Test
    fun `capitalized collapses whitespace and refuses nothing at all`() {
        assertEquals("Dentist Ioana", NameCasing.capitalized("  dentist    ioana  "))
        assertNull(NameCasing.capitalized(null))
        assertNull(NameCasing.capitalized("   "))
    }

    @Test
    fun `capitalized once is capitalized forever`() {
        val once = NameCasing.capitalized("dentist ioana")
        assertEquals(once, NameCasing.capitalized(once))
    }
}
