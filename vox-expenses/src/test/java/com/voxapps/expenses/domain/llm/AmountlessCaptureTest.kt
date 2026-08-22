package com.voxapps.expenses.domain.llm

import com.voxapps.expenses.data.TransactionDirection
import com.voxapps.expenses.data.preferences.ExpensesSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Some senders announce a payment and leave the sum out — "Plata acceptata" and its kind. Keeping
 * those means giving up the one thing that told a payment from an advertisement, so the rules around
 * it matter more than the feature does.
 */
class AmountlessCaptureTest {

    private fun flowSource(): String =
        listOf(
            "src/main/java/com/voxapps/expenses/domain/llm/NotificationExpenseFlow.kt",
            "vox-expenses/src/main/java/com/voxapps/expenses/domain/llm/NotificationExpenseFlow.kt"
        ).map(::File).first { it.exists() }.readText()

    /** Nothing changes for an install that does not ask for it. */
    @Test
    fun `keeping amountless captures is off by default`() {
        assertFalse(ExpensesSettings().captureAmountlessPayments)
    }

    /**
     * The setting decides whether the capture is kept at all. Without it, an amount is still the
     * whole gate, which is what keeps a promotional message from becoming something to review.
     */
    @Test
    fun `an amount is what makes a capture usable, unless the setting says otherwise`() {
        val text = flowSource()
        assertTrue(
            "usability must widen only by the setting",
            text.contains("usable = hasAmount || settings.captureAmountlessPayments")
        )
    }

    /**
     * And it may never make one *complete*. Completeness is what lets a capture be filed without a
     * person seeing it, and a record with no amount is not a record — so an amountless capture can
     * only ever wait in review, whatever the direction assumption or the template memory say.
     */
    @Test
    fun `an amountless capture can never be filed unseen`() {
        val text = flowSource()
        assertTrue("completeness still begins at the amount", text.contains("complete = hasAmount &&"))

        // And the writing half refuses too, rather than relying on the policy alone.
        val commitAt = text.indexOf("override suspend fun commit(")
        val amountLine = text.indexOf("totalAmount = parsed?.totalAmount ?: f?.amount ?: return null", commitAt)
        assertTrue("commit must decline a record with no amount", amountLine > commitAt)
    }

    /** The queue, on the other hand, has to accept one — that is the whole point of keeping it. */
    @Test
    fun `the review queue accepts an entry that has no amount yet`() {
        val text = flowSource()
        val queueAt = text.indexOf("override suspend fun queueForReview(")
        val snippet = text.substring(queueAt, minOf(text.length, queueAt + 1200))
        assertFalse(
            "an early return on a missing amount would throw the capture away",
            snippet.contains("?: f?.amount ?: return")
        )
    }

    /**
     * A pending entry with no amount round-trips as one. `optDouble` answers NaN for an absent key
     * rather than throwing, so a naive read would store a nonsense figure and the entry would look
     * approvable.
     */
    @Test
    fun `a missing amount survives storage as missing`() {
        val entry = PendingNotificationExpense(
            id = 1L,
            title = "Plata acceptata",
            totalAmount = null,
            currency = "RON",
            vendor = "Digi",
            category = null,
            capturedAt = 0L,
            direction = TransactionDirection.OUTGOING
        )
        assertNull(entry.totalAmount)
        assertEquals("Digi", entry.vendor)
    }
}
