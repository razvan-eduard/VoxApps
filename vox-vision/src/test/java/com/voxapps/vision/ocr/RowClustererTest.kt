package com.voxapps.vision.ocr

import org.junit.Assert.assertEquals
import org.junit.Test

class RowClustererTest {

    private fun box(text: String, x: Float, y: Float, w: Float = 100f, h: Float = 20f): RowClusterer.Cell =
        RowClusterer.Cell(text, xLeft = x, yTop = y, yBottom = y + h)

    @Test
    fun `column-first detector order becomes row order`() {
        // The shape a real tabular invoice produced: the detector emitted the whole label column,
        // then the whole value column, and every label arrived separated from its own figure.
        val results = listOf(
            box("Total Factura", 0f, 0f),
            box("Sold Anterior", 0f, 30f),
            box("Total de Plata", 0f, 60f),
            box("22.21", 200f, 0f),
            box("44.42", 200f, 30f),
            box("66.63", 200f, 60f)
        )

        assertEquals(
            "Total Factura 22.21\nSold Anterior 44.42\nTotal de Plata 66.63",
            RowClusterer.toTextFromCells(results)
        )
    }

    @Test
    fun `line items reunite name quantity and price`() {
        val results = listOf(
            box("Paine", 0f, 0f),
            box("Lapte", 0f, 30f),
            box("2", 150f, 0f),
            box("1", 150f, 30f),
            box("3.50", 250f, 0f),
            box("5.20", 250f, 30f)
        )

        assertEquals("Paine 2 3.50\nLapte 1 5.20", RowClusterer.toTextFromCells(results))
    }

    @Test
    fun `a skewed row still clusters`() {
        // A handheld photo tilts rows a few pixels across the page; half-height overlap absorbs it.
        val results = listOf(
            box("Item", 0f, 0f),
            box("9.99", 200f, 7f)
        )
        assertEquals("Item 9.99", RowClusterer.toTextFromCells(results))
    }

    @Test
    fun `adjacent rows do not merge`() {
        val results = listOf(
            box("first", 0f, 0f),
            box("second", 0f, 22f)
        )
        assertEquals("first\nsecond", RowClusterer.toTextFromCells(results))
    }

    @Test
    fun `single-column prose keeps its order`() {
        val results = listOf(
            box("first line", 0f, 0f),
            box("second line", 0f, 30f),
            box("third line", 0f, 60f)
        )
        assertEquals("first line\nsecond line\nthird line", RowClusterer.toTextFromCells(results))
    }

    @Test
    fun `blank fragments are dropped`() {
        val results = listOf(box("kept", 0f, 0f), box("  ", 200f, 0f))
        assertEquals("kept", RowClusterer.toTextFromCells(results))
    }

    @Test
    fun `empty input yields empty text`() {
        assertEquals("", RowClusterer.toTextFromCells(emptyList()))
    }
}
