package com.voxapps.textmatch.extract

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The amount-anchored leftover vendor: for a source already known to be a payment, a title-shaped
 * merchant with no designator or bank token to pin it — "Café / 37,00 RON" — is named off the figure
 * alone. Off (any ordinary source) it stays unnamed, because the figure is too weak a signal.
 */
class AmountAnchoredVendorTest {

    private val roles = TwoFieldPreParse.Roles(legalForm = "legalForm", issuer = "bank", namedVendor = "vendor")
    private val vocabularies = listOf(
        VocabularyClassifier.Vocabulary("vendor", emptyList()),
        VocabularyClassifier.Vocabulary("legalForm", listOf("PFA", "SRL", "GmbH")),
        VocabularyClassifier.Vocabulary("bank", listOf("ING", "Revolut"))
    )

    private fun vendor(title: String, text: String, anchor: Boolean) =
        TwoFieldPreParse.parse(title, text, vocabularies, roles, setOf("RON"), amountAnchorsVendor = anchor).vendor

    @Test
    fun `off, a bare café with no bank yields no vendor`() {
        assertNull(vendor("Café Central", "37,00 RON", anchor = false))
    }

    @Test
    fun `on, the figure anchors the café as the vendor`() {
        assertEquals("Café Central", vendor("Café Central", "37,00 RON", anchor = true))
    }

    @Test
    fun `on, an accented single-word name survives`() {
        assertEquals("Café", vendor("Café", "37,00 RON", anchor = true))
    }

    @Test
    fun `on, a bank token still anchors over the figure`() {
        // Bank in the text → the bank-anchored rule wins, naming the title, unchanged by the flag.
        assertEquals("Café Central", vendor("Café Central", "37,00 RON with ING Card ••1234", anchor = true))
        assertEquals("ING", TwoFieldPreParse.parse("Café Central", "37,00 RON with ING Card ••1234", vocabularies, roles, setOf("RON"), amountAnchorsVendor = true).bank)
    }

    @Test
    fun `on, a prose title is not a name`() {
        assertNull(vendor("Payment made at your favourite spot", "37,00 RON", anchor = true))
    }

    @Test
    fun `on, an amount in both fields is too ambiguous to anchor`() {
        assertNull(vendor("Café 37,00 RON", "37,00 RON", anchor = true))
    }

    @Test
    fun `a designator still names the vendor without the flag`() {
        // The point of the fix is names WITHOUT a designator; ones with it never needed it.
        assertEquals("LAZAR IONUT PFA", vendor("LAZAR IONUT PFA", "37,00 RON", anchor = false))
    }
}
