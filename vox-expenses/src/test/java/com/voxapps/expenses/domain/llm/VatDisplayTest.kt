package com.voxapps.expenses.domain.llm

import com.voxapps.expenses.data.preferences.ExpensesSettings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The three settings, against a record that carries a breakdown and one that does not. */
class VatDisplayTest {

    @Test
    fun `always shows regardless of the record`() {
        assertTrue(VatDisplay.shows(ExpensesSettings.VAT_ON, carriesVat = false))
        assertTrue(VatDisplay.shows(ExpensesSettings.VAT_ON, carriesVat = true))
    }

    @Test
    fun `never shows regardless of the record`() {
        assertFalse(VatDisplay.shows(ExpensesSettings.VAT_OFF, carriesVat = true))
        assertFalse(VatDisplay.shows(ExpensesSettings.VAT_OFF, carriesVat = false))
    }

    @Test
    fun `the middle setting asks the record`() {
        assertTrue(VatDisplay.shows(ExpensesSettings.VAT_AUTO, carriesVat = true))
        assertFalse(VatDisplay.shows(ExpensesSettings.VAT_AUTO, carriesVat = false))
    }

    /** The offer belongs to exactly one combination: switched off, and the document has one. */
    @Test
    fun `only a hidden breakdown is offered`() {
        assertTrue(VatDisplay.offersToShow(ExpensesSettings.VAT_OFF, carriesVat = true))
        assertFalse(VatDisplay.offersToShow(ExpensesSettings.VAT_OFF, carriesVat = false))
        assertFalse(VatDisplay.offersToShow(ExpensesSettings.VAT_AUTO, carriesVat = true))
        assertFalse(VatDisplay.offersToShow(ExpensesSettings.VAT_ON, carriesVat = true))
    }

    /** Presence is the signal — the record's own figures, or any line that carries tax. */
    @Test
    fun `a breakdown is recognised from either the totals or the lines`() {
        assertTrue(VatDisplay.carriesVat(netAmount = 18.36, vatAmount = 3.85, itemVatAmounts = emptyList()))
        assertTrue(VatDisplay.carriesVat(netAmount = null, vatAmount = null, itemVatAmounts = listOf(null, 0.99)))
        assertFalse(VatDisplay.carriesVat(netAmount = null, vatAmount = null, itemVatAmounts = listOf(null, null)))
        // A printed zero is not a breakdown worth surfacing on its own.
        assertFalse(VatDisplay.carriesVat(netAmount = null, vatAmount = null, itemVatAmounts = listOf(0.0)))
    }
}
