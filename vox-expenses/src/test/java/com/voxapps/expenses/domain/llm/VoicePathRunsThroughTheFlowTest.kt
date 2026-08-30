package com.voxapps.expenses.domain.llm

import com.voxapps.expenses.data.preferences.ExpensesSettings
import com.voxapps.recordflow.Decision
import com.voxapps.recordflow.DeterministicReading
import com.voxapps.recordflow.LlmLevel
import com.voxapps.recordflow.RecordFlowPolicy
import com.voxapps.recordflow.RecordSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A spoken expense takes the same two steps as a scanned or notified one: the flow decides what to
 * ask, and the flow decides what an answer becomes.
 *
 * Checked against the source rather than by running the receivers, which need a live broadcast: what
 * matters is *which* call composes the question and which one writes the record, and that is a
 * property of the code. The scan and notification paths are guarded the same way, for the same
 * reason — see [ScanPathsHonourTheSettingTest] and [NotificationPathHonoursTheSettingTest].
 */
class VoicePathRunsThroughTheFlowTest {

    /** A receiver's statements only — imports and comments dropped, since the names looked for below
     *  appear in both and reading those would pass on any arrangement, including a wrong one. */
    private fun source(name: String): String =
        listOf(
            "src/main/java/com/voxapps/expenses/receiver/$name.kt",
            "vox-expenses/src/main/java/com/voxapps/expenses/receiver/$name.kt"
        ).map(::File).first { it.exists() }.readText()
            .lineSequence()
            .filterNot { line ->
                val t = line.trim()
                t.startsWith("import ") || t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
            }
            .joinToString("\n")

    @Test
    fun `the command receiver asks nothing of its own`() {
        val text = source("VoxCommandReceiver")
        assertTrue(
            "a spoken utterance has to enter through the shared flow",
            text.contains("RecordFlow.dispatch(")
        )
        assertFalse(
            "a prompt composed here would be a second declaration of the same question",
            text.contains("ExpenseParsePromptBuilder.")
        )
    }

    /** What travels can only be what the flow handed over. */
    @Test
    fun `sending happens only inside the flow's own send step`() {
        val text = source("VoxCommandReceiver")
        val dispatch = text.indexOf("RecordFlow.dispatch(")
        val send = text.indexOf("ExpenseParseRequestSender.send(")
        assertTrue("nothing is sent before the flow has decided", dispatch in 0 until send)
    }

    /** And the record is written by the flow rather than beside it. */
    @Test
    fun `the reply is delivered through the flow`() {
        val text = source("LlmResultReceiver")
        assertTrue(
            "a voice reply becomes a record through the flow",
            text.contains("spec = ExpenseVoiceFlow(")
        )
    }

    /** The flow's statements only, same filter as [source] — the invariants below are properties
     *  of the code rather than of a run. */
    private fun flowSource(): String =
        listOf(
            "src/main/java/com/voxapps/expenses/domain/llm/ExpenseVoiceFlow.kt",
            "vox-expenses/src/main/java/com/voxapps/expenses/domain/llm/ExpenseVoiceFlow.kt"
        ).map(::File).first { it.exists() }.readText()
            .lineSequence()
            .filterNot { line ->
                val t = line.trim()
                t.startsWith("import ") || t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
            }
            .joinToString("\n")

    /**
     * Two rungs, and the offline one only queues.
     *
     * The sentence survives the round trip, but no rule may prove it: the one field a rule could
     * settle is the amount, and a single currency-marked figure may be a per-unit price rather than
     * a total. So the offline rung lands the words in review instead of writing a record — the
     * policy's own reading of a usable, unproven sentence — and the fullest rung stays the default
     * an untouched install keeps.
     */
    @Test
    fun `voice offers two rungs and the offline one only queues`() {
        val support = ExpensesSettings.VOICE_FLOW_SUPPORT
        assertEquals(RecordSource.VOICE, support.source)
        assertEquals(setOf(LlmLevel.NONE, LlmLevel.FULL), support.supported)
        assertEquals(LlmLevel.FULL, support.default)
        assertEquals(LlmLevel.FULL, ExpensesSettings.voiceLevelOf(ExpensesSettings().voiceModelUse))
        assertEquals(
            Decision.QUEUE_FOR_REVIEW,
            RecordFlowPolicy.decide(
                LlmLevel.NONE,
                DeterministicReading("said out loud", usable = true, complete = false)
            )
        )
    }

    /** No rule may prove a spoken sentence, so the flow's reading declares itself incomplete. */
    @Test
    fun `the voice reading never proves itself`() {
        assertTrue("read() must establish nothing", flowSource().contains("complete = false"))
    }

    /** A reply that cannot be used still lands somewhere a person will see it. */
    @Test
    fun `a failed reply queues the utterance for review`() {
        assertTrue(
            "the failure branch files the sentence",
            source("LlmResultReceiver").contains("spec.queueForReview(")
        )
        assertTrue(
            "the review landing is a stub the record list surfaces",
            flowSource().contains("isStub = true")
        )
    }

    /** Both halves of the round trip honour the chosen rung, not the declaration's default. */
    @Test
    fun `both receivers read the chosen rung`() {
        val command = source("VoxCommandReceiver")
        val reply = source("LlmResultReceiver")
        assertTrue(command.contains("voiceLevelOf("))
        assertTrue(reply.contains("voiceLevelOf("))
        assertFalse("the setting, not the default", command.contains("VOICE_FLOW_SUPPORT.default"))
        assertFalse("the setting, not the default", reply.contains("VOICE_FLOW_SUPPORT.default"))
    }
}
