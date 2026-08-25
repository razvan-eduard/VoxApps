package com.voxapps.expenses.domain.health

import com.voxapps.expenses.data.Expense
import com.voxapps.expenses.data.ExpenseLineItem
import com.voxapps.expenses.data.ExpenseSource
import com.voxapps.expenses.data.ExpenseWithDetails
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExpenseGapsTest {

    private val fallbackCategory = 1L

    private fun record(
        amount: Double = 63.0,
        vendor: String? = "Lidl",
        title: String? = null,
        categoryId: Long? = 5L,
        accountId: Long? = 8L,
        source: ExpenseSource = ExpenseSource.NOTIFICATION,
        stub: Boolean = false,
        edited: Boolean = false,
        items: List<ExpenseLineItem> = emptyList()
    ) = ExpenseWithDetails(
        expense = Expense(
            title = title, totalAmount = amount, currencyCode = "RON", vendor = vendor, location = null, dateTime = 0L, comments = null, categoryId = categoryId,
            bankAccountId = accountId, source = source, isStub = stub, manuallyEdited = edited
        ),
        items = items
    )

    @Test
    fun `a complete record is missing nothing`() {
        assertEquals(emptySet<ExpenseGap>(), ExpenseGaps.of(record(), fallbackCategory))
    }

    @Test
    fun `what is missing is named`() {
        assertTrue(ExpenseGap.NO_AMOUNT in ExpenseGaps.of(record(amount = 0.0), fallbackCategory))
        assertTrue(ExpenseGap.NO_NAME in ExpenseGaps.of(record(vendor = null, title = null), fallbackCategory))
        assertTrue(ExpenseGap.NO_CATEGORY in ExpenseGaps.of(record(categoryId = null), fallbackCategory))
        assertTrue(ExpenseGap.UNREAD in ExpenseGaps.of(record(stub = true), fallbackCategory))
    }

    /** The fallback category is where things land when nothing classified them, so it is a question
     *  rather than an answer — until somebody says otherwise. */
    @Test
    fun `the fallback category counts as none`() {
        assertTrue(ExpenseGap.NO_CATEGORY in ExpenseGaps.of(record(categoryId = fallbackCategory), fallbackCategory))
    }

    /** A title is a name too: a record called "Parking" needs no shop to be recognisable. */
    @Test
    fun `a title is enough of a name`() {
        assertEquals(
            emptySet<ExpenseGap>(),
            ExpenseGaps.of(record(vendor = null, title = "Parking"), fallbackCategory)
        )
    }

    /**
     * The rule that keeps this list worth reading: a record somebody opened and saved is a record
     * they have answered, however incomplete it looks from outside.
     */
    @Test
    fun `a record a person has edited is never nagged about`() {
        val bare = record(amount = 0.0, vendor = null, title = null, categoryId = null, accountId = null, edited = true)
        assertEquals(emptySet<ExpenseGap>(), ExpenseGaps.of(bare, fallbackCategory))
    }

    /** Cash has no card, and asking somebody to attach one is asking for a fiction. */
    @Test
    fun `only a capture is asked which card it was`() {
        assertTrue(
            ExpenseGap.NO_ACCOUNT in ExpenseGaps.of(record(accountId = null, source = ExpenseSource.NOTIFICATION), fallbackCategory)
        )
        assertEquals(
            emptySet<ExpenseGap>(),
            ExpenseGaps.of(record(accountId = null, source = ExpenseSource.MANUAL), fallbackCategory)
        )
        assertEquals(
            emptySet<ExpenseGap>(),
            ExpenseGaps.of(record(accountId = null, source = ExpenseSource.VOICE), fallbackCategory)
        )
    }

    @Test
    fun `rows that contradict the total are named`() {
        val mismatched = record(
            amount = 100.0,
            items = listOf(
                ExpenseLineItem(expenseId = 0, name = "a", quantity = 1.0, unitPrice = 20.0),
                ExpenseLineItem(expenseId = 0, name = "b", quantity = 1.0, unitPrice = 20.0)
            )
        )
        assertTrue(ExpenseGap.TOTALS_DISAGREE in ExpenseGaps.of(mismatched, fallbackCategory))
    }

    @Test
    fun `the list is only what is missing something`() {
        val records = listOf(record(), record(categoryId = null), record(amount = 0.0))
        assertEquals(2, ExpenseGaps.needingAttention(records, fallbackCategory).size)
    }

    /**
     * A device that keeps no cards is not asked which card a payment was on.
     *
     * Otherwise every captured record is named for missing something the person never switched on,
     * and a list that can never be emptied is a list nobody reads.
     */
    @Test
    fun `no card is asked for where no cards are kept`() {
        val capture = record(accountId = null, source = ExpenseSource.NOTIFICATION)
        assertTrue(ExpenseGap.NO_ACCOUNT in ExpenseGaps.of(capture, fallbackCategory, accountsInUse = true))
        assertEquals(emptySet<ExpenseGap>(), ExpenseGaps.of(capture, fallbackCategory, accountsInUse = false))
    }
}
