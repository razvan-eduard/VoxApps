package com.voxapps.expenses.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A rule may trigger on an amount, and the engine compares normalized strings — so a figure needs
 * exactly one spelling or 160, 160.00 and 160,00 become three different triggers that each match a
 * third of the time. Cents as a plain integer is that spelling, and both sides go through it: the
 * record's figure and the one the person typed.
 *
 * Triggering is the only thing an amount may do here. Writing one would be a falsification rather
 * than a re-map, which is why it appears in the match fields and has no counterpart among the set
 * fields — asserted below so the pairing cannot drift.
 */
class AmountTriggerTest {

    @Test
    fun `one figure has one key, however it is written`() {
        val expected = ExpenseRemapFields.amountKey(160.0)
        assertNotNull(expected)
        for (typed in listOf("160", "160.0", "160.00", "160,00", " 160 ", "160,0")) {
            assertEquals("\"$typed\" should be the same trigger", expected, ExpenseRemapFields.amountKeyOf(typed))
        }
    }

    @Test
    fun `cents are kept, not rounded away`() {
        assertEquals(ExpenseRemapFields.amountKey(160.5), ExpenseRemapFields.amountKeyOf("160,50"))
        assertTrue(ExpenseRemapFields.amountKey(160.5) != ExpenseRemapFields.amountKey(160.0))
        assertEquals("16050", ExpenseRemapFields.amountKey(160.50))
    }

    /** Floating point being what it is, the key has to come from rounding rather than truncation. */
    @Test
    fun `a figure that does not divide cleanly still lands on its own cent`() {
        assertEquals("1015", ExpenseRemapFields.amountKey(10.15))
        assertEquals("2", ExpenseRemapFields.amountKey(0.02))
    }

    @Test
    fun `nothing typed, or nonsense typed, is not a trigger`() {
        assertNull(ExpenseRemapFields.amountKeyOf(""))
        assertNull(ExpenseRemapFields.amountKeyOf("   "))
        assertNull(ExpenseRemapFields.amountKeyOf("about a hundred"))
        assertNull(ExpenseRemapFields.amountKey(null))
    }

    /** A record with no amount, or a zero one, matches no amount rule rather than matching them
     *  all — the absence of a figure is not a figure. */
    @Test
    fun `no amount is not the same as an amount of zero`() {
        assertNull(ExpenseRemapFields.amountKey(null))
        assertNull(ExpenseRemapFields.amountKey(0.0))
        assertNull(ExpenseRemapFields.amountKeyOf("0"))
    }

    @Test
    fun `the amount triggers but is never written`() {
        assertTrue(
            "the amount must be offerable as a trigger",
            ExpenseRemapFields.matchFields.any { it.id == ExpenseRemapFields.ID_AMOUNT }
        )
        assertTrue(
            "writing an amount would be a falsification, not a re-map",
            ExpenseRemapFields.setFields(emptyList()).none { it.id == ExpenseRemapFields.ID_AMOUNT }
        )
    }

    /** And the field reads the figure off the record it is given. */
    @Test
    fun `the trigger reads the draft's own amount`() {
        val field = ExpenseRemapFields.matchFields.first { it.id == ExpenseRemapFields.ID_AMOUNT }
        val draft = ExpenseRemapFields.Draft(
            totalAmount = 160.0, title = null, vendor = null, bank = null,
            location = null, comments = null, category = null
        )
        assertEquals(ExpenseRemapFields.amountKey(160.0), field.valueOf(draft))
        assertNull(field.valueOf(draft.copy(totalAmount = null)))
    }
}
