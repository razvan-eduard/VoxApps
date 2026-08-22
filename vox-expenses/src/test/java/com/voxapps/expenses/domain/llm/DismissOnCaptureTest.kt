package com.voxapps.expenses.domain.llm

import com.voxapps.expenses.data.preferences.ExpensesSettings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When a captured notification may be taken out of the shade.
 *
 * Clearing one removes the only copy of a message this app did not write, so the rule is stated
 * once and tested here rather than inferred at each of the two call sites that apply it.
 */
class DismissOnCaptureTest {

    /** The rule as both call sites apply it: asked for, and something was kept. */
    private fun dismisses(kept: NotificationExpenseFlow.Kept, enabled: Boolean): Boolean =
        enabled && kept != NotificationExpenseFlow.Kept.NOTHING

    @Test
    fun `nothing is dismissed unless it was asked for`() {
        assertFalse(dismisses(NotificationExpenseFlow.Kept.RECORD, enabled = false))
        assertFalse(dismisses(NotificationExpenseFlow.Kept.REVIEW, enabled = false))
    }

    @Test
    fun `the default is to leave notifications alone`() {
        assertFalse(
            "clearing a message the app did not write has no undo",
            ExpensesSettings().dismissNotificationOnCapture
        )
    }

    /**
     * A message the app read and threw away is a message you still have to see. This is the case the
     * whole [NotificationExpenseFlow.Kept] distinction exists for: the outcome the flow reports to
     * RecordFlow is the same `Discarded` whether it queued for review or kept nothing at all.
     */
    @Test
    fun `a capture that kept nothing leaves the notification alone`() {
        assertFalse(dismisses(NotificationExpenseFlow.Kept.NOTHING, enabled = true))
    }

    @Test
    fun `a filed expense clears its notification`() {
        assertTrue(dismisses(NotificationExpenseFlow.Kept.RECORD, enabled = true))
    }

    /** A review entry counts: the app has the capture, and the queue is on the same settings page. */
    @Test
    fun `a capture waiting for approval clears its notification too`() {
        assertTrue(dismisses(NotificationExpenseFlow.Kept.REVIEW, enabled = true))
    }
}
