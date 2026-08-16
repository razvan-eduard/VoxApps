package com.voxapps.textmatch.extract

import java.time.LocalDate
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DateTimeExtractorTest {

    @Test
    fun `finds every date in document order`() {
        val found = DateTimeExtractor.findDates("issued 13.08.2026\ndue 2026-09-01")
        assertEquals(listOf(LocalDate.of(2026, 8, 13), LocalDate.of(2026, 9, 1)), found.map { it.value })
        assertEquals(listOf(0, 1), found.map { it.lineIndex })
    }

    @Test
    fun `a future date is reported, not filtered`() {
        // Validity is the caller's rule: a receipt forbids this, a calendar invitation depends on it.
        val found = DateTimeExtractor.findDates("meeting 01.01.2099")
        assertEquals(LocalDate.of(2099, 1, 1), found.single().value)
    }

    @Test
    fun `an impossible date is not a finding`() {
        assertTrue(DateTimeExtractor.findDates("32.13.2026").isEmpty())
    }

    @Test
    fun `two-digit years read into the current century`() {
        assertEquals(LocalDate.of(2026, 8, 13), DateTimeExtractor.findDates("13/08/26").single().value)
    }

    @Test
    fun `times with and without seconds`() {
        val found = DateTimeExtractor.findTimes("at 09:41 and 23:15:30")
        assertEquals(listOf(LocalTime.of(9, 41), LocalTime.of(23, 15, 30)), found.map { it.value })
    }

    @Test
    fun `an impossible time is not a finding`() {
        assertTrue(DateTimeExtractor.findTimes("25:99").isEmpty())
    }

    @Test
    fun `evidence points back at the source`() {
        val finding = DateTimeExtractor.findDates("line0\nissued 13.08.2026").single()
        assertEquals("13.08.2026", finding.raw)
        assertEquals(1, finding.lineIndex)
    }
}

class LabelledAmountExtractorTest {

    private val totals = listOf("total", "de plata", "amount due")

    @Test
    fun `reports every labelled candidate without choosing`() {
        val text = """
            Total Factura
            22.21
            Sold Anterior
            44.42
            Total de Plata
            66.63
        """.trimIndent()

        val found = LabelledAmountExtractor.find(text, totals)
        // Both totals are reported; the unlabelled carried-over balance is not a candidate at all.
        assertEquals(listOf(22.21, 66.63), found.map { it.value }.sorted())
    }

    @Test
    fun `an unlabelled figure never qualifies however large`() {
        val found = LabelledAmountExtractor.find("Total 45.00\nNumerar 9999.00", totals)
        assertEquals(listOf(45.00), found.map { it.value })
    }

    @Test
    fun `the matching label is reported so callers can rank by specificity`() {
        val found = LabelledAmountExtractor.find("Total de Plata 66.63", totals)
        assertEquals("de plata", found.single().label)
    }

    @Test
    fun `separator conventions both resolve`() {
        assertEquals(1234.56, LabelledAmountExtractor.find("Total 1.234,56", totals).single().value, 0.001)
        assertEquals(1234.56, LabelledAmountExtractor.find("Total 1,234.56", totals).single().value, 0.001)
        assertEquals(66.63, LabelledAmountExtractor.find("Total 66,63", totals).single().value, 0.001)
    }

    @Test
    fun `a bare integer qualifies`() {
        assertEquals(45.0, LabelledAmountExtractor.find("Total 45", totals).single().value, 0.001)
    }

    @Test
    fun `a rate on the label's line is not an amount`() {
        assertEquals(listOf(35.70), LabelledAmountExtractor.find("Total 35.70 (TVA 19%)", totals).map { it.value })
    }

    @Test
    fun `prose between a label and its value is not a value cell`() {
        // The exact shape a real scan produced: OCR interleaved legal boilerplate between the
        // label and its figure, and the statute number in it ("Legii 225/2016") was harvested as
        // the amount — outscoring the genuine total. A look-ahead line must be a bare figure.
        val text = """
            Total Factura
            intarziere. Serviciul poate fi suspendat. Conform Legii 225/2016,
            Sold Anterior
            44.42
            Total de Plata
            66.63
        """.trimIndent()

        val found = LabelledAmountExtractor.find(text, totals)
        assertEquals(listOf(66.63), found.map { it.value })
    }

