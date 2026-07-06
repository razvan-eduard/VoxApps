package com.voxcommander.app.domain.intent.interpreter

import android.util.Log
import com.voxcommander.app.domain.intent.taxonomy.IntentTaxonomy
import com.voxcommander.app.utils.Logger
import com.voxcommander.app.utils.PackageNames
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.mockkStatic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests for [NluIntentParser] — verifies the three LLM JSON schemas (anatomy, legacy-domain,
 * old-legacy) parse into NluIntent, plus markdown/prose extraction and error handling.
 */
class NluIntentParserTest {

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>()) } returns 0
        mockkObject(Logger)
        every { Logger.log(any(), any()) } returns Unit
    }

    // --- Anatomy schema (action_verb present) ---

    @Test
    fun `anatomy schema maps all fields`() {
        val json = """
            {
              "action_verb": "play",
              "logical_subject": "Scorpions",
              "modifiers": ["loud"],
              "context_words": ["on spotify"],
              "domain": "audio",
              "action": "play",
              "targetApp": "spotify",
              "category": null,
              "confidence": 0.9,
              "extras": {"foo": "bar"}
            }
        """.trimIndent()

        val intent = NluIntentParser.parse(json)

        assertNotNull(intent)
        assertEquals("play", intent!!.actionVerb)
        assertEquals("Scorpions", intent.logicalSubject)
        assertEquals(listOf("loud"), intent.modifiers)
        assertEquals(listOf("on spotify"), intent.contextWords)
        assertEquals(IntentTaxonomy.Domains.AUDIO, intent.domain)
        assertEquals(IntentTaxonomy.Actions.PLAY, intent.action)
        assertEquals("spotify", intent.targetApp)
        assertNull(intent.category)
        assertEquals(0.9f, intent.confidence, 0.0001f)
        assertEquals(mapOf("foo" to "bar"), intent.extras)
    }

    @Test
    fun `anatomy schema blank logical_subject becomes null`() {
        val json = """{"action_verb":"pause","logical_subject":"","domain":"audio","action":"pause"}"""
        val intent = NluIntentParser.parse(json)
        assertNotNull(intent)
        assertNull(intent!!.logicalSubject)
    }

    @Test
    fun `anatomy schema defaults confidence to 1_0 when absent`() {
        val json = """{"action_verb":"play","domain":"audio","action":"play"}"""
        val intent = NluIntentParser.parse(json)
        assertNotNull(intent)
        assertEquals(1.0f, intent!!.confidence, 0.0001f)
    }

    @Test
    fun `anatomy schema targetApp null when absent`() {
        val json = """{"action_verb":"play","domain":"audio","action":"play"}"""
        val intent = NluIntentParser.parse(json)
        assertNull(intent!!.targetApp)
    }

    @Test
    fun `anatomy schema accepts modifiers as a single string`() {
        // getSafeStringList must tolerate a scalar where an array is expected.
        val json = """{"action_verb":"play","domain":"audio","action":"play","modifiers":"loud"}"""
        val intent = NluIntentParser.parse(json)
        assertNotNull(intent)
        assertEquals(listOf("loud"), intent!!.modifiers)
    }

    @Test
    fun `anatomy schema with blank domain and action returns null`() {
        val json = """{"action_verb":"","domain":"","action":""}"""
        assertNull(NluIntentParser.parse(json))
    }

    // --- Domain/action synonym normalization ---

    @Test
    fun `domain synonyms are normalized`() {
        assertEquals(IntentTaxonomy.Domains.AUDIO,
            NluIntentParser.parse("""{"action_verb":"x","domain":"music","action":"play"}""")!!.domain)
        assertEquals(IntentTaxonomy.Domains.MAPS,
            NluIntentParser.parse("""{"action_verb":"x","domain":"navigation","action":"navigate"}""")!!.domain)
    }

    @Test
    fun `action synonyms are normalized`() {
        assertEquals(IntentTaxonomy.Actions.NEXT,
            NluIntentParser.parse("""{"action_verb":"x","domain":"audio","action":"skip"}""")!!.action)
        assertEquals(IntentTaxonomy.Actions.VOLUME_UP,
            NluIntentParser.parse("""{"action_verb":"x","domain":"settings","action":"louder"}""")!!.action)
    }

    @Test
    fun `normalization is case and whitespace insensitive`() {
        val intent = NluIntentParser.parse("""{"action_verb":"x","domain":"  MUSIC ","action":" SKIP "}""")
        assertEquals(IntentTaxonomy.Domains.AUDIO, intent!!.domain)
        assertEquals(IntentTaxonomy.Actions.NEXT, intent.action)
    }

    // --- Legacy domain schema (domain present, no action_verb) ---

    @Test
    fun `legacy domain schema maps parameters to anatomy fields`() {
        val json = """
            {
              "domain": "audio",
              "action": "play",
              "targetApp": "spotify",
              "parameters": {
                "query": "Bohemian Rhapsody",
                "category": "music",
                "mediaControlType": "active_session",
                "leftover": "keep"
              }
            }
        """.trimIndent()

        val intent = NluIntentParser.parse(json)

        assertNotNull(intent)
        assertEquals("Bohemian Rhapsody", intent!!.logicalSubject)
        assertEquals("music", intent.category)
        assertEquals("active_session", intent.mediaControlType)
        assertEquals("spotify", intent.targetApp)
        // Recognized params are stripped; only leftovers remain in extras.
        assertEquals(mapOf("leftover" to "keep"), intent.extras)
    }

    @Test
    fun `legacy domain schema falls back through subject aliases`() {
        val json = """{"domain":"maps","action":"navigate","parameters":{"destination":"Cluj"}}"""
        val intent = NluIntentParser.parse(json)
        assertEquals("Cluj", intent!!.logicalSubject)
    }

    // --- Old legacy schema (category present) ---

    @Test
    fun `old legacy schema maps actionType via LegacyMapper`() {
        val json = """{"category":"audio","actionType":"audio_spotify","artist":"Queen"}"""
        val intent = NluIntentParser.parse(json)
        assertNotNull(intent)
        assertEquals(IntentTaxonomy.Domains.AUDIO, intent!!.domain)
        assertEquals(IntentTaxonomy.Actions.PLAY, intent.action)
        assertEquals(PackageNames.SPOTIFY, intent.targetApp)
        assertEquals("Queen", intent.logicalSubject)
    }

    // --- extractJsonBlock ---

    @Test
    fun `parses json wrapped in markdown fences`() {
        val json = "```json\n{\"action_verb\":\"play\",\"domain\":\"audio\",\"action\":\"play\"}\n```"
        val intent = NluIntentParser.parse(json)
        assertNotNull(intent)
        assertEquals(IntentTaxonomy.Domains.AUDIO, intent!!.domain)
    }

    @Test
    fun `extracts first json object from prose with multiple blocks`() {
        val raw = "Sure! Here you go: {\"action_verb\":\"pause\",\"domain\":\"audio\",\"action\":\"pause\"} and also {\"ignored\":true}"
        val intent = NluIntentParser.parse(raw)
        assertNotNull(intent)
        assertEquals(IntentTaxonomy.Actions.PAUSE, intent!!.action)
    }

    // --- Error handling ---

    @Test
    fun `malformed json returns null`() {
        assertNull(NluIntentParser.parse("{not valid json"))
    }

    @Test
    fun `unknown schema returns null`() {
        assertNull(NluIntentParser.parse("""{"something":"else"}"""))
    }

    @Test
    fun `empty input returns null`() {
        assertNull(NluIntentParser.parse(""))
    }
}
