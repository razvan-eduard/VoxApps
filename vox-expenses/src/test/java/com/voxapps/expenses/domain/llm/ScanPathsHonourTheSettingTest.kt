package com.voxapps.expenses.domain.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every path that turns a scan into a request must honour the setting that stops one being sent.
 *
 * This is asserted against the source because that is where the failure lives. The setting was
 * honoured on one sending path and not on the other, the two paths were identical in every other
 * respect, and a fresh scan takes the unguarded one — so with "nothing" plainly selected the text
 * went to the model anyway, on a device, and only a log line gave it away. Nothing about the types
 * involved made that wrong, and no test of behaviour would have caught it without an Android
 * runtime, a database and a broadcast.
 *
 * One path is exempt on purpose: re-reading the items is asked for deliberately, from the expense
 * itself, and a button that quietly did nothing would be worse than one that asks. The exemption is
 * named here so that adding another requires saying so out loud.
 */
class ScanPathsHonourTheSettingTest {

    private val deliberatelyExempt = setOf("sendLineItemsRescan")

    private fun source(): String =
        listOf(
            "src/main/java/com/voxapps/expenses/domain/llm/ExpenseScanCleanupRequestSender.kt",
            "vox-expenses/src/main/java/com/voxapps/expenses/domain/llm/ExpenseScanCleanupRequestSender.kt"
        ).map(::File).first { it.exists() }.readText()

    /** Each `suspend fun` in the sender, with the body up to the next one. */
    private fun paths(): Map<String, String> {
        val text = source()
        val starts = Regex("""suspend fun (\w+)\(""").findAll(text).toList()
        return starts.mapIndexed { index, match ->
            val from = match.range.first
            val to = starts.getOrNull(index + 1)?.range?.first ?: text.length
            match.groupValues[1] to text.substring(from, to)
        }.toMap()
    }

    /**
     * Every capture path hands the page to the shared flow rather than composing a prompt of its
     * own — a stronger guarantee than the one this used to make, which was that a prompt built here
     * had checked the setting beforehand. Now there is no prompt built here to check: the only text
     * that can be sent is what the flow produced, and it produces one only where the rung asked for
     * it.
     */
    @Test
    fun `every capture path goes through the shared flow`() {
        val unguarded = paths()
            .filterKeys { it !in deliberatelyExempt }
            .filterValues { it.contains("ExpenseScanCleanupPromptBuilder.build") }
            .keys

        assertTrue(
            "these compose a prompt themselves, bypassing the level entirely: $unguarded",
            unguarded.isEmpty()
        )
        val dispatching = paths().filterValues { it.contains("RecordFlow.dispatch(") }.keys
        assertTrue("no capture path dispatches through the flow any more", dispatching.size >= 2)
    }

    /** The exemption must stay a single, named path rather than becoming a habit. */
    @Test
    fun `only the deliberate rescan composes its own prompt`() {
        val building = paths().filterValues { it.contains("ExpenseScanCleanupPromptBuilder.build") }.keys
        assertEquals(deliberatelyExempt, building)
    }

    /**
     * The reading is assembled in one place. It existed three times before, the copies drifted, and
     * that drift is what let one path skip the check.
     */
    @Test
    fun `the document is read through one shared function`() {
        val text = source()

        assertEquals(1, Regex("""ScanReading\.of\(""").findAll(text).count())
        // Named rather than private now, because the flow reads the page through the same one — the
        // point was never the visibility, it was that there is exactly one.
        assertTrue(text.contains("suspend fun readScanFor("))
    }

    /** Whatever a path decides, it decides it from the mode rather than from a setting string. */
    @Test
    fun `no path reads the setting behind the orchestrator's back`() {
        val text = source()

        assertTrue(
            "a sending path compares the raw setting instead of asking ScanFlow",
            !text.contains("SCAN_MODEL_")
        )
    }
}
