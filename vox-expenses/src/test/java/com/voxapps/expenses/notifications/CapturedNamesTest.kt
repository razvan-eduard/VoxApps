package com.voxapps.expenses.notifications

import com.google.gson.Gson
import com.voxapps.expenses.data.FieldVocabularies
import com.voxapps.expenses.data.VocabulariesSchema
import com.voxapps.expenses.domain.names.CapturedNames
import com.voxapps.textmatch.extract.VocabularyClassifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/**
 * The names, read the same way whatever brought the text.
 *
 * This is the step a scanned page never had: a message naming ING gave a bank with nothing asked of
 * anybody, while a card slip naming ING gave none unless a model was asked. Same lists, same rule,
 * both routes.
 */
class CapturedNamesTest {

    private val supplied: VocabulariesSchema = Gson().fromJson(
        File("src/main/assets/schemas/field_vocabularies.json").readText(),
        VocabulariesSchema::class.java
    )

    private fun vocabularies(shops: List<String> = emptyList()) = listOf(
        VocabularyClassifier.Vocabulary(FieldVocabularies.VOCAB_VENDOR, shops),
        VocabularyClassifier.Vocabulary(FieldVocabularies.VOCAB_LEGAL_FORM, supplied.legalForms),
        VocabularyClassifier.Vocabulary(FieldVocabularies.VOCAB_BANK, supplied.banks)
    )

    /** A card slip, as a scan hands it over. The bank is printed on it exactly as it is in a
     *  notification, and now it is read from it. */
    private val cardSlip = """
        LIDL DISCOUNT SRL
        TOTAL                 315,07 LEI
        CARD **** **** **** 4535
        ING BANK ROMANIA
    """.trimIndent()

    @Test
    fun `a bank printed on a page is the bank`() {
        assertEquals("ING", CapturedNames.of(cardSlip, vocabularies()).bank)
    }

    /** A shop this device listed is found wherever it is printed — which is what makes a name
     *  learned from one capture worth anything to the next. */
    @Test
    fun `a listed shop is found on the page`() {
        val read = CapturedNames.of(cardSlip, vocabularies(shops = listOf("LIDL DISCOUNT")))
        assertEquals("LIDL DISCOUNT", read.vendor)
    }

    @Test
    fun `a shop nobody listed is not invented`() {
        assertNull(CapturedNames.of(cardSlip, vocabularies()).vendor)
    }

    /** Two banks on one page name neither — the certainty rule every reading here follows. */
    @Test
    fun `two banks name none`() {
        val twoBanks = "$cardSlip\nEmis prin Revolut"
        assertNull(CapturedNames.of(twoBanks, vocabularies()).bank)
    }

    @Test
    fun `nothing at all reads nothing`() {
        assertNull(CapturedNames.of(null, vocabularies()).bank)
        assertNull(CapturedNames.of("   ", vocabularies()).vendor)
    }
}
