package com.voxapps.expenses.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * What the editor compares against to decide it has been touched.
 *
 * The accounts reach the screen through a flow that starts empty, so the two fields derived from
 * the record's pointer are still null on the first composition. A baseline taken from them would be
 * a baseline of null, and the list arriving one frame later would read as an edit — a record nobody
 * touched asking whether to discard, every time it is opened.
 */
class EditorDirtyBaselineTest {

    private val source =
        File("src/main/java/com/voxapps/expenses/ui/ExpenseEditScreen.kt").readText()

    @Test
    fun `the baseline takes the record's own pointer`() {
        val at = source.indexOf("val initialSnapshot = remember {")
        assertTrue("the baseline has to exist", at > 0)
        val block = source.substring(at, minOf(source.length, at + 900))
        assertTrue(
            "it must read the stored pointer, not the state derived from a list that arrives late",
            block.contains("existing?.expense?.bankAccountId")
        )
    }

    /** And the derived fields are still what the screen edits and saves — the baseline is the only
     *  place the stored value is read directly. */
    @Test
    fun `the record still saves what the two fields chose`() {
        assertTrue(source.contains("val bankAccountId = cardId ?: accountId"))
        assertTrue(source.contains("bankAccountId = bankAccountId,"))
    }
}
