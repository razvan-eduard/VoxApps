package com.voxapps.expenses.domain.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The invoice throughout is real, and its figures were read off the document and checked: twelve
 * service rows, every one `pers × 2`, whose values sum to exactly 18.36 net — 3.85 VAT short of the
 * 22.21 the invoice charges, which is itself 44.42 short of the 66.63 due. A scan of it produced
 * three items named after the supplier's address instead, summing to 16.90; both readings appear
 * here, because a battery is only as good as its willingness to reject the second one.
 */
class LineItemBatteryTest {

    private val net = 18.36
    private val vat = 3.85
    private val invoiceTotal = 22.21

    private val targets = LineItemBattery.Targets(
        invoiceTotal = invoiceTotal, netSubtotal = net, vatTotal = vat
    )

    /** The invoice as its descriptions are printed: each wraps over lines above its amounts. */
    private val wrappedInvoice = """
        Tarif pentru colectarea separata si transportul separat al deseurilor
        de hartie,metal,plastic si sticla din deseurile municipale
        Tcs reciclabile Iun. 2026 Strada SAT PLEASA 2 2.35 4.70 0.99
        Tarif pentru colectarea separata a biodeseurilor
        Tcs biodeseuri Iun. 2026 Strada SAT PLEASA 2 1.62 3.24 0.68
        Tarif pentru colectarea deseurilor reziduale
        Tcs reziduale Iun. 2026 Strada SAT PLEASA 2 1.61 3.22 0.68
        Tarif pentru sortarea deseurilor colectate separat
        Tsortare Iun. 2026 Strada SAT PLEASA 2 0.6 1.20 0.25
        Tarif TMB biodeseuri Iun. 2026 Strada SAT PLEASA 2 1 2.00 0.42
        Tarif TMB reziduale Iun. 2026 Strada SAT PLEASA 2 0.24 0.48 0.10
        Tarif depozitare ip-reciclabile Iun. 2026 Strada SAT PLEASA 2 0.1 0.20 0.04
        Tarif depozitare ip-biodeseuri Iun. 2026 Strada SAT PLEASA 2 0.5 1.00 0.21
        Tarif depozitare ip-reziduale Iun. 2026 Strada SAT PLEASA 2 0.12 0.24 0.05
        CEC ip-reciclabile Iun. 2026 Strada SAT PLEASA 2 0.14 0.28 0.06
        CEC ip-biodeseuri Iun. 2026 Strada SAT PLEASA 2 0.7 1.40 0.29
        CEC ip-reziduale Iun. 2026 Strada SAT PLEASA 2 0.2 0.40 0.08
    """.trimIndent()

    @Test
    fun `an invoice whose descriptions wrap is read row by row and proved by its net subtotal`() {
        val reading = LineItemBattery.read(wrappedInvoice, targets)!!

        assertEquals("numeric-tail", reading.templateId)
        assertEquals(12, reading.rows.size)
        assertEquals(net, reading.matchedTarget, 0.001)
        assertEquals(net, reading.rows.sumOf { it.value }, 0.02)
        // Quantity and unit price survive as printed, rather than being folded into one figure.
        assertEquals(2.0, reading.rows.first().quantity, 0.001)
        assertEquals(2.35, reading.rows.first().unitPrice, 0.001)
        // The wrapped lines above a row became its description.
        assertTrue(reading.rows.first().name.startsWith("Tarif pentru colectarea separata"))
    }

    @Test
    fun `a fiscal receipt line with unit, filler and currency is read`() {
        val bon = """
            Paine integrala 500g 1 buc x 6,50 RON ........ 6,50 RON
            Lapte 1.5% 1L 2 buc x 8,90 RON ....... 17,80 RON
            Sampon 400ml 1 buc x 24,50 RON ...... 24,50 RON
        """.trimIndent()

        val reading = LineItemBattery.read(bon, LineItemBattery.Targets(invoiceTotal = 48.80))!!

        assertEquals("qty-unit-x-price-value", reading.templateId)
        assertEquals(3, reading.rows.size)
        assertEquals(2.0, reading.rows[1].quantity, 0.001)
        assertEquals(8.90, reading.rows[1].unitPrice, 0.001)
        assertTrue(reading.rows[1].name.startsWith("Lapte"))
    }

    @Test
    fun `a till listing quantity first derives the unit price it does not print`() {
        val bill = """
            2 Espresso 14.00
            1 Tiramisu 18.50
        """.trimIndent()

        val reading = LineItemBattery.read(bill, LineItemBattery.Targets(invoiceTotal = 32.50))!!
        assertEquals(2, reading.rows.size)
        assertEquals(7.00, reading.rows[0].unitPrice, 0.001)
        assertEquals(2.0, reading.rows[0].quantity, 0.001)
    }

    /** The junk the real scan produced. It sums to nothing the document prints, so it must lose. */
    @Test
    fun `an item list summing to nothing printed is refused`() {
        val junk = """
            Bucuresti 4 3.31 13.24
            Strada 6 0.45 2.70
            Pers 2 8 0.12 0.96
        """.trimIndent()

        assertNull(LineItemBattery.read(junk, targets))
    }

