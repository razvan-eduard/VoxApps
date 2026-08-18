package com.voxapps.expenses.domain.llm

import com.voxapps.expenses.data.preferences.ExpensesSettings
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

    @Test
    fun `the capture returns before a prompt is built`() {
        val text = source()
        val guard = text.indexOf("ModelFreeNotificationCreator.isEnabled")
        val prompt = text.indexOf("NotificationExpenseParsePromptBuilder.build(")
        val send = text.indexOf("pendingLlmRequestQueue.enqueueAndSend(")

        assertTrue("the no-model branch has to exist in the listener", guard > 0)
        assertTrue("the setting must be honoured before the prompt is composed", guard < prompt)
        assertTrue("and therefore before anything is sent", guard < send)
    }

    /** The branch has to end the capture, or it would compose the prompt anyway. */
    @Test
    fun `the no-model branch returns`() {
        val text = source()
        val guard = text.indexOf("ModelFreeNotificationCreator.isEnabled")
        val prompt = text.indexOf("NotificationExpenseParsePromptBuilder.build(")
        assertTrue("no return between the guard and the prompt", text.substring(guard, prompt).contains("return"))
    }

    @Test
    fun `only the none setting skips the model`() {
        assertTrue(
            ModelFreeNotificationCreator.isEnabled(
                ExpensesSettings(notificationModelUse = ExpensesSettings.NOTIFICATION_MODEL_NONE)
            )
        )
        assertFalse(
            ModelFreeNotificationCreator.isEnabled(
                ExpensesSettings(notificationModelUse = ExpensesSettings.NOTIFICATION_MODEL_FULL)
            )
        )
    }

    /** An install that never touched the setting keeps sending, which is what it did before it
     *  existed — a default that silently stopped capturing would be a regression, not a promise. */
    @Test
    fun `the default is unchanged behaviour`() {
        assertFalse(ModelFreeNotificationCreator.isEnabled(ExpensesSettings()))
    }
}
