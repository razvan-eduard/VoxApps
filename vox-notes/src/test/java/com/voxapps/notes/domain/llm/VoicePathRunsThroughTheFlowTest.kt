package com.voxapps.notes.domain.llm

import com.voxapps.notes.data.preferences.NotesSettings
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
 * A spoken note takes the same two steps as a scanned one: the flow decides what to ask, and the
 * flow decides what an answer becomes.
 *
 * Checked against the source rather than by running the receivers, which need a live broadcast:
 * what matters is *which* call composes the question and which one writes the note, and that is a
 * property of the code. The equivalents in vox-expenses and vox-calendar are guarded the same way.
 */
class VoicePathRunsThroughTheFlowTest {

    /** A receiver's statements only — imports and comments dropped, since the names looked for below
     *  appear in both and reading those would pass on any arrangement, including a wrong one. */
    private fun source(name: String): String =
        listOf(
            "src/main/java/com/voxapps/notes/receiver/$name.kt",
            "vox-notes/src/main/java/com/voxapps/notes/receiver/$name.kt"
        ).map(::File).first { it.exists() }.readText()
            .lineSequence()
            .filterNot { line ->
                val t = line.trim()
                t.startsWith("import ") || t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
            }
            .joinToString("\n")

    /** The flow's statements only, same filter as [source]. */
    private fun flowSource(): String =
        listOf(
            "src/main/java/com/voxapps/notes/domain/llm/NoteVoiceFlow.kt",
            "vox-notes/src/main/java/com/voxapps/notes/domain/llm/NoteVoiceFlow.kt"
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
            text.contains("NoteScanCleanupPromptBuilder.")
        )
    }

    /** What travels can only be what the flow handed over. */
    @Test
    fun `sending happens only inside the flow's own send step`() {
        val text = source("VoxCommandReceiver")
        val dispatch = text.indexOf("RecordFlow.dispatch(")
        val send = text.indexOf("NoteParseRequestSender.send(")
        assertTrue("nothing is sent before the flow has decided", dispatch in 0 until send)
    }

    /** And the note is written by the flow rather than beside it. */
    @Test
    fun `the reply is delivered through the flow`() {
        val text = source("LlmResultReceiver")
        assertTrue(
            "a voice reply becomes a note through the flow",
            text.contains("spec = NoteVoiceFlow(")
        )
    }

    /**
     * Two rungs, the offline one the default: the words are the note, so an untouched install
     * writes them on the device and sends nothing. The full rung is the same cleanup a scan gets,
     * and its failure falls back to the untouched transcript, so nothing spoken is ever lost.
     */
    @Test
    fun `voice stays on the device by default and never loses the words`() {
        val support = NotesSettings.VOICE_FLOW_SUPPORT
        assertEquals(RecordSource.VOICE, support.source)
        assertEquals(setOf(LlmLevel.NONE, LlmLevel.FULL), support.supported)
        assertEquals(LlmLevel.NONE, support.default)
        assertTrue("an untouched install must not start sending", support.default.staysOnDevice)
        assertEquals(LlmLevel.NONE, NotesSettings.voiceLevelOf(NotesSettings().voiceLlmLevel))
        // The transcript is whole on arrival, so the offline rung commits rather than queues.
        assertEquals(
            Decision.COMMIT,
            RecordFlowPolicy.decide(
                LlmLevel.NONE,
                DeterministicReading("the words", usable = true, complete = true)
            )
        )
    }

    /** A reply that cannot be used commits the raw transcript instead of dropping it. */
    @Test
    fun `a failed reply falls back to the untouched transcript`() {
        assertTrue(
            "the failure branch hands the reading back to the flow",
            source("LlmResultReceiver").contains("spec.queueForReview(")
        )
        assertTrue(
            "the fallback is the offline commit itself",
            flowSource().contains("commit(it, null)")
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