    @Test
    fun `a value cell with a currency marker still qualifies`() {
        val text = "Total de Plata\n66.63 RON"
        assertEquals(66.63, LabelledAmountExtractor.find(text, totals).single().value, 0.001)
    }

    @Test
    fun `lookAhead zero requires label and value to share a line`() {
        val text = "Total de Plata\n66.63"
        assertTrue(LabelledAmountExtractor.find(text, totals, lookAhead = 0).isEmpty())
        assertEquals(66.63, LabelledAmountExtractor.find(text, totals, lookAhead = 1).single().value, 0.001)
    }

    @Test
    fun `no labels means no findings`() {
        assertTrue(LabelledAmountExtractor.find("Total 45.00", emptyList()).isEmpty())
    }
}

class VocabularyClassifierTest {

    private val vocabularies = listOf(
        VocabularyClassifier.Vocabulary("legalForm", listOf("PFA", "SRL", "GmbH")),
        VocabularyClassifier.Vocabulary("bank", listOf("ING", "Revolut", "Banca Transilvania"))
    )

    @Test
    fun `punctuation variants are one term`() {
        listOf("LAZAR IONUT PFA", "LAZAR IONUT P.F.A.", "lazar ionut p.f.a").forEach { field ->
            val found = VocabularyClassifier.classify(field, vocabularies)
            assertEquals("legalForm", found.single().vocabulary)
        }
    }

    @Test
    fun `dotted and undotted company forms both match`() {
        assertEquals("legalForm", VocabularyClassifier.classify("ACME S.R.L.", vocabularies).single().vocabulary)
        assertEquals("legalForm", VocabularyClassifier.classify("ACME SRL", vocabularies).single().vocabulary)
    }

    @Test
    fun `a term does not fire from inside a longer word`() {
        // "ING" must not match "SHOPPING" / "PFA" must not match "PFAFF".
        assertTrue(VocabularyClassifier.classify("SHOPPING CENTER", vocabularies).isEmpty())
        assertTrue(VocabularyClassifier.classify("PFAFF", vocabularies).isEmpty())
    }

    @Test
    fun `multi-word terms match as a sequence`() {
        val found = VocabularyClassifier.classify("plata la Banca Transilvania", vocabularies)
        assertEquals("bank", found.single().vocabulary)
    }

    @Test
    fun `a field drawing on both vocabularies reports both`() {
        val found = VocabularyClassifier.classify("ING Broker SRL", vocabularies)
        assertEquals(setOf("legalForm", "bank"), found.map { it.vocabulary }.toSet())
    }

    @Test
    fun `classifying separate fields reports which field matched`() {
        val found = VocabularyClassifier.classifyFields(
            listOf("LAZAR IONUT PFA", "63,00 RON with ING Card"),
            vocabularies
        )
        assertEquals("legalForm", found.first { it.lineIndex == 0 }.vocabulary)
        assertEquals("bank", found.first { it.lineIndex == 1 }.vocabulary)
    }

    @Test
    fun `blank and empty inputs yield nothing`() {
        assertTrue(VocabularyClassifier.classify("", vocabularies).isEmpty())
        assertTrue(VocabularyClassifier.classify("anything", emptyList()).isEmpty())
    }

    /**
     * Recognition runs a stray figure into the one beside it, and a rule that reads a space as a
     * thousands separator is then free to begin part way through a number: "6688 170.91" offers
     * "688 170.91" as a reading. That is a figure the page never printed, and on a scanned invoice it
     * then competes to be somebody's total. Every reading must begin where a number begins.
     */
    @Test
    fun `no amount is read from part way through a number`() {
        val text = "6688 170.91 113.94"

        val printed = AmountText.printed.findAll(text).mapNotNull { AmountText.normalize(it.value) }.toList()

        assertTrue("read $printed", printed.contains(170.91))
        assertTrue("read $printed", printed.contains(113.94))
        assertTrue("invented a figure the text never printed: $printed", printed.none { it > 1000.0 })
    }

    /** The printed reading wants minor units: a bare integer is a quantity or a code, not an amount. */
    @Test
    fun `the printed reading requires minor units`() {
        assertEquals(listOf("3.50"), AmountText.printed.findAll("qty 12 at 3.50 each").map { it.value }.toList())
    }
}
