package com.voxapps.expenses.state

import com.voxapps.design.filter.VoxRange

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which narrowings the button reports, and in what order. */
class ExpenseFilterSummaryTest {

    private val date: (Long) -> String = { "d$it" }
    private val money: (Double) -> String = { "m${it.toLong()}" }
    private val sortName: (SortMode) -> String = { it.name }

    private fun parts(
        category: String? = null,
        bank: FilterValue? = null,
        vendor: FilterValue? = null,
        location: FilterValue? = null,
        amount: VoxRange? = null,
        account: String? = null,
        currency: String? = null,
        from: Long? = null,
        to: Long? = null,
        sort: SortMode = SortMode.NEWEST
    ) = ExpenseFilterSummary.parts(category, bank, vendor, location, amount, account, currency, from, to, sort, date, money, sortName)
        .filterNotNull()

    @Test
    fun `an unnarrowed list reports nothing`() {
        assertEquals(emptyList<String>(), parts())
    }

    @Test
    fun `the order is category, names, dates, sort`() {
        assertEquals(
            listOf("Groceries", "ING", "LIDL", "Cluj", "m50 – m100", "••4535", "EUR", "d1 – d2", "AMOUNT_DESC"),
            parts(
                category = "Groceries",
                bank = FilterValue.picked("ING"),
                vendor = FilterValue.picked("LIDL"),
                location = FilterValue.picked("Cluj"),
                amount = VoxRange(50.0, 100.0),
                account = "••4535",
                currency = "EUR",
                from = 1L, to = 2L,
                sort = SortMode.AMOUNT_DESC
            )
        )
    }

    /** Newest-first is the resting state, not a choice, so naming it would say nothing. */
    @Test
    fun `the default sort is not reported`() {
        assertEquals(emptyList<String>(), parts(sort = SortMode.NEWEST))
        assertEquals(listOf("OLDEST"), parts(sort = SortMode.OLDEST))
    }

    @Test
    fun `a typed query reports the same as a picked row`() {
        assertEquals(listOf("LID"), parts(vendor = FilterValue.typed("LID")))
    }

    // --- dates ---

    @Test
    fun `both ends read as a range`() {
        assertEquals("d10 – d20", ExpenseFilterSummary.dateLabel(10L, 20L, date))
    }

    /** One day chosen twice is a day, not a range from itself to itself. */
    @Test
    fun `a single day reads as that day`() {
        assertEquals("d10", ExpenseFilterSummary.dateLabel(10L, 10L, date))
    }

    /** An open end excludes as much as a range does, so it has to say so. */
    @Test
    fun `an open end still reports`() {
        assertEquals("d10 – ", ExpenseFilterSummary.dateLabel(10L, null, date))
        assertEquals(" – d20", ExpenseFilterSummary.dateLabel(null, 20L, date))
    }

    @Test
    fun `no dates reads as nothing`() {
        assertNull(ExpenseFilterSummary.dateLabel(null, null, date))
    }

    // --- whether a clear is worth offering ---

    @Test
    fun `nothing active means nothing to clear`() {
        assertFalse(ExpenseFilterSummary.anyActive(null, null, null, null, null, null, null, null, null, SortMode.NEWEST))
    }

    @Test
    fun `any one of them counts`() {
        assertTrue(ExpenseFilterSummary.anyActive(1L, null, null, null, null, null, null, null, null, SortMode.NEWEST))
        assertTrue(ExpenseFilterSummary.anyActive(null, FilterValue.picked("ING"), null, null, null, null, null, null, null, SortMode.NEWEST))
        assertTrue(ExpenseFilterSummary.anyActive(null, null, FilterValue.picked("LIDL"), null, null, null, null, null, null, SortMode.NEWEST))
        assertTrue(ExpenseFilterSummary.anyActive(null, null, null, FilterValue.picked("Cluj"), null, null, null, null, null, SortMode.NEWEST))
        assertTrue(ExpenseFilterSummary.anyActive(null, null, null, null, null, null, null, 1L, null, SortMode.NEWEST))
        assertTrue(ExpenseFilterSummary.anyActive(null, null, null, null, null, null, null, null, 2L, SortMode.NEWEST))
        assertTrue(ExpenseFilterSummary.anyActive(null, null, null, null, null, null, null, null, null, SortMode.AMOUNT_ASC))
    }

    @Test
    fun `a bracket is reported, and a missing one is not`() {
        assertEquals(listOf("m50 – m100"), parts(amount = VoxRange(50.0, 100.0)))
        assertEquals(emptyList<String>(), parts(amount = null))
    }

    @Test
    fun `a bracket counts as something to clear`() {
        assertTrue(
            ExpenseFilterSummary.anyActive(null, null, null, null, VoxRange(0.0, 5.0), null, null, null, null, SortMode.NEWEST)
        )
    }

    @Test
    fun `an account and a currency are reported where set`() {
        assertEquals(listOf("••4535"), parts(account = "••4535"))
        assertEquals(listOf("EUR"), parts(currency = "EUR"))
    }

    /** Either counts as something to clear. */
    @Test
    fun `an account or a currency counts`() {
        assertTrue(ExpenseFilterSummary.anyActive(null, null, null, null, null, 1L, null, null, null, SortMode.NEWEST))
        assertTrue(ExpenseFilterSummary.anyActive(null, null, null, null, null, null, "EUR", null, null, SortMode.NEWEST))
    }

    /** The chips that set a sort and the summary that reports it read from the same map. */
    @Test
    fun `every sort has its own name`() {
        for (mode in SortMode.entries) assertTrue(sortKeyOf(mode).startsWith("sort_"))
        assertEquals(SortMode.entries.size, SortMode.entries.map { sortKeyOf(it) }.distinct().size)
    }
}
