package com.voxapps.expenses.state

import com.voxapps.design.filter.VoxRange
import com.voxapps.expenses.data.Expense
import com.voxapps.expenses.data.ExpenseWithDetails
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Narrowing the list. Every filter is independent and they compose by conjunction — a list narrowed
 * two ways shows what satisfies both, never what satisfies either.
 */
class ExpenseFilterTest {

    private fun priced(id: Long, amount: Double) = ExpenseWithDetails(
        expense = Expense(id = id, totalAmount = amount, currencyCode = "RON", dateTime = 1_000L),
        items = emptyList(),
        category = null
    )

    // A record says which account it went through, and the bank is that account's name — which is
    // what the filter asks this resolver for.
    private val ACCOUNTS = mapOf(1L to "ING", 2L to "BCR", 3L to "ING Bank")
    private val bankOf: (Long?) -> String? = { id -> ACCOUNTS[id] }

    private fun expense(
        id: Long,
        vendor: String? = null,
        bank: String? = null,
        location: String? = null,
        at: Long = 1_000L
    ) = ExpenseWithDetails(
        expense = Expense(
            id = id, title = vendor, totalAmount = 10.0, currencyCode = "RON",
            dateTime = at, vendor = vendor, location = location,
            bankAccountId = ACCOUNTS.entries.firstOrNull { it.value == bank }?.key
        ),
        items = emptyList(),
        category = null
    )

    private val all = listOf(
        expense(1, vendor = "LIDL", bank = "ING", location = "Bucuresti"),
        expense(2, vendor = "LIDL", bank = "BCR", location = "Cluj"),
        expense(3, vendor = "KAUFLAND", bank = "ING", location = "Bucuresti"),
        expense(4, vendor = "KAUFLAND", bank = "BCR", location = null)
    )

    private fun ids(
        bank: String? = null,
        vendor: String? = null,
        location: String? = null
    ) = ExpenseFilter.apply(
        all, null, null, null,
        bank?.let(FilterValue::picked), bankOf,
        vendor?.let(FilterValue::picked), location?.let(FilterValue::picked),
        null, null, null, SortMode.NEWEST
    ).map { it.expense.id }.sorted()

    @Test
    fun `nothing selected keeps everything`() {
        assertEquals(listOf(1L, 2L, 3L, 4L), ids())
    }

    @Test
    fun `a location narrows the list`() {
        assertEquals(listOf(1L, 3L), ids(location = "Bucuresti"))
        assertEquals(listOf(2L), ids(location = "Cluj"))
    }

    /** A record with no location is not in any location, rather than being in all of them. */
    @Test
    fun `a record without a location is excluded when one is chosen`() {
        assertEquals(listOf(1L, 3L), ids(location = "Bucuresti"))
        assertEquals("id 4 has none", listOf<Long>(), ids(location = "Nowhere"))
    }

    @Test
    fun `location composes with the other two rather than replacing them`() {
        assertEquals(listOf(1L), ids(vendor = "LIDL", location = "Bucuresti"))
        assertEquals(listOf(1L, 3L), ids(bank = "ING", location = "Bucuresti"))
        assertEquals(listOf(1L), ids(bank = "ING", vendor = "LIDL", location = "Bucuresti"))
        assertEquals("all three must hold", listOf<Long>(), ids(bank = "BCR", vendor = "LIDL", location = "Bucuresti"))
    }

    @Test
    fun `the other filters still work on their own`() {
        assertEquals(listOf(1L, 2L), ids(vendor = "LIDL"))
        assertEquals(listOf(1L, 3L), ids(bank = "ING"))
    }

    // --- a filter is either a row that was picked, or words that were typed ---

    /**
     * Picking a row means that row. Under containment it would also mean every longer name the
     * picked one is part of — shops nobody chose, and an exclusion nobody can see they made.
     */
    @Test
    fun `a picked row does not drag in the longer names it is part of`() {
        val shops = listOf(
            expense(1, vendor = "LIDL"),
            expense(2, vendor = "LIDL EXPRESS"),
            expense(3, vendor = "KAUFLAND")
        )
        val picked = ExpenseFilter.apply(
            shops, null, null, null, null, bankOf, FilterValue.picked("LIDL"), null, null, null, null, SortMode.NEWEST
        )
        assertEquals(listOf(1L), picked.map { it.expense.id })
    }

    /** Words nobody could have picked are applied to everything they find. */
    @Test
    fun `a typed query matches every name containing it`() {
        val shops = listOf(
            expense(1, vendor = "LIDL RO-490"),
            expense(2, vendor = "LIDL RO-217"),
            expense(3, vendor = "KAUFLAND")
        )
        val queried = ExpenseFilter.apply(
            shops, null, null, null, null, bankOf, FilterValue.typed("LIDL"), null, null, null, null, SortMode.NEWEST
        )
        assertEquals(listOf(1L, 2L), queried.map { it.expense.id }.sorted())
    }

    @Test
    fun `a query is case-insensitive`() {
        val shops = listOf(expense(1, vendor = "LIDL RO-490"))
        assertEquals(1, ExpenseFilter.apply(shops, null, null, null, null, bankOf, FilterValue.typed("lidl"), null, null, null, null, SortMode.NEWEST).size)
    }

