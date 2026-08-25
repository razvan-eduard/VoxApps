package com.voxapps.expenses.notifications

import com.google.gson.Gson
import com.voxapps.expenses.data.FieldVocabularies
import com.voxapps.expenses.data.VocabulariesSchema
import com.voxapps.expenses.domain.llm.NotificationPreParse
import com.voxapps.textmatch.extract.AccountIdentifiers
import com.voxapps.textmatch.extract.VocabularyClassifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * What a payment notification yields, in every arrangement one can arrive in.
 *
 * Read against the vocabularies the app actually ships — the file itself, not a hand-written list —
 * because a reading tested against a fixture is a test of the fixture. The banks, the company
 * designators and the words that mean a refusal are the ones on the device.
 *
 * It stops at the two fields, and there is a reason it cannot start earlier: a notification posted
 * from a shell reaches a listener as "Sensitive notification content hidden", so the text never
 * arrives and every case would look like a parse failure that is really a permission. Everything
 * from the title and the text onward — which is where all the reading happens — is here.
 */
class NotificationParsePositionsTest {

    private val supplied: VocabulariesSchema = Gson().fromJson(
        File("src/main/assets/schemas/field_vocabularies.json").readText(),
        VocabulariesSchema::class.java
    )

    /** Assembled exactly as the app assembles them, in the same precedence order: a merchant this
     *  device named, then designators, then issuers. */
    private val vocabularies = listOf(
        VocabularyClassifier.Vocabulary(FieldVocabularies.VOCAB_VENDOR, emptyList()),
        VocabularyClassifier.Vocabulary(FieldVocabularies.VOCAB_LEGAL_FORM, supplied.legalForms),
        VocabularyClassifier.Vocabulary(FieldVocabularies.VOCAB_BANK, supplied.banks)
    )

    /** What this install deals in — what settles a spelling that names several currencies. */
    private val known = setOf("RON", "EUR")

    private fun read(title: String?, text: String?) =
        NotificationPreParse.parse(title, text, vocabularies, known)

    @Test
    fun `the vocabularies this rests on actually shipped`() {
        assertTrue("no banks shipped", supplied.banks.isNotEmpty())
        assertTrue("no legal forms shipped", supplied.legalForms.isNotEmpty())
        assertTrue("no stop words shipped", supplied.stopWords.isNotEmpty())
    }

    // --- where the figure sits relative to what it is a figure of ---

    @Test
    fun `the code after the figure`() {
        val r = read("ING Bank", "Ai platit 315,07 RON la LIDL")
        assertEquals(315.07, r.amount!!, 0.001)
        assertEquals("RON", r.currency)
    }

    @Test
    fun `the code before the figure`() {
        val r = read("ING Bank", "Payment of RON 315.07 at LIDL")
        assertEquals(315.07, r.amount!!, 0.001)
        assertEquals("RON", r.currency)
    }

    @Test
    fun `the code stuck to the figure`() {
        val r = read("Revolut", "Paid 45EUR")
        assertEquals(45.0, r.amount!!, 0.001)
        assertEquals("EUR", r.currency)
    }

    @Test
    fun `a symbol before the figure`() {
        val r = read("Revolut", "Charged 12,99 EUR at Carrefour")
        assertEquals(12.99, r.amount!!, 0.001)
        assertEquals("EUR", r.currency)
    }

    /** The word a bank actually uses, settled by what this install holds. */
    @Test
    fun `a word that names two currencies`() {
        assertEquals("RON", read("ING Bank", "60,00 lei la BRISTOL MED SRL").currency)
    }

    @Test
    fun `both separators read the same figure`() {
        assertEquals(
            read("ING Bank", "1.234,56 RON").amount!!,
            read("ING Bank", "1,234.56 RON").amount!!,
            0.001
        )
    }

    // --- where the names sit ---

    /**
     * The bank is named by the term the vocabulary matched, not by the whole field it sat in — one
     * canonical spelling however the sender wrote the line.
     *
     * And the other field is a sentence, so nobody is named by it. A merchant is a name this device
     * was told, or a line short enough to be one; "Ai platit 100,00 RON la LIDL" is neither, and
     * calling it a shop would file the whole message as a merchant.
     */
    @Test
    fun `the bank in the title and a sentence in the text`() {
        val r = read("ING Bank", "Ai platit 100,00 RON la LIDL")
        assertEquals("ING", r.bank)
        assertNull(r.vendor)
        assertEquals(100.0, r.amount!!, 0.001)
    }

    @Test
    fun `the bank in the text and the shop in the title`() {
        val r = read("LIDL RO-490", "ING Bank: plata de 100,00 RON")
        assertEquals("ING", r.bank)
        assertEquals("LIDL RO-490", r.vendor)
    }

    /** A designator outranks an issuer token inside one field: a company whose name contains a
     *  bank's is a company, not the bank. */
    @Test
    fun `a legal form makes the field a company name`() {
        val r = read("BRISTOL MED SRL", "ING Bank: 60,00 RON")
        assertEquals("BRISTOL MED SRL", r.vendor)
        assertEquals("ING", r.bank)
    }

    @Test
    fun `neither field names a bank`() {
        assertNull(read("LIDL RO-490", "Payment 100,00 RON").bank)
    }

    // --- the card or account the message names ---

    @Test
    fun `a masked tail`() {
        val ref = AccountIdentifiers.single("315,07 RON with ING Card ••4535")
        assertEquals(AccountIdentifiers.Kind.CARD_TAIL, ref?.kind)
        assertEquals("4535", ref?.digits)
    }

    @Test
    fun `an iban in full`() {
        assertEquals(
            AccountIdentifiers.Kind.IBAN,
            AccountIdentifiers.single("transfer din RO49AAAA1B31007593840000")?.kind
        )
    }

    /** Two accounts in one message identify neither — a transfer between two of your own. */
    @Test
    fun `two accounts identify neither`() {
        assertNull(
            AccountIdentifiers.single(
                "din RO49AAAA1B31007593840000 in RO56RNCB0082044172480001"
            )
        )
    }

    @Test
    fun `no account at all`() {
        assertNull(AccountIdentifiers.single("Ai platit 100,00 RON la LIDL"))
    }

    // --- what refuses to be read ---

    /** Two currencies is a conversion or a balance in another currency; choosing between them is
     *  not a reader's business. */
    @Test
    fun `two currencies name none`() {
        assertNull(read("ING Bank", "Charged 10 EUR, balance 200 RON").currency)
    }

    @Test
    fun `a message with no figure has no amount`() {
        assertNull(read("ING Bank", "Tranzactie refuzata la LIDL").amount)
    }

    @Test
    fun `nothing at all`() {
        val r = read(null, null)
        assertNull(r.amount)
        assertNull(r.vendor)
        assertNull(r.bank)
        assertNull(r.currency)
    }
}
