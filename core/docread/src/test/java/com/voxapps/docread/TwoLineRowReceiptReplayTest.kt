package com.voxapps.docread

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A restaurant bill that prints its rows over two lines and suggests a tip underneath.
 *
 * Two shapes meet here that no other fixture has. The name of each dish is printed on its own line
 * with the figures below it, so a row has no description beside its numbers. And the foot carries a
 * courtesy table — 10%, 12%, 15% — whose every column is captioned "Total" and whose every figure
 * exceeds what was actually charged, so a document that states one total in fact states four.
 *
 * The fixture is the text one photograph of that bill produced through this app's own OCR, kept as it
 * came: "Ora" read as "0ra", "lipoveneasca" as "1ipoveneasca", "0.3l" as "0.31", and the geometric
 * reconstruction below the marker interleaving names from adjacent rows.
 *
 * Its column rules are worth reading closely, because they are why the row pattern is shaped the way
 * it is. The same bar, printed the same way down one page, survives recognition three different ways:
 * kept ("1.00| 16.00|"), absorbed onto the figure it touches ("19.001"), and lost altogether
 * ("36.00 72.00"). A pattern that assumed any one of those would read part of a page and refuse the
 * rest, which is worse than refusing all of it.
 */
class TwoLineRowReceiptReplayTest {

    private val rawText: String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream("scan-receipt-two-line-rows.txt")) {
            "missing receipt fixture"
        }.bufferedReader().use { it.readText() }

    private fun read() = ScanReading.of(rawText, TableItemsPreParse.plainText(rawText))

    /** What the bill charges, not what it suggests adding to it. */
    @Test
    fun `the total is the amount charged rather than the largest one printed`() {
        assertEquals(287.0, read().totals.total!!, 0.005)
    }

    @Test
    fun `the seven rows are read with their names and quantities intact`() {
        val items = read().items
        assertNotNull("a bill whose rows sum exactly should not read as itemless", items)
        assertEquals(7, items!!.size)
        assertEquals(287.0, items.sumOf { it.quantity * it.unitPrice }, 0.005)

        // The one row that is not a single unit — proof the quantity column was read rather than
        // inferred from the line's amount. Matched on the tail, since this line is also where the
        // reader turned an l into a 1.
        val soup = items.single { it.name.contains("ipoveneasca") }
        assertEquals(2.0, soup.quantity, 0.001)
        assertEquals(36.0, soup.unitPrice, 0.005)

        // A name is the line above its figures and nothing else: the letterhead, the address and the
        // column captions all precede the first row and must not have landed on it.
        assertEquals("beck s 0.33", items.first().name)
        assertTrue(items.none { it.name.contains("SCOICA") || it.name.contains("Produs") })
    }

    /** The tip suggestions are the arithmetic's to reject, so they must reach it as candidates. */
    @Test
    fun `every labelled total is offered, the largest still first`() {
        val others = ReceiptTotalRegexParser.others(TableItemsPreParse.plainText(rawText))

        assertEquals(330.0, ReceiptTotalRegexParser.parse(TableItemsPreParse.plainText(rawText)).total!!, 0.005)
        assertEquals(listOf(321.0, 316.0, 287.0), others)
    }
}
