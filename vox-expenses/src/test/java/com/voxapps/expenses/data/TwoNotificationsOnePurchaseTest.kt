package com.voxapps.expenses.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * One purchase announced twice — by the wallet that carries the card and by the scheme that issued
 * it — must end as one record that keeps whichever announcement said more.
 *
 * Only one of the two names the merchant, and which of them the app happens to read first is not
 * something either side controls. So the merge has to be indifferent to arrival order, and the test
 * is written in both directions for that reason rather than for symmetry's own sake.
 */
class TwoNotificationsOnePurchaseTest {

    private fun captured(vendor: String?, accountId: Long? = null) = Expense(
        title = vendor,
        totalAmount = 223.53,
        currencyCode = "RON",
        dateTime = 1_000L,
        source = ExpenseSource.NOTIFICATION,
        vendor = vendor,
        bankAccountId = accountId
    )

    /** What the wallet's message yields once the scheme is a known issuer: the merchant. */
    private val fromWallet = captured(vendor = "LIDL RO-490", accountId = 3L)

    /** What the scheme's own message yields: no merchant — it never names one. */
    private val fromScheme = captured(vendor = null)

    @Test
    fun `the merchant survives when the scheme's message is filed first`() {
        val merged = enrichWithNearDuplicate(existing = fromScheme, candidate = fromWallet)
        assertEquals("LIDL RO-490", merged.vendor)
        assertEquals(3L, merged.bankAccountId)
    }

    @Test
    fun `the merchant survives when the wallet's message is filed first`() {
        val merged = enrichWithNearDuplicate(existing = fromWallet, candidate = fromScheme)
        assertEquals("a later message that names nothing must not blank what is known", "LIDL RO-490", merged.vendor)
    }

    /**
     * The state before the scheme was listed as an issuer, and the point of the whole exercise:
     * neither message resolved a merchant, so there was nothing for the merge to rescue. The record
     * being short a vendor was decided when each message was read, not when they were folded
     * together.
     */
    @Test
    fun `two vendorless messages merge to a vendorless record`() {
        val merged = enrichWithNearDuplicate(existing = fromScheme, candidate = captured(vendor = null))
        assertNull(merged.vendor)
    }

    /** Identity stays with the row being written back to, whichever side's content wins. */
    @Test
    fun `merging keeps the existing row's identity`() {
        val existing = fromScheme.copy(id = 7, uid = "u-7", createdAt = 500L)
        val merged = enrichWithNearDuplicate(existing = existing, candidate = fromWallet)
        assertEquals(7L, merged.id)
        assertEquals("u-7", merged.uid)
        assertEquals(500L, merged.createdAt)
    }
}
