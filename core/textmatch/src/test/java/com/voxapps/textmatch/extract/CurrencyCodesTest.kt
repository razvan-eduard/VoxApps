package com.voxapps.textmatch.extract

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CurrencyCodesTest {

    @Test
    fun `a code beside the figure is the currency`() {
        assertEquals("RON", CurrencyCodes.find("315,07 RON with ING Card 4535"))
        assertEquals("EUR", CurrencyCodes.find("Payment of 45.20 EUR at Carrefour"))
    }

    @Test
    fun `a symbol that names one currency needs no space and no boundary`() {
        assertEquals("EUR", CurrencyCodes.find("12,99€"))
        assertEquals("GBP", CurrencyCodes.find("£8 at Pret"))
    }

    /**
     * A dollar is six currencies. Which one is not this table's to decide, so it is not decided —
     * unless the app has already been told which dollars it deals in, and then only one candidate
     * remains and there is nothing left to guess.
     */
    @Test
    fun `a bare dollar names nothing until the app knows which dollar`() {
        assertNull(CurrencyCodes.find("Charged $12.99"))
        assertEquals("USD", CurrencyCodes.find("Charged $12.99", known = setOf("USD")))
        assertEquals("CAD", CurrencyCodes.find("Charged $12.99", known = setOf("CAD")))
        // Both known is the ambiguity intact: two candidates survive, so neither is the answer.
        assertNull(CurrencyCodes.find("Charged $12.99", known = setOf("USD", "CAD")))
    }

    /** The qualified spelling is longer, matched first, and ambiguous to nobody. */
    @Test
    fun `a qualified dollar needs nothing known at all`() {
        assertEquals("CAD", CurrencyCodes.find("Charged CA$40"))
        assertEquals("USD", CurrencyCodes.find("Charged US$40"))
    }

    /** The case this exists for: "lei" is RON here and MDL across the border, and what makes it RON
     *  is that this app was told RON. */
    @Test
    fun `lei is the leu the app deals in`() {
        assertEquals("RON", CurrencyCodes.find("60,00 lei la BRISTOL MED SRL", known = setOf("RON")))
        assertEquals("MDL", CurrencyCodes.find("60,00 lei", known = setOf("MDL")))
        assertNull(CurrencyCodes.find("60,00 lei"))
    }

    /**
     * A spelling that cannot be pinned down refuses the whole reading rather than being stepped
     * over. Something was stated; reading past it would report a currency the text contradicts.
     */
    @Test
    fun `an unresolvable spelling beside a clear one names nothing`() {
        assertNull(CurrencyCodes.find("Paid 10 EUR, tipped $2"))
        assertEquals("EUR", CurrencyCodes.find("Paid 10 EUR, tipped 2 EUR"))
    }

    /** A machine that never spaces its output still says what it means. */
    @Test
    fun `a code stuck to the figure is still the code`() {
        assertEquals("EUR", CurrencyCodes.find("45EUR"))
    }

    @Test
    fun `a code hidden inside a word is not a currency`() {
        assertNull(CurrencyCodes.find("Leiden Bakery 24,00"))
        assertNull(CurrencyCodes.find("Euroins policy renewed"))
    }

    /**
     * Two currencies is a conversion or a balance in another currency, and picking between them is
     * not a table's call — the same certainty policy the amount follows.
     */
    @Test
    fun `two currencies name none`() {
        assertNull(CurrencyCodes.find("Charged 10 EUR, balance 200 RON", known = setOf("RON", "EUR")))
    }

    @Test
    fun `one currency stated twice is still that currency`() {
        assertEquals("RON", CurrencyCodes.find("100 RON spent, 250 RON left"))
        assertEquals(listOf("RON", "RON"), CurrencyCodes.codesIn("100 RON spent, 250 RON left"))
    }

    @Test
    fun `a message with no currency names none`() {
        assertNull(CurrencyCodes.find("Your parcel is on its way"))
        assertNull(CurrencyCodes.find(null))
    }

    @Test
    fun `a spelling resolves to its code`() {
        assertEquals("RON", CurrencyCodes.codeOf("Lei", known = setOf("RON")))
        assertEquals("EUR", CurrencyCodes.codeOf(" eur "))
        assertNull(CurrencyCodes.codeOf("bananas"))
        assertNull(CurrencyCodes.codeOf(null))
    }

    @Test
    fun `every code the reader knows is offered, the likeliest first`() {
        assertEquals(listOf("RON", "MDL", "EUR", "USD", "GBP", "CHF"), CurrencyCodes.ordered("ro").take(6))
        assertEquals("EUR", CurrencyCodes.ordered("de").first())
        // Whatever the language, the list is every currency once.
        assertEquals(CurrencyCodes.all().sorted(), CurrencyCodes.ordered("ro").sorted())
        assertEquals(CurrencyCodes.all().size, CurrencyCodes.ordered(null).size)
    }
}
