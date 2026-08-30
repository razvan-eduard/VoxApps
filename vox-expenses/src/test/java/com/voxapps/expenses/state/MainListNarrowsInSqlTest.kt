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
        assertTrue(
            "what SQL cannot say runs as the per-record residual",
            text.contains("ExpenseFilter.residualMatches(")
        )
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
        assertTrue(
            "the list form must reuse the one-record predicate the paging window filters by",
            text.contains("residualMatches(it, bank, bankOf, vendor, location)")
        )
    }

    @Test
    fun `the scrolling list is a paging window over the same narrowing`() {
        val daoText = source(dao)
        assertTrue(
            "the paged query must exist as a paging source",
            daoText.contains("PagingSource<Int, ExpenseWithDetails>")
        )
        val text = statements(manager)
        assertTrue("the pager must be re-keyed like the snapshot", text.contains("Pager("))
        assertTrue("the window must survive recomposition", text.contains(".cachedIn(scope)"))
        assertTrue(
            "both views of one question must pass the same keep-or-drop answer",
            Regex("""keeps\(""").findAll(text).count() >= 3 // the definition and both call sites
        )
    }

    @Test
    fun `the ui state carries no rows`() {
        val text = statements("src/main/java/com/voxapps/expenses/state/ExpensesUiState.kt")
        assertFalse(
            "rows travel through the paged window and the cold snapshot, never the hot state",
            text.contains("ExpenseWithDetails")
        )
    }

    @Test
    fun `the main list's shape has its index`() {
        val entity = source("src/main/java/com/voxapps/expenses/data/Expense.kt")
        assertTrue(
            "the entity must declare the (archivedAt, dateTime) index",
            entity.contains("Index(\"archivedAt\", \"dateTime\")")
        )
        val db = source("src/main/java/com/voxapps/expenses/data/ExpensesDatabase.kt")
        assertTrue("the migration must create it", db.contains("Migration(41, 42)"))
        assertTrue(
            "under the name Room expects, or validation rejects the schema",
            db.contains("index_expenses_archivedAt_dateTime")
        )
    }
}
