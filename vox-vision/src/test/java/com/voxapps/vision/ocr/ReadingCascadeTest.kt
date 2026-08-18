package com.voxapps.vision.ocr

import com.voxapps.docread.ReceiptSections
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The stopping rule, exercised without a camera: what the cascade keeps and how many renderings it
 * is willing to pay for.
 *
 * The two texts below differ only in whether their rows add up. That is the whole judgement — the
 * cascade has no opinion about contrast or tone, only about whether a page came back readable.
 */
class ReadingCascadeTest {

    /** Twelve rows of two at their own unit price, summing to the 18.36 the table itself prints. */
    private val readable: String = run {
        val unitPrices = listOf(2.35, 1.62, 1.61, 0.60, 1.00, 0.24, 0.10, 0.50, 0.12, 0.14, 0.70, 0.20)
        val rows = unitPrices.mapIndexed { index, unit ->
            "Service ${index + 1} | 2 | ${"%.2f".format(unit)} | ${"%.2f".format(unit * 2)}"
        }.joinToString("\n")
        """
            ${ReceiptSections.ITEMS_MARKER}
            $rows
             | - | - | 18.36 | 3.85
            ${ReceiptSections.FOOTER_MARKER}
            Total Factura
            Sold Anterior 22.21
            Total de Plata 44.42
            66.63
        """.trimIndent()
    }

    /** The same page as it arrives when the geometry is lost: figures, but no rows to add up. */
    private val unreadable = """
        51.33 18.66 32.67 5 (3 x 4) - lei - Valoarea
        Total de Plata 170.91
    """.trimIndent()

    private fun pass(name: String, text: String?, ran: MutableList<String>) =
        ReadingCascade.Pass(name) { ran += name; text }

    @Test
    fun `a page that reads costs one pass`() = runBlocking {
        val ran = mutableListOf<String>()
        val kept = ReadingCascade.choose(
            listOf(
                pass("normal", readable, ran),
                pass("binarised", unreadable, ran)
            )
        )
        assertEquals(readable, kept)
        assertEquals(listOf("normal"), ran)
    }

    @Test
    fun `a later rendering is kept only when it closes`() = runBlocking {
        val ran = mutableListOf<String>()
        val kept = ReadingCascade.choose(
            listOf(
                pass("normal", unreadable, ran),
                pass("binarised", readable, ran),
                pass("inverted", unreadable, ran)
            )
        )
        assertEquals(readable, kept)
        assertEquals(listOf("normal", "binarised"), ran)
    }

    @Test
    fun `when nothing closes the first reading is what ships`() = runBlocking {
        val ran = mutableListOf<String>()
        val kept = ReadingCascade.choose(
            listOf(
                pass("normal", unreadable, ran),
                pass("binarised", "something else that does not add up", ran)
            )
        )
        assertEquals(unreadable, kept)
        assertEquals(listOf("normal", "binarised"), ran)
    }

    /** A rendering that could not be produced costs its own pass and nothing else. */
    @Test
    fun `a rendering that fails does not end the cascade`() = runBlocking {
        val ran = mutableListOf<String>()
        val kept = ReadingCascade.choose(
            listOf(
                pass("normal", unreadable, ran),
                pass("binarised", null, ran),
                pass("inverted", readable, ran)
            )
        )
        assertEquals(readable, kept)
        assertEquals(listOf("normal", "binarised", "inverted"), ran)
    }
}