    /**
     * Rows whose own printed figures contradict each other are not a reading of anything.
     *
     * The target here is 6.00 — what these rows would sum to if `2 × 2.00` really were 9.99. The
     * strict pattern refuses them for that contradiction, and no looser pattern can rescue them,
     * because the only self-consistent reading of these lines (9.99 + 2.00) sums to something the
     * document does not print. Both halves of the gate are doing work: a contradictory row is
     * rejected, *and* an alternative reading still has to reconcile before it is believed.
     */
    @Test
    fun `a row whose quantity times price misses its printed value kills the candidate`() {
        val inconsistent = """
            Ceva 2 2.00 9.99
            Altceva 2 1.00 2.00
        """.trimIndent()

        assertNull(LineItemBattery.read(inconsistent, LineItemBattery.Targets(invoiceTotal = 6.00)))
    }

    /**
     * The same lines, read the only way that is self-consistent, when the document's printed total
     * says so. This is the behaviour that makes a plural library worth having — and the reason the
     * tolerance is a cent rather than a percentage: with several candidates, a loose threshold would
     * let a reading like this win on a document it had no business explaining.
     */
    @Test
    fun `a looser reading is believed only when the printed total agrees with it`() {
        val lines = """
            Ceva 2 2.00 9.99
            Altceva 2 1.00 2.00
        """.trimIndent()

        val reading = LineItemBattery.read(lines, LineItemBattery.Targets(invoiceTotal = 11.99))!!
        assertEquals("name-amount", reading.templateId)
        assertEquals(11.99, reading.rows.sumOf { it.value }, 0.02)
    }

    @Test
    fun `the geometric reading wins when it reconciles`() {
        val columnar = listOf(
            LineItemBattery.Row("Tarif reciclabile", 2.0, 2.35),
            LineItemBattery.Row("Rest", 2.0, 6.83)
        )
        val reading = LineItemBattery.read("", targets, columnarRows = columnar)!!
        assertEquals("columnar", reading.templateId)
        assertEquals(2, reading.rows.size)
    }

    @Test
    fun `a geometric reading that does not reconcile falls through to the patterns`() {
        val wrongColumnar = listOf(LineItemBattery.Row("Whatever", 1.0, 99.0))
        val reading = LineItemBattery.read(wrappedInvoice, targets, columnarRows = wrongColumnar)!!
        assertEquals("numeric-tail", reading.templateId)
    }

    @Test
    fun `a remembered template is tried first but cannot force a wrong reading`() {
        val reading = LineItemBattery.read(
            wrappedInvoice, targets, preferredTemplateId = "name-amount"
        )!!
        // The hint is only an ordering: name-amount cannot reconcile here, so the honest reading
        // still wins.
        assertEquals("numeric-tail", reading.templateId)
    }

    /**
     * The document that forced this: a clean scan produced all twelve rows and the subtotal 18.36,
     * but the words "Total Factura" landed without their amount, so nothing carried a label. The
     * figure was on the page; only its caption was missing.
     */
    @Test
    fun `an unlabelled figure printed in the foot can still prove a reading`() {
        val reading = LineItemBattery.read(
            wrappedInvoice,
            LineItemBattery.Targets(invoiceTotal = null, labelledOther = listOf(0.0, 18.36))
        )!!

        assertEquals("numeric-tail", reading.templateId)
        assertEquals(12, reading.rows.size)
        assertEquals(18.36, reading.matchedTarget, 0.001)
    }

    /** An unlabelled figure is a candidate, not a licence: rows that do not sum to it still lose. */
    @Test
    fun `an unlabelled figure proves nothing on its own`() {
        assertNull(
            LineItemBattery.read(
                wrappedInvoice,
                LineItemBattery.Targets(invoiceTotal = null, labelledOther = listOf(99.99, 55.55))
            )
        )
    }

    /**
     * The hazard admitting bare figures creates, stated so it cannot be forgotten: this invoice's
     * VAT column sums to 3.85, which the document also prints, so a reading that took each row's VAT
     * for its amount reconciles perfectly. It is arithmetically true and semantically wrong.
     *
     * What keeps it from winning in practice is the order: the strict pattern is tried against every
     * candidate before a loose one is tried against any, so as long as the rows' real total is among
     * the candidates — as 18.36 was on the real scan — the honest reading answers first. This test
     * pins the failure mode for the day someone reorders the list.
     */
    @Test
    fun `a VAT column can reconcile against a VAT total when nothing better is offered`() {
        val vatOnly = LineItemBattery.read(
            wrappedInvoice,
            LineItemBattery.Targets(invoiceTotal = null, labelledOther = listOf(3.85))
        )
        assertEquals(3.85, vatOnly!!.matchedTarget, 0.001)

        // Offer the rows' real total as well and the honest reading takes precedence.
        val both = LineItemBattery.read(
            wrappedInvoice,
            LineItemBattery.Targets(invoiceTotal = null, labelledOther = listOf(3.85, 18.36))
        )!!
        assertEquals("numeric-tail", both.templateId)
        assertEquals(18.36, both.matchedTarget, 0.001)
        assertEquals(12, both.rows.size)
    }

    @Test
    fun `without a printed figure to check against, nothing is read`() {
        assertNull(LineItemBattery.read(wrappedInvoice, LineItemBattery.Targets(invoiceTotal = null)))
    }

    @Test
    fun `one matching line is a coincidence, not a table`() {
        assertNull(LineItemBattery.read("Ceva 1 5.00 5.00", LineItemBattery.Targets(invoiceTotal = 5.00)))
    }

    @Test
    fun `the built-in patterns run strictest first`() {
        assertEquals("numeric-tail", LineItemBattery.BUILT_IN.first().id)
        assertEquals("name-amount", LineItemBattery.BUILT_IN.last().id)
        assertNotNull(LineItemBattery.BUILT_IN.first().continuation)
    }
}
