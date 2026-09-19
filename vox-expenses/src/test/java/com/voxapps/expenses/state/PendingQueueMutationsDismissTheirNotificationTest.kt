package com.voxapps.expenses.state

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Approving or dismissing a pending-review entry has to take the notification it came from out of
 * the shade too, or the very next listener reconnect / force-check re-captures it as a new
 * duplicate — indistinguishable, from the outside, from the dismissal never having happened.
 *
 * Checked against the source rather than by instantiating [ExpensesStateManager], which needs
 * dozens of DI dependencies for a class this deep in the container graph: what matters is that
 * each mutator's body calls the existing [com.voxapps.expenses.receiver.PaymentNotificationListenerService.dismissCaptured],
 * and that is a property of the code, the same reasoning `NotificationPathHonoursTheSettingTest`
 * already applies to the sibling capture path.
 */
class PendingQueueMutationsDismissTheirNotificationTest {

    private fun source(): String =
        listOf(
            "src/main/java/com/voxapps/expenses/state/ExpensesStateManager.kt",
            "vox-expenses/src/main/java/com/voxapps/expenses/state/ExpensesStateManager.kt"
        ).map(::File).first { it.exists() }.readText()

    private fun body(text: String, signature: String): String {
        val start = text.indexOf(signature)
        assertTrue("$signature not found", start >= 0)
        val nextFun = text.indexOf("\n    fun ", start + signature.length)
        return text.substring(start, if (nextFun in 0 until Int.MAX_VALUE) nextFun else text.length)
    }

    @Test
    fun `approving an entry dismisses its source notification`() {
        val text = body(source(), "fun approveNotificationExpense(")
        assertTrue(text.contains("dismissCaptured("))
    }

    @Test
    fun `dismissing a single entry dismisses its source notification`() {
        val text = body(source(), "fun dismissNotificationExpense(")
        assertTrue(text.contains("dismissCaptured("))
    }

    @Test
    fun `dismissing all entries dismisses every one of their source notifications`() {
        val text = body(source(), "fun dismissAllNotificationExpenses(")
        assertTrue(text.contains("dismissCaptured("))
        // The keys have to be read before the list is wiped — clearAll() leaves nothing to read.
        val snapshot = text.indexOf("snapshot(")
        val clear = text.indexOf("clearAll(")
        assertTrue("the pending list must be snapshotted before it's cleared", snapshot in 0 until clear)
    }

    /** `dismissNotificationExpense` takes the whole entry, not just its id — the id alone has no
     *  source key to dismiss by. */
    @Test
    fun `dismissNotificationExpense takes the entry, not a bare id`() {
        val text = source()
        assertTrue(text.contains("fun dismissNotificationExpense(entry: PendingNotificationExpense)"))
    }
}
