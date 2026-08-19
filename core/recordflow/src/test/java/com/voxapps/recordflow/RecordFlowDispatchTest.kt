package com.voxapps.recordflow

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The promise the coldest rung makes, enforced where every satellite inherits it.
 *
 * Each app used to keep this guarantee in its own capture path, which meant each app could lose it
 * on its own. Asserted here instead: at a level that stays on the device, [RecordFlow.dispatch] must
 * not so much as ask the satellite for a prompt — not merely refrain from sending one, since a
 * prompt composed and then dropped has already read the input it was built from.
 */
class RecordFlowDispatchTest {

    private class Spy(
        override val support: FlowSupport,
        private val reading: DeterministicReading<String>
    ) : RecordFlowSpec<String, String, String> {
        override val source = RecordSource.SCAN
        override val taskId = "SPY"
        var promptsBuilt = 0
        var committed: String? = null
        var queued = false

        override suspend fun read(input: String) = reading
        override suspend fun prompt(reading: DeterministicReading<String>, asks: AskScope): String {
            promptsBuilt++
            return "ask about ${reading.fields}"
        }
        override suspend fun parse(reply: String) = reply
        override suspend fun commit(
            reading: DeterministicReading<String>?,
            parsed: String?,
            applies: (FieldWeight) -> Boolean
        ): Long {
            committed = parsed ?: reading?.fields
            return 1L
        }
        override suspend fun queueForReview(reading: DeterministicReading<String>?, parsed: String?) {
            queued = true
        }
    }

    private val full = FlowSupport(
        RecordSource.SCAN,
        supported = LlmLevel.entries.toSet(),
        default = LlmLevel.FULL,
        suggestsAnswers = true
    )

    private fun proved() = DeterministicReading("page", usable = true, complete = true)
    private fun partial() = DeterministicReading("page", usable = true, complete = false)

    @Test
    fun `the offline rung neither builds a prompt nor sends one`() = runTest {
        val spy = Spy(full, proved())
        var sent = 0
        val outcome = RecordFlow.dispatch(spy, "page", LlmLevel.NONE) { _, _ -> sent++ }

        assertEquals(0, spy.promptsBuilt)
        assertEquals(0, sent)
        assertEquals(RecordFlow.Outcome.Committed(1L), outcome)
        assertEquals("page", spy.committed)
    }

    /** Unproved and nothing may be asked: it waits for a person rather than being filed or sent. */
    @Test
    fun `an unproved reading offline waits for a person`() = runTest {
        val spy = Spy(full, partial())
        var sent = 0
        val outcome = RecordFlow.dispatch(spy, "page", LlmLevel.NONE) { _, _ -> sent++ }

        assertEquals(0, spy.promptsBuilt)
        assertEquals(0, sent)
        assertTrue(spy.queued)
        assertEquals(RecordFlow.Outcome.Queued, outcome)
    }

    @Test
    fun `the narrow rung asks nothing when the reading proved everything`() = runTest {
        val spy = Spy(full, proved())
        var sent = 0
        RecordFlow.dispatch(spy, "page", LlmLevel.ASSIST_AUTO) { _, _ -> sent++ }

        assertEquals("there was no question to ask", 0, spy.promptsBuilt)
        assertEquals(0, sent)
        assertEquals("page", spy.committed)
    }

    @Test
    fun `a wider rung sends what the satellite composed`() = runTest {
        val spy = Spy(full, partial())
        var sentPrompt: String? = null
        var sentTask: String? = null
        val outcome = RecordFlow.dispatch(spy, "page", LlmLevel.FULL) { task, prompt ->
            sentTask = task
            sentPrompt = prompt
        }

        assertEquals(1, spy.promptsBuilt)
        assertEquals("SPY", sentTask)
        assertEquals("ask about page", sentPrompt)
        assertEquals(RecordFlow.Outcome.Asked, outcome)
    }

    /** A level the satellite withdrew falls back to what it does support, and still keeps whatever
     *  promise that fallback carries. */
    @Test
    fun `a level outside the contract falls back to the declared default`() = runTest {
        val offlineOnly = FlowSupport(
            RecordSource.SCAN,
            supported = setOf(LlmLevel.NONE),
            default = LlmLevel.NONE
        )
        val spy = Spy(offlineOnly, proved())
        var sent = 0
        RecordFlow.dispatch(spy, "page", LlmLevel.FULL) { _, _ -> sent++ }

        assertEquals(0, spy.promptsBuilt)
        assertEquals(0, sent)
        assertEquals("page", spy.committed)
    }
}
