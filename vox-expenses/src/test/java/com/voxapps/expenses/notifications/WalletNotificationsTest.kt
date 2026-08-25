package com.voxapps.expenses.notifications

import com.google.gson.Gson
import com.voxapps.expenses.data.FieldVocabularies
import com.voxapps.expenses.data.VocabulariesSchema
import com.voxapps.expenses.domain.llm.NotificationPreParse
import com.voxapps.textmatch.extract.AccountIdentifiers
import com.voxapps.textmatch.extract.VocabularyClassifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.File

/**
 * The messages this device actually receives, read exactly as they arrive.
 *
 * Copied from the shade rather than invented: a wallet puts the merchant in the title and the
 * figure, the bank and the card in the body. Every rule this app has about two fields was written
 * for this shape, so it is the shape worth holding still.
 */
class WalletNotificationsTest {

    private val supplied: VocabulariesSchema = Gson().fromJson(
        File("src/main/assets/schemas/field_vocabularies.json").readText(),
        VocabulariesSchema::class.java
    )

    private val vocabularies = listOf(
        VocabularyClassifier.Vocabulary(FieldVocabularies.VOCAB_VENDOR, emptyList()),
        VocabularyClassifier.Vocabulary(FieldVocabularies.VOCAB_LEGAL_FORM, supplied.legalForms),
        VocabularyClassifier.Vocabulary(FieldVocabularies.VOCAB_BANK, supplied.banks)
    )

    private fun read(title: String, text: String) =
        NotificationPreParse.parse(title, text, vocabularies, setOf("RON"))

    @Test
    fun `a shop with a company designator`() {
        val r = read("BRISTOL MED SRL", "60,00 RON with ING Card ••4535")
        assertEquals(60.0, r.amount!!, 0.001)
        assertEquals("RON", r.currency)
        assertEquals("BRISTOL MED SRL", r.vendor)
        assertEquals("ING", r.bank)
        assertEquals("4535", AccountIdentifiers.single("60,00 RON with ING Card ••4535")?.digits)
    }

    @Test
    fun `a shop written as a name and a branch number`() {
        val r = read("LIDL RO-490", "315,07 RON with ING Card ••4535")
        assertEquals(315.07, r.amount!!, 0.001)
        assertEquals("LIDL RO-490", r.vendor)
        assertEquals("ING", r.bank)
    }

    @Test
    fun `a figure written with a thousands separator`() {
        val r = read("CABINET MEDICAL INDIVIDU", "1.000,00 RON with ING Card ••4535")
        assertEquals(1000.0, r.amount!!, 0.001)
        assertEquals("CABINET MEDICAL INDIVIDU", r.vendor)
    }

    @Test
    fun `another shop with a designator`() {
        val r = read("SIMARSI COM SRL", "108,13 RON with ING Card ••4535")
        assertEquals(108.13, r.amount!!, 0.001)
        assertEquals("SIMARSI COM SRL", r.vendor)
        assertEquals("ING", r.bank)
    }

    /**
     * A refusal is not a transaction, and this is the message the stop list exists for: the same
     * shop, the same figure, minutes before the payment that succeeded. Filed, it would be a
     * duplicate of a purchase that only happened once.
     */
    @Test
    fun `a declined payment carries a stop word`() {
        val text = "DECLINED – 315,07 RON with Pluxee Gusto ••9138"
        val stop = VocabularyClassifier.firstTerm("LIDL RO-490\n$text", supplied.stopWords)
        assertNotNull("the refusal has to be recognised before anything is read", stop)
    }

    /** The card a wallet names is the one the payment went through, whichever card it is. */
    @Test
    fun `a second card is read as its own`() {
        assertEquals(
            "9138",
            AccountIdentifiers.single("DECLINED – 315,07 RON with Pluxee Gusto ••9138")?.digits
        )
    }
}
