package com.voxapps.expenses.receiver

import com.voxapps.expenses.data.Category
import com.voxapps.expenses.data.Expense
import com.voxapps.expenses.data.ExpensesRepository
import com.voxapps.expenses.data.preferences.ExpensesSettings
import com.voxapps.expenses.data.preferences.ExpensesSettingsRepository
import com.voxapps.expenses.state.SessionManager
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExpensesReadResponderTest {

    private val settingsRepo = mockk<ExpensesSettingsRepository>()
    private val sessionManager = mockk<SessionManager>()
    private val expensesRepo = mockk<ExpensesRepository>()
    // Localized by the caller (ExpensesContainer.lockedMessage) and passed in, so this responder
    // stays free of Android and of any particular language.
    private val lockedMessage = "The expenses are locked. Unlock the app."
    private val responder = ExpensesReadResponder(settingsRepo, sessionManager, expensesRepo, lockedMessage)

    @Test
    fun `date-range read includes id and colorArgb for Calendar's day-summary sheet`() = runTest {
        every { settingsRepo.getSnapshot() } returns ExpensesSettings(isBiometricRequired = false)
        coEvery { expensesRepo.expensesForDateRange(any(), any()) } returns listOf(
            Expense(id = 5, title = "Categorized", totalAmount = 10.0, currencyCode = "RON", dateTime = 2_000L, categoryId = 1),
            Expense(id = 6, title = "Uncategorized", totalAmount = 20.0, currencyCode = "RON", dateTime = 3_000L, categoryId = null)
        )
        every { expensesRepo.categories } returns flowOf(
            listOf(Category(id = 1, name = "Shopping", colorArgb = 0xFFAB47BCL, createdAt = 0L))
        )

        val result = responder.respond(dateFrom = 0L, dateTo = 10_000L)

        assertTrue(result.ok)
        val items = JSONObject(result.text).getJSONArray("items")
        val categorized = items.getJSONObject(0)
        assertEquals(5L, categorized.getLong("id"))
        assertEquals(0xFFAB47BCL, categorized.getLong("colorArgb"))
        val uncategorized = items.getJSONObject(1)
        assertEquals(6L, uncategorized.getLong("id"))
        assertFalse(uncategorized.has("colorArgb"))
    }

    @Test
    fun `locked read never touches the DB and returns the spoken message`() = runTest {
        every { settingsRepo.getSnapshot() } returns ExpensesSettings(isBiometricRequired = true)
        every { sessionManager.isSessionValid(any()) } returns false

        val result = responder.respond()

        assertFalse(result.ok)
        assertEquals(lockedMessage, result.text)
    }
}
