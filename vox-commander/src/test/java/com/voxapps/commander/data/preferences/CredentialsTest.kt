package com.voxapps.commander.data.preferences

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CredentialsTest {

    @Test
    fun `an engine with no entry has no credential`() {
        val credentials = Credentials(mapOf("OPENAI" to "sk-live"))

        assertNull(credentials.forEngine("WHISPER_API"))
        assertFalse(credentials.has("WHISPER_API"))
    }

    /**
     * Clearing a text field leaves an empty string, not an absent value. If that read as a
     * configured credential, the engine would be offered as available and then fail on the call —
     * which is the failure this whole area exists to stop.
     */
    @Test
    fun `a blank entry counts as no credential`() {
        val credentials = Credentials(mapOf("OPENAI" to "   "))

        assertNull(credentials.forEngine("OPENAI"))
        assertFalse(credentials.has("OPENAI"))
    }

    /**
     * The point of keying by engine: two services that used to read one `api_key` are now
     * independently configurable, and one is not the other's default.
     */
    @Test
    fun `engines billed separately hold separate credentials`() {
        val credentials = Credentials(
            mapOf("OPENAI" to "sk-intent", "WHISPER_API" to "sk-transcription")
        )

        assertEquals("sk-intent", credentials.forEngine("OPENAI"))
        assertEquals("sk-transcription", credentials.forEngine("WHISPER_API"))
        assertTrue(credentials.has("OPENAI") && credentials.has("WHISPER_API"))
    }
}
