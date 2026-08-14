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
        assertEquals("Acme Mart 45,20 RON", r.vendor)
        assertEquals(45.20, r.amount!!, 0.001)
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

}
