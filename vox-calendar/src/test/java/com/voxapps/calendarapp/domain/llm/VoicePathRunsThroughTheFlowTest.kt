package com.voxapps.calendarapp.domain.llm

import com.voxapps.calendarapp.data.preferences.CalendarSettings
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
 * A spoken entry takes the same two steps as a scanned one: the flow decides what to ask, and the
 * flow decides what an answer becomes.
 *
 * Checked against the source rather than by running the receivers, which need a live broadcast: what
 * matters is *which* call composes the question and which one writes the entry, and that is a
 * property of the code.
 */
class VoicePathRunsThroughTheFlowTest {

    /** A receiver's statements only — imports and comments dropped, since the names looked for below
     *  appear in both and reading those would pass on any arrangement, including a wrong one. */
    private fun source(name: String): String =
        listOf(
            "src/main/java/com/voxapps/calendarapp/receiver/$name.kt",
            "vox-calendar/src/main/java/com/voxapps/calendarapp/receiver/$name.kt"
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
            text.contains("CalendarEventParsePromptBuilder.")
        )
    }

    /** What travels can only be what the flow handed over. */
    @Test
    fun `sending happens only inside the flow's own send step`() {
        val text = source("VoxCommandReceiver")
        val dispatch = text.indexOf("RecordFlow.dispatch(")
        val send = text.indexOf("CalendarEventParseRequestSender.send(")
        assertTrue("nothing is sent before the flow has decided", dispatch in 0 until send)
    }

    /** And the entry is written by the flow rather than beside it. */
    @Test
    fun `the reply is delivered through the flow`() {
        val text = source("LlmResultReceiver")
        assertTrue(
            "a voice reply becomes an entry through the flow",
            text.contains("spec = CalendarVoiceFlow(")
        )
    }

    /** The flow's statements only, same filter as [source] — the invariants below are properties
     *  of the code rather than of a run. */
    private fun flowSource(): String =
        listOf(
            "src/main/java/com/voxapps/calendarapp/domain/llm/CalendarVoiceFlow.kt",
            "vox-calendar/src/main/java/com/voxapps/calendarapp/domain/llm/CalendarVoiceFlow.kt"
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
     * An entry needs a moment, spoken time is language, and the deterministic reader this app uses
     * for pages settles digits and declines everything else. So the offline rung lands the words as
     * a dateless to-do in the review list instead of an entry on a guessed day — the policy's own
     * reading of a usable, unproven sentence — and the fullest rung stays the default an untouched
     * install keeps.
     */
    @Test
    fun `voice offers two rungs and the offline one only queues`() {
        val support = CalendarSettings.VOICE_FLOW_SUPPORT
        assertEquals(RecordSource.VOICE, support.source)
        assertEquals(setOf(LlmLevel.NONE, LlmLevel.FULL), support.supported)
        assertEquals(LlmLevel.FULL, support.default)
        assertEquals(LlmLevel.FULL, CalendarSettings.voiceLevelOf(CalendarSettings().voiceLlmLevel))
        assertEquals(
            Decision.QUEUE_FOR_REVIEW,
            RecordFlowPolicy.decide(
                LlmLevel.NONE,
                DeterministicReading("said out loud", usable = true, complete = false)
            )
        )
    }

    /** No rule may prove a spoken date, so the flow's reading declares itself incomplete. */
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
            "the review landing is a to-do item in the review list",
            flowSource().contains("addItem(")
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
