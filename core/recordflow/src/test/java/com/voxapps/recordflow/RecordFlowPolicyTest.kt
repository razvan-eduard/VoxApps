package com.voxapps.recordflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The switch, stated as a table.
 *
 * Worth writing out in full rather than testing the interesting cases: this rule existed three times
 * in three shapes before it existed once, and every disagreement between those shapes was a case
 * nobody had written down. A table is the artefact that makes the next disagreement visible.
 */
class RecordFlowPolicyTest {

    private fun reading(usable: Boolean, complete: Boolean) =
        DeterministicReading(fields = Unit, usable = usable, complete = complete)

    private val nothing = reading(usable = false, complete = false)
    private val partial = reading(usable = true, complete = false)
    private val proved = reading(usable = true, complete = true)

    @Test
    fun `nothing is sent at the offline level`() {
        assertEquals(Decision.DISCARD, RecordFlowPolicy.decide(LlmLevel.NONE, nothing))
        assertEquals(Decision.QUEUE_FOR_REVIEW, RecordFlowPolicy.decide(LlmLevel.NONE, partial))
        assertEquals(Decision.COMMIT, RecordFlowPolicy.decide(LlmLevel.NONE, proved))
    }

    @Test
    fun `asking only what is missing skips the request when nothing is`() {
        assertEquals(Decision.ASK_MODEL, RecordFlowPolicy.decide(LlmLevel.ASSIST_SUGGEST, nothing))
        assertEquals(Decision.ASK_MODEL, RecordFlowPolicy.decide(LlmLevel.ASSIST_SUGGEST, partial))
        // Proved everything, so there is nothing to ask — the request is not made at all.
        assertEquals(Decision.COMMIT, RecordFlowPolicy.decide(LlmLevel.ASSIST_SUGGEST, proved))
    }

    @Test
    fun `the widest scope always asks`() {
        assertEquals(Decision.ASK_MODEL, RecordFlowPolicy.decide(LlmLevel.FULL, nothing))
        assertEquals(Decision.ASK_MODEL, RecordFlowPolicy.decide(LlmLevel.FULL, partial))
        assertEquals(Decision.ASK_MODEL, RecordFlowPolicy.decide(LlmLevel.FULL, proved))
    }

    @Test
    fun `every scope wider than what-is-missing asks regardless of the reading`() {
        LlmLevel.entries.filter { it.asks == AskScope.ALL_HEAD || it.asks == AskScope.EVERYTHING }
            .forEach { level ->
                assertEquals(Decision.ASK_MODEL, RecordFlowPolicy.decide(level, nothing))
                assertEquals(Decision.ASK_MODEL, RecordFlowPolicy.decide(level, partial))
                assertEquals(Decision.ASK_MODEL, RecordFlowPolicy.decide(level, proved))
            }
    }

    /** The ladder, in the order a settings screen shows it: by how much is sent, and within that by
     *  how much lands unread. */
    @Test
    fun `the levels are ordered by how much is sent and how much lands unread`() {
        assertEquals(
            listOf(
                LlmLevel.NONE,
                LlmLevel.ASSIST_SUGGEST,
                LlmLevel.ASSIST_AUTO,
                LlmLevel.HEAD_SUGGEST,
                LlmLevel.HEAD_AUTO,
                LlmLevel.ALL_SUGGEST,
                LlmLevel.BODY_SUGGEST,
                LlmLevel.FULL
            ),
            LlmLevel.entries.toList()
        )
        // Never narrows: each rung sends at least as much as the one before it.
        LlmLevel.entries.zipWithNext { a, b ->
            assertTrue("$b sends less than $a", b.asks.ordinal >= a.asks.ordinal)
        }
    }

    /**
     * Every coherent pair of "how much is asked" and "what is written" has a rung, and every rung is
     * a coherent pair. Writing the fine detail while leaving the coarse fields to be approved is the
     * incoherent one — it automates the half nobody can check and defers the half anyone can — and
     * writing an answer to a question never asked is the other.
     */
    @Test
    fun `the ladder covers every coherent combination and nothing else`() {
        val coherent = mutableListOf<Triple<AskScope, Boolean, Boolean>>()
        for (scope in AskScope.entries) {
            for (head in listOf(false, true)) {
                for (body in listOf(false, true)) {
                    if (body && !head) continue
                    if (head && !scope.covers(FieldWeight.HEAD)) continue
                    if (body && !scope.covers(FieldWeight.BODY)) continue
                    coherent += Triple(scope, head, body)
                }
            }
        }
        val onTheLadder = LlmLevel.entries.map {
            Triple(it.asks, it.applies(FieldWeight.HEAD), it.applies(FieldWeight.BODY))
        }
        assertEquals(coherent.toSet(), onTheLadder.toSet())
        assertEquals("no rung is listed twice", onTheLadder.size, onTheLadder.toSet().size)
    }

    /** The promise only the coldest rung makes, and it is about the device rather than the record. */
    @Test
    fun `only none keeps everything on the device`() {
        assertEquals(true, LlmLevel.NONE.staysOnDevice)
        LlmLevel.entries.filter { it != LlmLevel.NONE }.forEach {
            assertEquals("$it sends something", false, it.staysOnDevice)
        }
    }

