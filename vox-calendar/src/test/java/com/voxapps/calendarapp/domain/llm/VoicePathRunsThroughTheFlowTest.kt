package com.voxapps.calendarapp.domain.llm

import com.voxapps.calendarapp.data.preferences.CalendarSettings
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

    /**
     * One rung, declared as one rung: an entry needs a moment, spoken time is language, and the
     * deterministic reader this app uses for pages settles digits and declines everything else.
     */
    @Test
    fun `voice offers the fullest rung and nothing narrower`() {
        val support = CalendarSettings.VOICE_FLOW_SUPPORT
        assertEquals(RecordSource.VOICE, support.source)
        assertEquals(setOf(support.default), support.supported)
        assertFalse("a rung that asks nothing cannot make an entry from speech", support.default.staysOnDevice)
    }
}
