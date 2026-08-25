package com.voxapps.expenses.domain.llm

import com.voxapps.expenses.data.Expense
import com.voxapps.expenses.data.ExpenseSource
import com.voxapps.expenses.data.enrichWithNearDuplicate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * One payment announced by two apps must become one record, and the announcement that says more
 * must win whichever of them arrives first.
 *
 * Before this existed, the second announcement built a record, offered it, and was turned away by
 * the duplicate check at the storage layer — which reported the refusal as a payment that had failed
 * to save, and left the surviving record as whichever message happened to be delivered first.
 */
class SecondNoticeTest {

    private val t0 = 1_800_000_000_000L

    private fun notice(
        vendor: String? = null,
        bank: String? = null,
        amount: Double = 223.53,
        currency: String = "RON",
        at: Long = t0,
        source: ExpenseSource = ExpenseSource.NOTIFICATION,
        edited: Boolean = false
    ) = Expense(
        title = vendor ?: bank,
        totalAmount = amount,
        currencyCode = currency,
        dateTime = at,
        source = source,
        manuallyEdited = edited,
        vendor = vendor
    )

    // --- what counts as the same payment ---

    @Test
    fun `the same figure moments later from another app is the same payment`() {
        assertTrue(
            SecondNotice.isAnotherNoticeOf(
                notice(bank = "SchemeCard"),
                notice(vendor = "SHOP RO-490", at = t0 + 40_000)
            )
        )
    }

    @Test
    fun `the same figure much later is a second payment`() {
        assertFalse(
            SecondNotice.isAnotherNoticeOf(
                notice(bank = "SchemeCard"),
                notice(vendor = "SHOP", at = t0 + TimeUnit.HOURS.toMillis(2))
            )
        )
    }

    /** Two coffees at the same price minutes apart are two coffees; the figure has to match exactly. */
    @Test
    fun `a different figure is a different payment`() {
        assertFalse(
            SecondNotice.isAnotherNoticeOf(notice(amount = 223.53), notice(amount = 223.54, at = t0 + 20_000))
        )
    }

    @Test
    fun `the same number in another currency is not the same payment`() {
        assertFalse(
            SecondNotice.isAnotherNoticeOf(notice(currency = "RON"), notice(currency = "EUR", at = t0 + 20_000))
        )
    }

    /** Only announcements correlate. A scan or a typed record that happens to match is untouched. */
    @Test
    fun `only captures from notifications fold together`() {
        assertFalse(
            SecondNotice.isAnotherNoticeOf(
                notice(source = ExpenseSource.SCAN),
                notice(at = t0 + 20_000)
            )
        )
        assertFalse(
            SecondNotice.isAnotherNoticeOf(
                notice(),
                notice(at = t0 + 20_000, source = ExpenseSource.MANUAL)
            )
        )
    }

    /** A record someone has worked on is theirs; a later announcement must not rewrite it. */
    @Test
    fun `a hand-edited record is never folded into`() {
        assertFalse(
            SecondNotice.isAnotherNoticeOf(
                notice(edited = true),
                notice(vendor = "SHOP RO-490", at = t0 + 20_000)
            )
        )
    }

    // --- and what the surviving record holds ---

    /**
     * The whole point: the announcement naming the merchant supplies it in either order, so the
     * record no longer depends on which app the phone delivered first.
     */
    @Test
    fun `the merchant survives whichever announcement arrives first`() {
        val withMerchant = notice(vendor = "SHOP RO-490")
        val without = notice(bank = "SchemeCard")

        assertEquals("SHOP RO-490", enrichWithNearDuplicate(existing = without, candidate = withMerchant).vendor)
        assertEquals("SHOP RO-490", enrichWithNearDuplicate(existing = withMerchant, candidate = without).vendor)
    }
}
