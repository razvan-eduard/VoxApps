package com.voxapps.expenses.domain.llm

import com.voxapps.expenses.data.preferences.ExpensesSettings
import com.voxapps.recordflow.LlmLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The promise the setting makes is that nothing leaves the device, so it has to be kept before a
 * prompt exists — not before one is sent.
 *
 * Checked against the source rather than by running the listener, which needs a live
 * NotificationListenerService: what matters is the *order* of two statements, and order is a
 * property of the code. The scan path is guarded the same way, for the same reason — see
 * [ScanPathsHonourTheSettingTest].
 */
class NotificationPathHonoursTheSettingTest {

    /**
     * The listener's statements only — imports and comments dropped.
     *
     * Both names looked for below also appear in the file's own prose and import list, above
     * everything: reading those as the call would make the order assertion pass on any arrangement
     * of the code, including a wrong one.
     */
    private fun source(): String =
        listOf(
            "src/main/java/com/voxapps/expenses/receiver/PaymentNotificationListenerService.kt",
            "vox-expenses/src/main/java/com/voxapps/expenses/receiver/PaymentNotificationListenerService.kt"
        ).map(::File).first { it.exists() }.readText()
            .lineSequence()
            .filterNot { line ->
                val t = line.trim()
                t.startsWith("import ") || t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
            }
            .joinToString("\n")

    /**
     * The listener composes no prompt of its own any more, which is a stronger guarantee than the
     * one this test used to make. It used to check that the offline branch came *before* the prompt
     * was built; now there is no prompt here to come before. The only text that can be sent is the
     * one the flow produces, and the flow produces it only where the policy asked for it.
     */
    @Test
    fun `the listener never composes a prompt itself`() {
        val text = source()
        assertTrue(
            "the capture has to run through the shared flow",
            text.contains("RecordFlow.dispatch(")
        )
        assertFalse(
            "a prompt built here would bypass the level entirely",
            text.contains("NotificationExpenseParsePromptBuilder.build(")
        )
    }

    /** And what is sent can only be what the flow handed over. */
    @Test
    fun `sending happens only inside the flow's own send step`() {
        val text = source()
        val dispatch = text.indexOf("RecordFlow.dispatch(")
        val send = text.indexOf("pendingLlmRequestQueue.enqueueAndSend(")
        assertTrue("nothing is sent before the flow has decided", dispatch in 0 until send)
    }

    @Test
    fun `only the none setting keeps the sentence on the device`() {
        assertEquals(
            LlmLevel.NONE,
            ExpensesSettings.notificationLevelOf(ExpensesSettings.NOTIFICATION_MODEL_NONE)
        )
        assertEquals(
            LlmLevel.FULL,
            ExpensesSettings.notificationLevelOf(ExpensesSettings.NOTIFICATION_MODEL_FULL)
        )
        assertTrue(LlmLevel.NONE.staysOnDevice)
        assertFalse(LlmLevel.FULL.staysOnDevice)
    }

    /** An install that never touched the setting keeps sending, which is what it did before it
     *  existed — a default that silently stopped capturing would be a regression, not a promise. */
    @Test
    fun `the default is unchanged behaviour`() {
        assertEquals(LlmLevel.FULL, ExpensesSettings.notificationLevelOf(ExpensesSettings().notificationModelUse))
    }
}
