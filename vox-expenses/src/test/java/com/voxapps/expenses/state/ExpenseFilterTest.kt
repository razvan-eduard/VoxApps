package com.voxapps.expenses.state

import com.voxapps.expenses.data.Expense
import com.voxapps.expenses.data.ExpenseWithDetails
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Narrowing the list. Every filter is independent and they compose by conjunction — a list narrowed
 * two ways shows what satisfies both, never what satisfies either.
 */
class ExpenseFilterTest {

    private fun expense(
        id: Long,
        vendor: String? = null,
        bank: String? = null,
        location: String? = null,
        at: Long = 1_000L
    ) = ExpenseWithDetails(
        expense = Expense(
            id = id, title = vendor, totalAmount = 10.0, currencyCode = "RON",
            dateTime = at, vendor = vendor, bank = bank, location = location
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
        bank?.let(FilterValue::picked), vendor?.let(FilterValue::picked), location?.let(FilterValue::picked),
        SortMode.NEWEST
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
            shops, null, null, null, null, FilterValue.picked("LIDL"), null, SortMode.NEWEST
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
            shops, null, null, null, null, FilterValue.typed("LIDL"), null, SortMode.NEWEST
        )
        assertEquals(listOf(1L, 2L), queried.map { it.expense.id }.sorted())
    }

    @Test
    fun `a query is case-insensitive`() {
        val shops = listOf(expense(1, vendor = "LIDL RO-490"))
        assertEquals(1, ExpenseFilter.apply(shops, null, null, null, null, FilterValue.typed("lidl"), null, SortMode.NEWEST).size)
    }

    @Test
    fun `a record with nothing in that field never matches a query`() {
        val shops = listOf(expense(1, vendor = null), expense(2, vendor = "LIDL"))
        assertEquals(listOf(2L), ExpenseFilter.apply(shops, null, null, null, null, FilterValue.typed("LIDL"), null, SortMode.NEWEST).map { it.expense.id })
    }

    @Test
    fun `queries work on the other two fields too`() {
        val all = listOf(
            expense(1, bank = "ING Bank", location = "Bucuresti Sector 3"),
            expense(2, bank = "BCR", location = "Cluj")
        )
        assertEquals(listOf(1L), ExpenseFilter.apply(all, null, null, null, FilterValue.typed("ING"), null, null, SortMode.NEWEST).map { it.expense.id })
        assertEquals(listOf(1L), ExpenseFilter.apply(all, null, null, null, null, null, FilterValue.typed("Sector"), SortMode.NEWEST).map { it.expense.id })
    }
}