    /** Who writes what, per weight — the table the partly-automatic rungs exist for. */
    @Test
    fun `what a level writes depends on the weight of the field`() {
        listOf(LlmLevel.NONE, LlmLevel.ASSIST_SUGGEST, LlmLevel.HEAD_SUGGEST, LlmLevel.ALL_SUGGEST)
            .forEach {
                assertEquals("$it wrote a head field", false, it.applies(FieldWeight.HEAD))
                assertEquals("$it wrote a body field", false, it.applies(FieldWeight.BODY))
            }
        listOf(LlmLevel.ASSIST_AUTO, LlmLevel.HEAD_AUTO, LlmLevel.BODY_SUGGEST).forEach {
            assertEquals("$it did not fill the coarse fields", true, it.applies(FieldWeight.HEAD))
            assertEquals("$it wrote the fine detail", false, it.applies(FieldWeight.BODY))
        }
        assertEquals(true, LlmLevel.FULL.applies(FieldWeight.HEAD))
        assertEquals(true, LlmLevel.FULL.applies(FieldWeight.BODY))
    }

    /**
     * A surface for proposals is needed by exactly the rungs that leave something unwritten — which
     * is not "everything between the ends": a rung that asks only about the coarse fields and then
     * fills them in has nothing left over, however narrow it is.
     */
    @Test
    fun `a level that offers anything needs somewhere to offer it`() {
        val offering = LlmLevel.entries.filter { it.offersAnything }.toSet()
        assertEquals(
            setOf(
                LlmLevel.ASSIST_SUGGEST,
                LlmLevel.HEAD_SUGGEST,
                LlmLevel.ALL_SUGGEST,
                LlmLevel.BODY_SUGGEST
            ),
            offering
        )
        assertThrows(IllegalArgumentException::class.java) {
            FlowSupport(
                RecordSource.SCAN,
                supported = setOf(LlmLevel.BODY_SUGGEST, LlmLevel.FULL),
                default = LlmLevel.FULL
            )
        }
        // A satellite with nowhere to show anything can still offer every rung that writes what it
        // asked for — the two ends, and the two that ask narrowly and fill in.
        FlowSupport(
            RecordSource.VOICE,
            supported = setOf(LlmLevel.NONE, LlmLevel.ASSIST_AUTO, LlmLevel.HEAD_AUTO, LlmLevel.FULL),
            default = LlmLevel.FULL
        )
    }

    /** A channel nobody triggered on purpose can refuse to write anything unreviewed, and that
     *  refusal outranks having proved every field. */
    @Test
    fun `review can be required even for a proved reading`() {
        assertEquals(
            Decision.QUEUE_FOR_REVIEW,
            RecordFlowPolicy.decide(LlmLevel.NONE, proved, autoAcceptWhenProven = false)
        )
        assertEquals(
            Decision.QUEUE_FOR_REVIEW,
            RecordFlowPolicy.decide(LlmLevel.ASSIST_AUTO, proved, autoAcceptWhenProven = false)
        )
    }

    /** Only the offline level discards; anywhere else an unreadable input is still worth asking
     *  about, which is what "the model reads it instead" means. */
    @Test
    fun `an unusable reading is discarded only offline`() {
        assertEquals(Decision.DISCARD, RecordFlowPolicy.decide(LlmLevel.NONE, nothing))
        assertEquals(Decision.ASK_MODEL, RecordFlowPolicy.decide(LlmLevel.ASSIST_SUGGEST, nothing))
        assertEquals(Decision.ASK_MODEL, RecordFlowPolicy.decide(LlmLevel.FULL, nothing))
    }

    @Test
    fun `a reading cannot be complete without being usable`() {
        assertThrows(IllegalArgumentException::class.java) {
            DeterministicReading(fields = Unit, usable = false, complete = true)
        }
    }

    @Test
    fun `a flow cannot default to a level it does not support`() {
        assertThrows(IllegalArgumentException::class.java) {
            FlowSupport(RecordSource.VOICE, supported = setOf(LlmLevel.FULL), default = LlmLevel.NONE)
        }
        assertThrows(IllegalArgumentException::class.java) {
            FlowSupport(RecordSource.SCAN, supported = emptySet(), default = LlmLevel.FULL)
        }
    }

    /**
     * A record with no fine detail has nothing to distinguish the two widest rungs, so offering both
     * would present the same behaviour twice under different names.
     */
    @Test
    fun `a record with no fine detail cannot offer the rung that defers it`() {
        assertThrows(IllegalArgumentException::class.java) {
            FlowSupport(
                RecordSource.SCAN,
                supported = setOf(LlmLevel.NONE, LlmLevel.BODY_SUGGEST),
                default = LlmLevel.NONE,
                suggestsAnswers = true,
                weights = setOf(FieldWeight.HEAD)
            )
        }
        // Head-only is otherwise ordinary: the ends, and anything that writes what it asked for.
        FlowSupport(
            RecordSource.SCAN,
            supported = setOf(LlmLevel.NONE, LlmLevel.FULL),
            default = LlmLevel.FULL,
            weights = setOf(FieldWeight.HEAD)
        )
    }

    /** A flow that never asks has no answer to offer, so the pair is a contradiction. */
    @Test
    fun `a flow cannot suggest answers it never asks for`() {
        assertThrows(IllegalArgumentException::class.java) {
            FlowSupport(
                RecordSource.NOTIFICATION,
                supported = setOf(LlmLevel.NONE),
                default = LlmLevel.NONE,
                suggestsAnswers = true
            )
        }
    }

    /** What a settings screen asks before offering the promise. */
    @Test
    fun `a flow says whether it can run with nothing leaving the device`() {
        val calendarVoice = FlowSupport(
            RecordSource.VOICE,
            supported = setOf(LlmLevel.FULL),
            default = LlmLevel.FULL
        )
        val expensesScan = FlowSupport(
            RecordSource.SCAN,
            supported = setOf(LlmLevel.NONE, LlmLevel.ASSIST_SUGGEST, LlmLevel.FULL),
            default = LlmLevel.FULL,
            suggestsAnswers = true
        )
        assertEquals(false, calendarVoice.canRunOffline)
        assertEquals(true, expensesScan.canRunOffline)
    }
}
