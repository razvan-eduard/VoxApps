package com.voxapps.expenses.data.preferences

import org.junit.Assert.assertEquals
import org.junit.Test

/** Which currency a card or account created from a capture starts in. */
class AccountCurrencyTest {

    private val settings = ExpensesSettings(defaultCurrency = "RON", homeCurrency = "RON")

    @Test
    fun `nothing chosen follows the app's own currency`() {
        assertEquals("RON", settings.copy(defaultAccountCurrency = "").accountCurrencyFor("EUR"))
    }

    @Test
    fun `a currency named outright is that currency, whatever the capture said`() {
        assertEquals("EUR", settings.copy(defaultAccountCurrency = "EUR").accountCurrencyFor("RON"))
        assertEquals("EUR", settings.copy(defaultAccountCurrency = "EUR").accountCurrencyFor(null))
    }

    @Test
    fun `following the capture takes what the capture stated`() {
        val following = settings.copy(
            defaultAccountCurrency = ExpensesSettings.ACCOUNT_CURRENCY_FROM_CAPTURE
        )
        assertEquals("EUR", following.accountCurrencyFor("EUR"))
    }

    /** An account has to be in some currency, and inventing one from silence is no better than
     *  following the default. */
    @Test
    fun `a capture that stated none falls back to the app's currency`() {
        val following = settings.copy(
            defaultAccountCurrency = ExpensesSettings.ACCOUNT_CURRENCY_FROM_CAPTURE
        )
        assertEquals("RON", following.accountCurrencyFor(null))
        assertEquals("RON", following.accountCurrencyFor("  "))
    }

    /** The sentinel is a setting, never a currency — it must not leak into what settles a
     *  spelling that names several. */
    @Test
    fun `the follow-the-capture sentinel is not a currency the device knows`() {
        val following = settings.copy(
            defaultAccountCurrency = ExpensesSettings.ACCOUNT_CURRENCY_FROM_CAPTURE
        )
        assertEquals(setOf("RON"), following.knownCurrencies())
        assertEquals(setOf("RON", "EUR"), following.knownCurrencies(listOf("EUR")))
    }
}
