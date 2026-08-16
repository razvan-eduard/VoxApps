package com.voxapps.docread

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Roles worked out from the figures alone, on the columns of a real scanned table.
 *
 * The columns below are lifted from a genuine reconstruction, gaps included — long descriptions
 * wrapped, and the cells they displaced are the nulls. Nothing here is told what any column means,
 * what language the document is in, or what its headings said.
 */
class ColumnRoleInferenceTest {

    /** Quantity, unit price, value and tax as the reconstruction produced them: four columns, with
     *  the rows whose description wrapped missing their quantity and price. */
    private val quantity = listOf(2.0, null, null, null, 2.0, null, null, null, null, null, null, 2.0)
    private val unitPrice = listOf(2.35, null, null, 0.60, 1.00, 0.24, 0.10, 0.50, 0.12, 0.14, 0.70, 0.20)
    private val value = listOf(4.70, 3.24, 3.22, 1.20, 2.00, 0.48, 0.20, 1.00, 0.24, 0.28, 1.40, 0.40)
    private val vat = listOf(0.99, 0.68, 0.68, 0.25, 0.42, 0.10, 0.04, 0.21, 0.05, 0.06, 0.29, 0.08)

    /**
     * The tax column adds up to the tax total the document prints, so it proves itself against the
     * printed figures exactly as convincingly as the value column does. Only the ratio between them
     * settles which is which — tax is a constant fraction of what it is charged on, and a value is
     * not a constant fraction of anything.
     */
    @Test
    fun `the tax column is told from the value column by its constant rate`() {
        val roles = ColumnRoleInference.infer(
            columns = listOf(quantity, unitPrice, value, vat),
            printedTotals = listOf(18.36, 3.85)
        )

        assertEquals(2, roles.value)
        assertEquals(3, roles.vat)
        assertNotEquals("the tax column was read as the values", roles.value, roles.vat)
        assertEquals(0.21, roles.taxRate!!, 0.02)
    }

    /** Where a column times another gives a third on row after row, those three name themselves. */
    @Test
    fun `quantity and unit price are found by the product they make`() {
        val roles = ColumnRoleInference.infer(
            columns = listOf(quantity, unitPrice, value, vat),
            printedTotals = listOf(18.36)
        )

        assertEquals(0, roles.quantity)
        assertEquals(1, roles.unitPrice)
    }

    /**
     * Order is not assumed anywhere: the same table with its columns printed the other way round
     * must produce the same roles, at their new positions.
     */
    @Test
    fun `the same table read in reverse column order names the same roles`() {
        val roles = ColumnRoleInference.infer(
            columns = listOf(vat, value, unitPrice, quantity),
            printedTotals = listOf(18.36, 3.85)
        )

        assertEquals(1, roles.value)
        assertEquals(0, roles.vat)
        assertTrue(setOf(roles.quantity, roles.unitPrice) == setOf(2, 3))
    }

    /** Columns with no relation between them are not forced into one. */
    @Test
    fun `unrelated columns yield nothing`() {
        val roles = ColumnRoleInference.infer(
            columns = listOf(
                listOf(11.0, 27.0, 3.0, 91.0, 5.0),
                listOf(408.0, 12.0, 76.0, 5.0, 63.0)
            ),
            printedTotals = emptyList()
        )

        assertTrue(roles.isEmpty())
        assertNull(roles.taxRate)
    }
}
