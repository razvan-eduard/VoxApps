package com.voxapps.expenses.state

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The main list's narrowing happens in the database, not after it: the query carries every clause
 * SQL can express, the state layer runs only the residual the query cannot say faithfully, and the
 * pickers' vocabularies are column aggregates rather than walks over carried rows.
 *
 * Checked against the source, the way the voice/scan/notification paths are guarded: what matters
 * is *where* each narrowing lives, and that is a property of the code. [ExpenseFilterTest] remains
 * the behavioural spec — [ExpenseFilter.apply] routes through [ExpenseFilter.residual], so the SQL
 * path's Kotlin remainder cannot drift from the tested whole.
 */
class MainListNarrowsInSqlTest {

    private fun source(relative: String): String =
        listOf(relative, "vox-expenses/$relative")
            .map(::File).first { it.exists() }.readText()

    /** Statements only — the names below also appear in comments, which would pass on any layout. */
    private fun statements(relative: String): String =
        source(relative)
            .lineSequence()
            .filterNot { line ->
                val t = line.trim()
                t.startsWith("import ") || t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
            }
            .joinToString("\n")

    private val dao = "src/main/java/com/voxapps/expenses/data/ExpenseDao.kt"
    private val manager = "src/main/java/com/voxapps/expenses/state/ExpensesStateManager.kt"
    private val filter = "src/main/java/com/voxapps/expenses/state/ExpenseFilter.kt"

    @Test
    fun `the query narrows by everything SQL can express`() {
        // The clauses below are unique to the one filtered query, so the whole file may answer.
        val query = source(dao)
        listOf(
            "archivedAt IS NULL",
            ":categoryId IS NULL OR categoryId = :categoryId",
            ":dateFrom IS NULL OR dateTime >= :dateFrom",
            ":dateTo IS NULL OR dateTime <= :dateTo",
            ":amountMin IS NULL OR totalAmount >= :amountMin",
            ":amountMax IS NULL OR totalAmount <= :amountMax",
            ":currency IS NULL OR currencyCode = :currency COLLATE NOCASE",
            ":filterByAccount = 0 OR bankAccountId IN (:accountIds)"
        ).forEach { clause ->
            assertTrue("the query must narrow by: $clause", query.contains(clause))
        }
    }

    @Test
    fun `the query owns the sort, newest-first as rest and tiebreak`() {
        val text = source(dao)
        assertTrue(text.contains("CASE WHEN :sort = 'OLDEST' THEN dateTime END ASC"))
        assertTrue(text.contains("CASE WHEN :sort = 'AMOUNT_ASC' THEN totalAmount END ASC"))
        assertTrue(text.contains("CASE WHEN :sort = 'AMOUNT_DESC' THEN totalAmount END DESC"))
        assertTrue(
            "newest-first must close the ORDER BY as the resting order and tiebreak",
            text.contains("dateTime DESC\n")
        )
    }

    @Test
    fun `the state layer reads the narrowed flow, not the whole ledger`() {
        val text = statements(manager)
        assertTrue("the list must come from the SQL narrowing", text.contains("observeFiltered("))
        assertTrue("filter changes must re-key the query", text.contains("flatMapLatest"))
        assertFalse(
            "the main combine must not carry every row up to filter it here",
            text.contains("expensesRepo.expensesWithDetails")
        )
        assertFalse(
            "the in-memory whole is for list-holding callers, not the screen's own path",
            text.contains("ExpenseFilter.apply(")
        )
        assertTrue("what SQL cannot say runs as the residual", text.contains("ExpenseFilter.residual("))
    }

    @Test
    fun `the vocabularies are aggregates, not walks over carried rows`() {
        val text = statements(manager)
        listOf("currenciesInUse", "amountSpan", "locationsInUse", "vendorsInUse").forEach {
            assertTrue("the pickers must read $it", text.contains(it))
        }
        assertFalse(
            "the amount buckets' ends come from MIN/MAX, not from scanning the rows",
            text.contains("minOfOrNull")
        )
    }

    @Test
    fun `the in-memory form routes through the same residual`() {
        val text = statements(filter)
        assertTrue(
            "apply must delegate the residual predicates so the two paths cannot drift",
            text.contains("residual(it, bank, bankOf, vendor, location)")
        )
    }
}
