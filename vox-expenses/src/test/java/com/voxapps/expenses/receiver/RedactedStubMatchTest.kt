package com.voxapps.expenses.receiver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The heuristic that pairs a redacted stub with the figure the shade renders for it: the sum on the
 * stub's name line or just below, never above, and never a figure another stub already claimed.
 */
class RedactedStubMatchTest {

    // The shade for a wallet payment: the merchant name, then the amount on the next line.
    private val shade = listOf(
        "LIDL RO-490",
        "79,61 RON with Pluxee Gusto",
        "KAUFLAND SB",
        "132,50 RON with ING Card"
    )
    private val amounts = listOf(1 to 79.61, 3 to 132.50)

    @Test
    fun `takes the amount just below the name`() {
        val hit = RedactedStubRecovery.matchAmount(shade, amounts, "LIDL RO-490", emptySet())
        assertEquals(79.61, hit!!.second, 0.001)
    }

    @Test
    fun `a second stub takes its own amount, not the first's`() {
        val first = RedactedStubRecovery.matchAmount(shade, amounts, "LIDL RO-490", emptySet())!!
        val second = RedactedStubRecovery.matchAmount(shade, amounts, "KAUFLAND SB", setOf(first.first))
        assertEquals(132.50, second!!.second, 0.001)
    }

    @Test
    fun `two stubs of the same name never fold onto one figure`() {
        val dup = listOf("LIDL RO-490", "10,00 RON", "LIDL RO-490", "20,00 RON")
        val amt = listOf(1 to 10.0, 3 to 20.0)
        val a = RedactedStubRecovery.matchAmount(dup, amt, "LIDL RO-490", emptySet())!!
        val b = RedactedStubRecovery.matchAmount(dup, amt, "LIDL RO-490", setOf(a.first))!!
        assertEquals(10.0, a.second, 0.001)
        assertEquals(20.0, b.second, 0.001)
    }

    @Test
    fun `a name with no figure below it matches nothing`() {
        val hit = RedactedStubRecovery.matchAmount(
            listOf("SOME PROMO", "no numbers here"), emptyList(), "SOME PROMO", emptySet()
        )
        assertNull(hit)
    }

    @Test
    fun `a figure above the name is not its own`() {
        // "88,00 RON" sits on line 0, the name on line 1 — the sum belongs to whatever was above.
        val lines = listOf("88,00 RON with Card", "LIDL RO-490")
        val hit = RedactedStubRecovery.matchAmount(lines, listOf(0 to 88.0), "LIDL RO-490", emptySet())
        assertNull(hit)
    }
}
