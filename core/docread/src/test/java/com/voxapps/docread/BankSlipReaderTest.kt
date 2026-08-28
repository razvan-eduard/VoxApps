package com.voxapps.docread

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BankSlipReaderTest {

    private val slip = javaClass.getResourceAsStream("/scan-bank-slip.txt")!!
        .bufferedReader().readText()

    @Test
    fun `the slip's one figure under the Debit-Credit heading is the total`() {
        val candidate = BankSlipReader.candidate(slip)!!
        assertEquals(BankSlipReader.TEMPLATE_ID_TABLE, candidate.templateId)
        assertEquals(245.90, candidate.grandTotal!!, 0.001)
        assertNull(candidate.invoiceTotal)
        assertNull(candidate.previousBalance)
    }

    @Test
    fun `the whole reading accepts the slip as totals-only, no items`() {
        val reading = ScanReading.of(slip, slip)
        assertEquals(245.90, reading.totals.total!!, 0.001)
        assertNull(reading.items)
    }

    @Test
    fun `several movements below the heading are a statement and are refused`() {
        val statement = slip.replace(
            "27 august 2026 Transfer Home'Bank 245,90",
            "27 august 2026 Transfer Home'Bank 245,90\n26 august 2026 Plata card 13,50"
        )
        assertNull(BankSlipReader.candidate(statement))
    }

    @Test
    fun `a second printed amount anywhere below the heading refuses the table shape`() {
        val withFee = slip.replace(
            "Referinta: 1787000000000000000001",
            "Referinta: 1787000000000000000001\nComision: 2,50"
        )
        assertNull(BankSlipReader.candidate(withFee))
    }

    @Test
    fun `reference numbers, IBANs and years below the heading are not amounts`() {
        // The fixture already carries an IBAN, a 22-digit reference, a year and a page marker below
        // the heading — the single 245,90 surviving them is exactly what this pins.
        assertEquals(245.90, BankSlipReader.candidate(slip)!!.grandTotal!!, 0.001)
    }

    @Test
    fun `documents without the transfer vocabulary are never slips`() {
        val invoice = javaClass.getResourceAsStream("/scan-invoice-single.txt")!!
            .bufferedReader().readText()
        assertNull(BankSlipReader.candidate(invoice))
        // A store receipt printing a transfer-ish caption still lacks the vocabulary gate.
        assertNull(BankSlipReader.candidate("BON FISCAL\nSuma platita: 49,99\nTOTAL 49,99"))
    }

    @Test
    fun `the debited caption owns its figure on a transfer document`() {
        val bt = "Confirmare transfer\nBeneficiar: Apa Nova\nSuma debitata: 120,55 RON\nData: 27-08-2026"
        val candidate = BankSlipReader.candidate(bt)!!
        assertEquals("bank-slip-caption-debited", candidate.templateId)
        assertEquals(120.55, candidate.grandTotal!!, 0.001)
    }

    @Test
    fun `the transferred caption owns its figure`() {
        val revolut = "Payment confirmation\nYou sent 89.99 EUR to John\nReference: 12345"
        assertEquals(89.99, BankSlipReader.candidate(revolut)!!.grandTotal!!, 0.001)
    }

    @Test
    fun `the transaction-value caption owns its figure`() {
        val slipRo = "Detalii tranzactie\nValoare tranzactie: 300,00\nOrdonator: Ion"
        assertEquals(300.00, BankSlipReader.candidate(slipRo)!!.grandTotal!!, 0.001)
    }

    @Test
    fun `the paid caption owns its figure`() {
        val en = "Transaction details\nAmount paid: 42.10\nMerchant: Example"
        assertEquals(42.10, BankSlipReader.candidate(en)!!.grandTotal!!, 0.001)
    }

    @Test
    fun `two lines matching one caption shape are refused`() {
        val two = "Transfer\nSuma platita: 10,00\nSuma platita: 20,00"
        assertNull(BankSlipReader.candidate(two))
    }

    @Test
    fun `the slip's parties are read off their captions`() {
        val fields = BankSlipReader.fields(slip)!!
        assertEquals("Salubritate Exemplu SRL", fields.beneficiary)
        assertEquals("RO49AAAA1B31007593840000", fields.ownIban)
        assertEquals("RO92BBBB2C41007593840001", fields.counterpartyIban)
        assertEquals("BANCA MODEL S.A. - PLOIESTI", fields.counterpartyBank)
    }

    @Test
    fun `the bank caption is line-leading and colon-anchored, never a substring`() {
        // The fixture's letterhead line STARTS with "BANCA EXEMPLU S.A." but carries no colon after
        // a caption word — only the "Banca:" line answers. A letterhead alone claims nothing.
        assertEquals("BANCA MODEL S.A. - PLOIESTI", BankSlipReader.fields(slip)!!.counterpartyBank)
        val letterheadOnly = "BANCA EXEMPLU S.A. Detalii tranzactie\nBeneficiar: Cineva"
        assertNull(BankSlipReader.fields(letterheadOnly)!!.counterpartyBank)
    }

    @Test
    fun `a checksum-invalid counterparty IBAN claims nothing`() {
        val bad = "Transfer\nBeneficiar: Cineva\nIn contul: RO12BBBB2C41007593840001"
        assertNull(BankSlipReader.fields(bad)!!.counterpartyIban)
    }

    @Test
    fun `two counterparty IBANs claim nothing`() {
        val two = "Transfer\nBeneficiar: Cineva\nIn contul: RO92BBBB2C41007593840001\nCatre: RO49AAAA1B31007593840000"
        assertNull(BankSlipReader.fields(two)!!.counterpartyIban)
    }

    @Test
    fun `one IBAN can never land on both sides`() {
        val fields = BankSlipReader.fields(slip)!!
        assertEquals(false, fields.ownIban == fields.counterpartyIban)
    }

    @Test
    fun `the counterparty's IBAN is excluded by its caption, not by luck`() {
        // Both IBANs checksum-valid; only the one outside "In contul:"/"Beneficiar:" lines survives.
        val twoValid = "Transfer\nNumar cont: RO49AAAA1B31007593840000\nIn contul: RO11INGB0000999907194233"
        assertEquals("RO49AAAA1B31007593840000", BankSlipReader.fields(twoValid)!!.ownIban)
    }

    @Test
    fun `two own IBANs claim nothing`() {
        val two = "Transfer\nNumar cont: RO49AAAA1B31007593840000\nCont vechi: RO11INGB0000999907194233"
        assertNull(BankSlipReader.fields(two)?.ownIban)
    }

    @Test
    fun `fields are gated on the transfer vocabulary too`() {
        assertNull(BankSlipReader.fields("FACTURA\nNumar cont: RO49AAAA1B31007593840000"))
    }

    @Test
    fun `a captioned line with no figure does not steal from other lines`() {
        val stray = "Transfer\nSuma debitata: vezi mai jos\nAlt text 77,70"
        assertNull(BankSlipReader.candidate(stray))
    }
}
