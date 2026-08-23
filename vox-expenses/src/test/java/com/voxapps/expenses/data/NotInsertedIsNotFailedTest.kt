package com.voxapps.expenses.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Not every refusal to insert is a failure to save.
 *
 * Three outcomes mean the record is already in the list, and each arrived separately — so the caller
 * that reports failures kept being written as "everything at or below zero except the ones I
 * remember". A refused duplicate was told to the user as a payment that had not saved, while the
 * payment sat in their list; the advice to try again would have been refused for the same reason.
 */
class NotInsertedIsNotFailedTest {

    /** The rule the reply handler applies. */
    private fun reportsFailure(result: Long) = result <= 0 && result !in RECOGNIZED_NOT_INSERTED

    @Test
    fun `a duplicate refused at insert is not a failure`() {
        assertFalse(reportsFailure(DUPLICATE_ENTRY_RESULT))
    }

    @Test
    fun `a near-duplicate folded into an existing record is not a failure`() {
        assertFalse(reportsFailure(NEAR_DUPLICATE_MERGED_RESULT))
    }

    @Test
    fun `a capture answered a second time is not a failure`() {
        assertFalse(reportsFailure(ALREADY_PRESENT_RESULT))
    }

    @Test
    fun `a real database failure still is`() {
        assertTrue(reportsFailure(-1L))
        assertTrue(reportsFailure(0L))
    }

    @Test
    fun `an inserted row is never reported as a failure`() {
        assertFalse(reportsFailure(1L))
        assertFalse(reportsFailure(9_999L))
    }

    /**
     * The set has to hold every sentinel that means "already there". A new one added to the
     * repository and forgotten here is the exact shape of the bug this file exists for.
     */
    @Test
    fun `every already-there sentinel is accounted for`() {
        val sentinels = listOf(DUPLICATE_ENTRY_RESULT, NEAR_DUPLICATE_MERGED_RESULT, ALREADY_PRESENT_RESULT)
        assertTrue(RECOGNIZED_NOT_INSERTED.containsAll(sentinels))
        assertTrue("distinct values, or one masks another", sentinels.distinct().size == sentinels.size)
    }
}
