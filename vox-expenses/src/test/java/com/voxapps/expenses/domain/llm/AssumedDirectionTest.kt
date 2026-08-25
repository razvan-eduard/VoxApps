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
 * The offline path may now file a message shape nobody has confirmed, using an assumption the user
 * chose. That is a deliberate relaxation of the rule this flow was built on — "a direction nobody
 * has confirmed is exactly the thing this flow refuses to assume" — so what it may and may not do
 * is worth stating rather than leaving to the next reader of a boolean.
 */
class AssumedDirectionTest {

    @Test
    fun `the stored setting reads as a direction, and off reads as nothing`() {
        assertNull(ExpensesSettings.assumedDirectionOf(ExpensesSettings.ASSUME_NOTHING))
        assertEquals(
            TransactionDirection.OUTGOING,
            ExpensesSettings.assumedDirectionOf(ExpensesSettings.ASSUME_OUTGOING)
        )
        assertEquals(
            TransactionDirection.INCOMING,
            ExpensesSettings.assumedDirectionOf(ExpensesSettings.ASSUME_INCOMING)
        )
    }

    /** An install that never touched it keeps waiting for a person, as it always did. */
    @Test
    fun `assuming nothing is the default`() {
        assertEquals(ExpensesSettings.ASSUME_NOTHING, ExpensesSettings().notificationAssumedDirection)
        assertNull(ExpensesSettings.assumedDirectionOf(ExpensesSettings().notificationAssumedDirection))
    }

    /** A value from a newer build, or a corrupt one, must not read as an assumption. */
    @Test
    fun `an unknown value assumes nothing rather than guessing`() {
        assertNull(ExpensesSettings.assumedDirectionOf("SIDEWAYS"))
        assertNull(ExpensesSettings.assumedDirectionOf(""))
    }

    private fun flowSource(): String =
        listOf(
            "src/main/java/com/voxapps/expenses/domain/llm/NotificationExpenseFlow.kt",
            "vox-expenses/src/main/java/com/voxapps/expenses/domain/llm/NotificationExpenseFlow.kt"
        ).map(::File).first { it.exists() }.readText()

    /**
     * What a person taught outranks what a setting assumes, and what a model answered outranks
     * both. The order is the whole safety of the feature: an assumption that could overwrite a
     * confirmed shape would quietly undo the teaching that shape already received.
     */
    @Test
    fun `the assumption is the last resort, never the first`() {
        val text = flowSource()
        // `head` is the answer where this rung lets it apply — see the flow's commit.
        val written = text.indexOf("direction = head?.direction")
        assertTrue("the record has to name its direction rather than take a type default", written > 0)

        val fromModel = text.indexOf("head?.direction", written)
        val fromTemplate = text.indexOf("f?.direction", written)
        val fromSetting = text.indexOf("assumedDirection()", written)
        assertTrue("a taught direction must be preferred to an assumed one", fromTemplate < fromSetting)
        assertTrue("a model's answer must be preferred to both", fromModel < fromTemplate)
    }

    /**
     * And it may only complete a reading that has an amount. Without one there is no record to
     * write, which is what keeps a promotional message harmless — an assumption must not turn
     * "50% off, 199 RON" into a payment.
     */
    @Test
    fun `an assumption cannot stand in for a missing amount`() {
        val text = flowSource()
        val completeAt = text.indexOf("complete = hasAmount")
        assertTrue("completeness still starts from the amount", completeAt > 0)
        assertFalse(
            "the assumption must not be able to complete a reading on its own",
            Regex("""complete\s*=\s*assumed""").containsMatchIn(text)
        )
    }

    /**
     * The relaxation is offerable only because it is reversible: the record is linked to the shape
     * that produced it, so correcting the direction is the confirmation that shape never had.
     */
    @Test
    fun `a filed record stays linked to the shape it came from`() {
        assertTrue(flowSource().contains("templateDirectionMemory.linkRecord"))
    }
}