    @Test
    fun `a record with nothing in that field never matches a query`() {
        val shops = listOf(expense(1, vendor = null), expense(2, vendor = "LIDL"))
        assertEquals(listOf(2L), ExpenseFilter.apply(shops, null, null, null, null, bankOf, FilterValue.typed("LIDL"), null, null, null, null, SortMode.NEWEST).map { it.expense.id })
    }

    @Test
    fun `queries work on the other two fields too`() {
        val all = listOf(
            expense(1, bank = "ING Bank", location = "Bucuresti Sector 3"),
            expense(2, bank = "BCR", location = "Cluj")
        )
        assertEquals(listOf(1L), ExpenseFilter.apply(all, null, null, null, FilterValue.typed("ING"), bankOf, null, null, null, null, null, SortMode.NEWEST).map { it.expense.id })
        assertEquals(listOf(1L), ExpenseFilter.apply(all, null, null, null, null, bankOf, null, FilterValue.typed("Sector"), null, null, null, SortMode.NEWEST).map { it.expense.id })
    }

    // --- narrowing by what a record cost ---

    private val priced = listOf(priced(1, 5.0), priced(2, 50.0), priced(3, 100.0), priced(4, 500.0))

    private fun inRange(range: VoxRange?) =
        ExpenseFilter.apply(priced, null, null, null, null, bankOf, null, null, range, null, null, SortMode.NEWEST)
            .map { it.expense.id }.sorted()

    @Test
    fun `a bracket keeps what falls inside it`() {
        assertEquals(listOf(2L, 3L), inRange(VoxRange(50.0, 100.0)))
        assertEquals(listOf(1L), inRange(VoxRange(0.0, 10.0)))
    }

    /** Both ends belong to the bracket, so a record sitting exactly on one is never lost. */
    @Test
    fun `the boundaries are inside`() {
        assertEquals(listOf(2L), inRange(VoxRange(50.0, 50.0)))
        assertEquals(listOf(3L, 4L), inRange(VoxRange(100.0, 500.0)))
    }

    @Test
    fun `no bracket keeps everything`() {
        assertEquals(listOf(1L, 2L, 3L, 4L), inRange(null))
    }

    @Test
    fun `a bracket nothing falls in keeps nothing`() {
        assertEquals(listOf<Long>(), inRange(VoxRange(1_000.0, 2_000.0)))
    }

    @Test
    fun `a bracket composes with the other filters rather than replacing them`() {
        val mixed = listOf(
            ExpenseWithDetails(
                expense = Expense(id = 1, totalAmount = 60.0, currencyCode = "RON", dateTime = 1L, vendor = "LIDL"),
                items = emptyList(), category = null
            ),
            ExpenseWithDetails(
                expense = Expense(id = 2, totalAmount = 60.0, currencyCode = "RON", dateTime = 1L, vendor = "KAUFLAND"),
                items = emptyList(), category = null
            )
        )
        val out = ExpenseFilter.apply(
            mixed, null, null, null, null, bankOf, FilterValue.picked("LIDL"), null, VoxRange(50.0, 100.0), null, null, SortMode.NEWEST
        )
        assertEquals(listOf(1L), out.map { it.expense.id })
    }

    // --- narrowing by the card or account money went through ---

    private fun onAccount(id: Long?, accountId: Long? = null, currency: String? = null) = ExpenseWithDetails(
        expense = Expense(
            id = id ?: 0, totalAmount = 10.0, currencyCode = currency ?: "RON",
            dateTime = 1L, bankAccountId = accountId
        ),
        items = emptyList(), category = null
    )

    private val banked = listOf(
        onAccount(1, accountId = 10),   // the account itself
        onAccount(2, accountId = 11),   // a card under it
        onAccount(3, accountId = 12),   // a card under a different account
        onAccount(4, accountId = null)  // nothing said which account
    )

    private fun through(ids: Set<Long>?) =
        ExpenseFilter.apply(banked, null, null, null, null, bankOf, null, null, null, ids, null, SortMode.NEWEST)
            .map { it.expense.id }.sorted()

    /** Spending on a card is spending from the account, so the family is what an account means. */
    @Test
    fun `an account reaches the records of its cards`() {
        assertEquals(listOf(1L, 2L), through(setOf(10L, 11L)))
    }

    @Test
    fun `a card reaches only its own records`() {
        assertEquals(listOf(2L), through(setOf(11L)))
    }

    /** A record nothing named an account for is not in any account. */
    @Test
    fun `a record with no account is excluded when one is chosen`() {
        assertFalse(through(setOf(10L, 11L)).contains(4L))
        assertEquals(listOf(1L, 2L, 3L, 4L), through(null))
    }

    // --- narrowing by currency ---

    private fun inCurrency(code: String?) = ExpenseFilter.apply(
        listOf(onAccount(1, currency = "RON"), onAccount(2, currency = "EUR"), onAccount(3, currency = "ron")),
        null, null, null, null, bankOf, null, null, null, null, code, SortMode.NEWEST
    ).map { it.expense.id }.sorted()

    @Test
    fun `a currency keeps what is filed in it`() {
        assertEquals(listOf(2L), inCurrency("EUR"))
    }

    /** Codes are compared as codes, not as spellings. */
    @Test
    fun `currency matching ignores case`() {
        assertEquals(listOf(1L, 3L), inCurrency("RON"))
        assertEquals(listOf(1L, 3L), inCurrency("ron"))
    }

    @Test
    fun `no currency keeps everything`() {
        assertEquals(listOf(1L, 2L, 3L), inCurrency(null))
    }
}
