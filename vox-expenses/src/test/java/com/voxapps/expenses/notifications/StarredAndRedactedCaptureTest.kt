package com.voxapps.expenses.notifications

import android.content.Context
import com.voxapps.expenses.data.TransactionDirection
import com.voxapps.expenses.data.preferences.ExpensesSettings
import com.voxapps.expenses.di.ExpensesContainer
import com.voxapps.expenses.domain.llm.CapturedNotification
import com.voxapps.expenses.domain.llm.NotificationExpenseFlow
import com.voxapps.recordflow.Decision
import com.voxapps.recordflow.LlmLevel
import com.voxapps.recordflow.RecordFlowPolicy
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two facts a capture can carry beside its text, and what each is worth on the offline rung.
 *
 * The banking star is the person's standing statement about a whole source; the platform's
 * code-protection guard is the system's statement that a body was withheld. One makes a figure
 * fileable without a vendor, the other makes a missing figure worth a review entry — and neither
 * may outrank a sentence shape a person actually taught.
 */
class StarredAndRedactedCaptureTest {

    private fun flow(settings: ExpensesSettings = ExpensesSettings()): NotificationExpenseFlow {
        val container = mockk<ExpensesContainer>()
        every { container.settingsRepository } returns mockk {
            coEvery { getSnapshot() } returns settings
        }
        return NotificationExpenseFlow(mockk<Context>(relaxed = true), container)
    }

    private fun captured(
        amount: Double?,
        starred: Boolean = false,
        redacted: Boolean = false,
        knownPayment: Boolean = false,
        direction: TransactionDirection? = null
    ) = CapturedNotification(
        title = "LIDL RO-490",
        text = amount?.let { "%.2f RON with Pluxee Gusto ••9138".format(it) },
        amount = amount,
        vendor = null,
        bank = null,
        currency = amount?.let { "RON" },
        templateHash = null,
        direction = direction,
        knownPayment = knownPayment,
        fromStarredBank = starred,
        redacted = redacted
    )

    @Test
    fun `a figure from a starred bank files itself, vendor or no vendor`() = runBlocking {
        val reading = flow().read(captured(amount = 79.61, starred = true))
        assertTrue(reading.usable)
        assertTrue(reading.complete)
        assertEquals(Decision.COMMIT, RecordFlowPolicy.decide(LlmLevel.NONE, reading))
    }

    @Test
    fun `a taught shape still outranks the star`() = runBlocking {
        val reading = flow().read(
            captured(
                amount = 79.61, starred = true,
                knownPayment = true, direction = TransactionDirection.INCOMING
            )
        )
        assertTrue(reading.usable)
        assertFalse("a shape taught as incoming is not an expense to file", reading.complete)
    }

    @Test
    fun `a gutted delivery waits in review instead of vanishing`() = runBlocking {
        val reading = flow().read(captured(amount = null, redacted = true))
        assertTrue("withheld body still leaves something to keep", reading.usable)
        assertFalse(reading.complete)
        assertEquals(Decision.QUEUE_FOR_REVIEW, RecordFlowPolicy.decide(LlmLevel.NONE, reading))
    }

    @Test
    fun `an unstarred figureless message still ends silently`() = runBlocking {
        val reading = flow().read(captured(amount = null))
        assertFalse(reading.usable)
        assertEquals(Decision.DISCARD, RecordFlowPolicy.decide(LlmLevel.NONE, reading))
    }

    @Test
    fun `the star supplies no figure`() = runBlocking {
        val reading = flow().read(captured(amount = null, starred = true))
        assertFalse("a bank's message without a sum is still nothing to file", reading.complete)
    }
}
