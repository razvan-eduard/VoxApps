package com.voxapps.expenses.domain.llm

import com.voxapps.expenses.data.preferences.ExpensesSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The three questions a scan mode answers, and the answers each setting gives.
 *
 * Written as a table because that is what the thing is: the settings differ along these axes and
 * nothing else, and the bug this replaced came from one axis being decided separately in two nearly
 * identical functions.
 */
class ScanFlowTest {

    private fun mode(value: String) = ScanFlow.modeOf(ExpensesSettings(scanModelUse = value))

    @Test
    fun `each setting maps to its mode`() {
        assertEquals(ScanMode.FULL, mode(ExpensesSettings.SCAN_MODEL_FULL))
        assertEquals(ScanMode.VENDOR_CATEGORY_AUTO, mode(ExpensesSettings.SCAN_MODEL_HEADER_FOOTER_AUTO))
        assertEquals(ScanMode.VENDOR_CATEGORY_SUGGEST, mode(ExpensesSettings.SCAN_MODEL_HEADER_FOOTER_SUGGEST))
        assertEquals(ScanMode.NONE, mode(ExpensesSettings.SCAN_MODEL_NONE))
    }

    /** A value from a newer build must read as the behaviour installs had before the setting existed,
     *  never as one that silently stops scanning. */
    @Test
    fun `an unrecognised setting reads as the fullest behaviour`() {
        assertEquals(ScanMode.FULL, mode("SOMETHING_A_LATER_VERSION_STORED"))
    }

    @Test
    fun `only the last setting keeps the text on the device`() {
        assertFalse(ScanMode.NONE.sendsToModel)
        assertTrue(ScanMode.VENDOR_CATEGORY_SUGGEST.sendsToModel)
        assertTrue(ScanMode.VENDOR_CATEGORY_AUTO.sendsToModel)
        assertTrue(ScanMode.FULL.sendsToModel)
    }

    /**
     * The record is written before anything is sent whenever the model is not the one deciding: with
     * nothing sent there is no later moment to write it, and with the answer merely offered the
     * expense must already exist for the offer to attach to.
     */
    @Test
    fun `the record is written locally unless the model decides`() {
        assertTrue(ScanMode.NONE.writesRecordLocally)
        assertTrue(ScanMode.VENDOR_CATEGORY_SUGGEST.writesRecordLocally)
        assertFalse(ScanMode.VENDOR_CATEGORY_AUTO.writesRecordLocally)
        assertFalse(ScanMode.FULL.writesRecordLocally)
    }

    /** The promise that items stay on the device must not quietly lapse when none were found. */
    @Test
    fun `only the fullest setting asks for items`() {
        assertTrue(ScanFlow.asksForItems(ScanMode.FULL, engineTakesLongPrompt = true))
        assertFalse(ScanFlow.asksForItems(ScanMode.VENDOR_CATEGORY_AUTO, engineTakesLongPrompt = true))
        assertFalse(ScanFlow.asksForItems(ScanMode.VENDOR_CATEGORY_SUGGEST, engineTakesLongPrompt = true))
        assertFalse(ScanFlow.asksForItems(ScanMode.NONE, engineTakesLongPrompt = true))
    }

    /** An engine that cannot take the longer prompt is refused it even at the fullest setting. */
    @Test
    fun `a weak engine is never sent the item half`() {
        assertFalse(ScanFlow.asksForItems(ScanMode.FULL, engineTakesLongPrompt = false))
    }

    @Test
    fun `only the suggesting setting withholds the answer from the record`() {
        assertFalse(ScanMode.VENDOR_CATEGORY_SUGGEST.appliesAnswer)
        assertTrue(ScanMode.VENDOR_CATEGORY_AUTO.appliesAnswer)
        assertTrue(ScanMode.FULL.appliesAnswer)
    }
}
