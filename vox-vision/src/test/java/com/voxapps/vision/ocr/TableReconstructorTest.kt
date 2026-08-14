package com.voxapps.vision.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Geometry replicating the real-invoice failure modes that motivated [TableReconstructor]:
 * a tariff table whose description cells wrap onto their own printed rows, a dense block of
 * short rows that [RowClusterer]'s expanding-row growth used to chain-merge into one line, and
 * header/totals regions that must pass through untouched.
 */
class TableReconstructorTest {

    private var nextY = 0f

    /** One printed row of cells at the same y; x positions given per cell. */
    private fun row(vararg cells: Pair<Float, String>, height: Float = 10f, gap: Float = 14f): List<RowClusterer.Cell> {
        val y = nextY
        nextY += gap
        return cells.map { (x, text) ->
            RowClusterer.Cell(text, x, y, y + height, x + text.length * 6f)
        }
    }

    private fun invoiceCells(): List<RowClusterer.Cell> {
        nextY = 0f
        val cells = mutableListOf<RowClusterer.Cell>()
        cells += row(0f to "FACTURA", 200f to "Serie:", 260f to "FPHB")
        cells += row(0f to "Data:", 200f to "09/07/2026")
        // Data rows: description + qty(400) unit(470) value(540) vat(610) columns.
        cells += row(0f to "Tarif colectare separata", 400f to "2", 470f to "2.35", 540f to "4.70", 610f to "0.99")
        cells += row(0f to "deseuri reciclabile continuare")           // wrapped description
        cells += row(0f to "Tarif biodeseuri", 400f to "2", 470f to "1.62", 540f to "3.24", 610f to "0.68")
        // Dense block — five short rows that used to chain-merge.
        cells += row(0f to "Tarif depozitare reziduale", 400f to "2", 470f to "0.12", 540f to "0.24", 610f to "0.05", gap = 12f)
        cells += row(0f to "Tarif depozitare biodeseuri", 400f to "2", 470f to "0.24", 540f to "0.48", 610f to "0.10", gap = 12f)
        cells += row(0f to "Tarif depozitare reciclabile", 400f to "2", 470f to "0.50", 540f to "1.00", 610f to "0.21", gap = 12f)
        cells += row(0f to "Tarif TMB reziduale", 400f to "2", 470f to "0.10", 540f to "0.20", 610f to "0.04", gap = 12f)
        cells += row(0f to "Tarif TMB biodeseuri", 400f to "2", 470f to "0.24", 540f to "0.48", 610f to "0.10", gap = 12f)
        cells += row(0f to "Total", 60f to "Factura", 200f to "22.21")
        cells += row(0f to "Total", 60f to "de", 90f to "Plata", 200f to "66.63")
        return cells
    }

    @Test
    fun `invoice table reconstructs one line per logical row with banded columns`() {
        val text = TableReconstructor.toText(invoiceCells())!!
        val lines = text.lines()
        val firstItem = lines.first { it.startsWith("Tarif colectare separata") }
        assertEquals("Tarif colectare separata deseuri reciclabile continuare | 2 | 2.35 | 4.70 | 0.99", firstItem)
        val second = lines.first { it.startsWith("Tarif biodeseuri") }
        assertEquals("Tarif biodeseuri | 2 | 1.62 | 3.24 | 0.68", second)
    }

    @Test
    fun `dense short rows never chain-merge`() {
        val text = TableReconstructor.toText(invoiceCells())!!
        val depositRows = text.lines().filter { it.startsWith("Tarif depozitare") || it.startsWith("Tarif TMB") }
        assertEquals(5, depositRows.size)
        assertTrue(depositRows[0].endsWith("| 2 | 0.12 | 0.24 | 0.05"))
        assertTrue(depositRows[4].endsWith("| 2 | 0.24 | 0.48 | 0.10"))
    }

    @Test
    fun `header and totals regions pass through in reading order`() {
        val text = TableReconstructor.toText(invoiceCells())!!
        val lines = text.lines()
        assertEquals("FACTURA Serie: FPHB", lines[0])
        assertTrue(lines.any { it == "Total Factura 22.21" })
        assertTrue(lines.last().contains("66.63"))
    }

    @Test
    fun `a document without repeating numeric columns is not a table`() {
        nextY = 0f
        val cells = buildList {
            addAll(row(0f to "Doar", 60f to "text", 120f to "liber"))
            addAll(row(0f to "cu", 60f to "un", 120f to "singur"))
            addAll(row(0f to "numar", 60f to "12.50"))
            addAll(row(0f to "si", 60f to "atat"))
            addAll(row(0f to "restul", 60f to "vorbe"))
            addAll(row(0f to "numai", 60f to "text"))
        }
        assertNull(TableReconstructor.toText(cells))
    }
}
