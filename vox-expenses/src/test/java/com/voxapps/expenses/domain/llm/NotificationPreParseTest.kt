package com.voxapps.expenses.domain.llm

import com.voxapps.expenses.data.FieldVocabularies
import com.voxapps.textmatch.extract.VocabularyClassifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NotificationPreParseTest {

    private val vocabularies = listOf(
        VocabularyClassifier.Vocabulary(FieldVocabularies.VOCAB_LEGAL_FORM, listOf("PFA", "SRL", "GmbH")),
        VocabularyClassifier.Vocabulary(FieldVocabularies.VOCAB_BANK, listOf("ING", "Revolut", "BCR"))
    )

    @Test
    fun `the wallet purchase that came back inverted resolves every field`() {
        // The real capture that produced an incoming transfer from "ING Card": Google Wallet puts
        // the merchant in the title and the card in the text — the opposite of what the few-shot
        // examples taught. Deterministic resolution makes the convention irrelevant.
        val r = NotificationPreParse.parse("LAZAR IONUT PFA", "63,00 RON with ING Card ••4535", vocabularies)
        assertEquals("LAZAR IONUT PFA", r.vendor)
        assertEquals("ING", r.bank)
        assertEquals(63.00, r.amount!!, 0.001)
    }

    @Test
    fun `a bank-titled notification with a name-shaped text uses the leftover rule`() {
        val r = NotificationPreParse.parse("Revolut", "Acme Mart 45,20 RON", vocabularies)
        assertEquals("Revolut", r.bank)
        // The figure comes out of the name. It is read as the amount in the same pass, and a vendor
        // carrying the price is one that will not match itself the next time the shop is seen.
        assertEquals("Acme Mart", r.vendor)
        assertEquals(45.20, r.amount!!, 0.001)
    }

    @Test
    fun `a figure between two halves of a name does not leave a gap`() {
        val r = NotificationPreParse.parse("Revolut", "Acme 45,20 RON Mart", vocabularies)
        assertEquals("Acme Mart", r.vendor)
    }

    @Test
    fun `prose is never promoted to vendor by the leftover rule`() {
        // A top-up sentence is not a merchant name; naming the counterparty is the model's job.
        val r = NotificationPreParse.parse(
            "Revolut", "Auto top-up of RON500 has been added to your account", vocabularies
        )
        assertEquals("Revolut", r.bank)
        assertNull(r.vendor)
        assertEquals(500.0, r.amount!!, 0.001)
    }

    /**
     * A voucher scheme is an issuer like any other, and only being listed as one lets the leftover
     * rule run: with neither field claimed there is nothing to eliminate, and a wallet relaying the
     * card of a scheme nobody listed resolves no merchant at all.
     */
    @Test
    fun `a voucher scheme claims its field like a bank does`() {
        val withScheme = listOf(
            VocabularyClassifier.Vocabulary(FieldVocabularies.VOCAB_LEGAL_FORM, listOf("PFA", "SRL")),
            VocabularyClassifier.Vocabulary(FieldVocabularies.VOCAB_BANK, listOf("ING", "Pluxee"))
        )
        val r = NotificationPreParse.parse("SHOP RO-490", "223,53 RON with Pluxee Gusto ••9138", withScheme)
        assertEquals("SHOP RO-490", r.vendor)
        assertEquals("Pluxee", r.bank)
        assertEquals(223.53, r.amount!!, 0.001)

        val unlisted = NotificationPreParse.parse("SHOP RO-490", "223,53 RON with Pluxee Gusto ••9138", vocabularies)
        assertNull("with no issuer claimed there is nothing to eliminate", unlisted.vendor)
    }

    /**
     * The counterparty sentence: short enough once its figure is removed to slip past a laxer bound,
     * and a whole sentence in a field that will be shown as a merchant is worse than no merchant.
     */
    @Test
    fun `a transfer sentence is not a merchant name`() {
        val r = NotificationPreParse.parse("Revolut", "You received 500,90 RON from John Doe", vocabularies)
        assertEquals("Revolut", r.bank)
        assertNull(r.vendor)
        assertEquals(500.90, r.amount!!, 0.001)
    }

    @Test
    fun `a transaction notice is not a merchant name either`() {
        val withScheme = listOf(
            VocabularyClassifier.Vocabulary(FieldVocabularies.VOCAB_LEGAL_FORM, listOf("SRL")),
            VocabularyClassifier.Vocabulary(FieldVocabularies.VOCAB_BANK, listOf("Pluxee"))
        )
        val r = NotificationPreParse.parse("Your Pluxee Card", "New transaction of -RON 223.53", withScheme)
        assertEquals("Pluxee", r.bank)
        assertNull(r.vendor)
    }

    /** Still resolved: the names this rule exists for are shorter than the sentences it refuses. */
    @Test
    fun `a branch-coded shop and a company with its legal form still resolve`() {
        assertEquals(
            "LAZAR IONUT PFA",
            NotificationPreParse.parse("LAZAR IONUT PFA", "63,00 RON with ING Card ••4535", vocabularies).vendor
        )
        assertEquals(
            "SHOP RO-490",
            NotificationPreParse.parse("SHOP RO-490", "63,00 RON with ING Card ••4535", vocabularies).vendor
        )
    }

    @Test
    fun `a purchase with a balance line declines the amount`() {
        // Two distinct currency-marked figures — choosing between them is not a regex's call.
        val r = NotificationPreParse.parse(
            "Revolut", "You spent 45,20 RON at the shop. RON balance: 900,00 RON", vocabularies
        )
        assertNull(r.amount)
    }

    @Test
    fun `an unmarked number is not an amount`() {
        // Card fragments and counts carry no currency marker.
        val r = NotificationPreParse.parse("LAZAR IONUT PFA", "paid with card 4535", vocabularies)
        assertNull(r.amount)
    }

    @Test
    fun `legal form outranks a bank token inside the same field`() {
        // A merchant whose name contains a bank's name is still the vendor.
        val r = NotificationPreParse.parse("ING Broker SRL", "12,50 RON payment", vocabularies)
        assertEquals("ING Broker SRL", r.vendor)
        assertNull(r.bank)
    }

    @Test
    fun `two bank fields decline the bank`() {
        val r = NotificationPreParse.parse("Revolut", "transfer to your BCR account", vocabularies)
        assertNull(r.bank)
        assertNull(r.vendor)
    }

    @Test
    fun `nothing matched resolves nothing`() {
        val r = NotificationPreParse.parse("Some App", "hello there", vocabularies)
        assertNull(r.vendor)
        assertNull(r.bank)
        assertNull(r.amount)
    }

    @Test
    fun `the same repeated figure is one amount, not two`() {
        val r = NotificationPreParse.parse("Shop", "45,20 RON — total 45,20 RON", vocabularies)
        assertEquals(45.20, r.amount!!, 0.001)
    }

    @Test
    fun `a composed title is vendor plus the model's category`() {
        assertEquals("LIDL Groceries", NotificationPreParse.composeTitle("LIDL", "Groceries"))
        assertEquals("LAZAR IONUT PFA", NotificationPreParse.composeTitle("LAZAR IONUT PFA", null))
        assertEquals("LIDL", NotificationPreParse.composeTitle(" LIDL ", "  "))
    }


    @Test
    fun `a preset payment parses a reply that never carried the gate field`() {
        val parsed = NotificationExpenseParseResultParser.parse(
            """{"currency":"RON","category":"Groceries"}""",
            presetAmount = 63.0,
            presetIsPayment = true
        )
        org.junit.Assert.assertNotNull(parsed)
        assertEquals(63.0, parsed!!.totalAmount, 0.001)
    }

    @Test
    fun `without the preset the gate still rejects`() {
        org.junit.Assert.assertNull(
            NotificationExpenseParseResultParser.parse("""{"currency":"RON"}""", presetAmount = 63.0)
        )
    }

    // --- a shop named by this device outranks both rules ---

    private val withNamedShop = listOf(
        VocabularyClassifier.Vocabulary(FieldVocabularies.VOCAB_VENDOR, listOf("SHOP RO-490")),
        VocabularyClassifier.Vocabulary(FieldVocabularies.VOCAB_LEGAL_FORM, listOf("PFA", "SRL")),
        VocabularyClassifier.Vocabulary(FieldVocabularies.VOCAB_BANK, listOf("ING", "Pluxee"))
    )

    /** The case neither other rule reaches: no designator anywhere, no issuer recognised. */
    @Test
    fun `a named shop resolves where nothing else can`() {
        val r = NotificationPreParse.parse("SHOP RO-490", "223,53 RON with an unlisted card", withNamedShop)
        assertEquals("SHOP RO-490", r.vendor)
        assertEquals(223.53, r.amount!!, 0.001)
    }

    /** Named beats designator: the more specific statement wins. */
    @Test
    fun `a named shop outranks a company designator in the other field`() {
        val r = NotificationPreParse.parse("SHOP RO-490", "PAID TO SOME SRL", withNamedShop)
        assertEquals("SHOP RO-490", r.vendor)
    }

    /** And an issuer token inside a named field is part of that name, not a bank. */
    @Test
    fun `an issuer word inside a named shop does not become the bank`() {
        val named = listOf(
            VocabularyClassifier.Vocabulary(FieldVocabularies.VOCAB_VENDOR, listOf("ING Store")),
            VocabularyClassifier.Vocabulary(FieldVocabularies.VOCAB_LEGAL_FORM, listOf("SRL")),
            VocabularyClassifier.Vocabulary(FieldVocabularies.VOCAB_BANK, listOf("ING"))
        )
        val r = NotificationPreParse.parse("ING Store", "12,50 RON paid", named)
        assertEquals("ING Store", r.vendor)
        assertNull("the shop's own name is not an issuer", r.bank)
    }

    /** Two named fields is an ambiguity, and ambiguity declines like everywhere else here. */
    @Test
    fun `two named fields resolve nothing`() {
        val named = listOf(
            VocabularyClassifier.Vocabulary(FieldVocabularies.VOCAB_VENDOR, listOf("ALPHA", "BETA")),
            VocabularyClassifier.Vocabulary(FieldVocabularies.VOCAB_LEGAL_FORM, listOf("SRL")),
            VocabularyClassifier.Vocabulary(FieldVocabularies.VOCAB_BANK, listOf("ING"))
        )
        assertNull(NotificationPreParse.parse("ALPHA", "BETA 10,00 RON", named).vendor)
    }

    /** With no named shop matching, the two older rules are untouched. */
    @Test
    fun `the issuer rules still work when no shop is named`() {
        val r = NotificationPreParse.parse("LAZAR IONUT PFA", "63,00 RON with ING Card", withNamedShop)
        assertEquals("LAZAR IONUT PFA", r.vendor)
        assertEquals("ING", r.bank)
    }
}
