package com.voxapps.expenses.domain.llm

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The notification path answers to its rung the way the scan does.
 *
 * Read from the source, as the other rules about this flow are: what matters is the shape of the
 * decision, and the flow's real collaborators are a database, a settings store and a suggestion
 * store — assembling them to assert one precedence would test the assembly.
 */
class NotificationHonoursTheLevelTest {

    private fun source(name: String) =
        File("src/main/java/com/voxapps/expenses/domain/llm/$name").readText()

    private val flow = source("NotificationExpenseFlow.kt")
    private val scan = source("ExpenseScanFlow.kt")

    /** The figure the characters proved is the most certain thing a message carries; an answer that
     *  read it differently read it wrong. */
    @Test
    fun `the proved amount is taken before the answered one`() {
        val commitAt = flow.indexOf("override suspend fun commit(")
        val proved = flow.indexOf("f?.amount", commitAt)
        val answered = flow.indexOf("parsed?.totalAmount", commitAt)
        assertTrue("commit must read the amount at all", proved > 0 && answered > 0)
        assertTrue("what was proved must be preferred to what was answered", proved < answered)
    }

    /** The gate the whole level system rests on: a rung that writes none of the answer must not
     *  write it anyway. */
    @Test
    fun `the level decides whether the answer reaches the record`() {
        assertTrue(
            "the notification flow must consult its rung",
            flow.contains("applies(FieldWeight.HEAD)")
        )
        assertTrue(
            "the scan already does, and the two must not drift",
            scan.contains("applies(FieldWeight.HEAD)")
        )
    }

    /** What the record did not take is not thrown away: it waits as something to accept. */
    @Test
    fun `what the level withheld is offered instead`() {
        assertTrue(flow.contains("offerWhatWasNotWritten"))
        assertTrue(flow.contains("suggestionStore.offer"))
    }

    /** And the one thing never offered: a second opinion about a figure that was already legible. */
    @Test
    fun `an amount is only offered where the message proved none`() {
        val at = flow.indexOf("private suspend fun offerWhatWasNotWritten")
        assertTrue("the offer must exist", at > 0)
        val body = flow.substring(at, minOf(flow.length, at + 1400))
        assertTrue(
            "an answered amount may only be offered when nothing was proved",
            body.contains("takeIf { amountProved }")
        )
        assertTrue(
            "with the head applied and the amount proved there is nothing to offer",
            body.contains("headApplied && amountProved")
        )
    }
}
