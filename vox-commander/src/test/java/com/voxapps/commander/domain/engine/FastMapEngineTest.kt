package com.voxapps.commander.domain.engine

import android.util.Log
import com.voxapps.commander.data.local.dao.FastMapDao
import com.voxapps.commander.domain.intent.interpreter.FastMapEngine
import com.voxapps.commander.domain.intent.taxonomy.IntentTaxonomy
import com.voxapps.commander.testutil.TestDataFactory
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FastMapEngineTest {

    private lateinit var fastMapDao: FastMapDao
    private lateinit var engine: FastMapEngine

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>()) } returns 0

        fastMapDao = mockk()
        engine = FastMapEngine(fastMapDao)
    }

    @Test
    fun `processCommand returns NluIntent when trigger words match`() = runTest {
        val rule = TestDataFactory.createAudioPlayRule(
            triggerWords = listOf("turn", "on", "the"),
            lazyQuery = true
        )
        every { fastMapDao.getAllRules() } returns flowOf(listOf(rule))

        val result = engine.processCommand("turn on the kitchen lights", null)

        assertNotNull(result)
        assertEquals(IntentTaxonomy.Domains.AUDIO, result?.domain)
        assertEquals(IntentTaxonomy.Actions.PLAY, result?.action)
        assertEquals("com.spotify.music", result?.targetApp)
    }

    @Test
    fun `processCommand is case insensitive for trigger matching`() = runTest {
        val rule = TestDataFactory.createFastMapRule(
            triggerWords = listOf("PUNE", "MUZICA"),
            lazyQuery = true,
            domain = IntentTaxonomy.Domains.AUDIO,
            action = IntentTaxonomy.Actions.PLAY
        )
        every { fastMapDao.getAllRules() } returns flowOf(listOf(rule))

        val result = engine.processCommand("pune muzica de la smiley", null)

        assertNotNull(result)
    }

    @Test
    fun `processCommand returns null when no trigger matches`() = runTest {
        val rule = TestDataFactory.createAudioPlayRule(
            triggerWords = listOf("pune", "muzica")
        )
        every { fastMapDao.getAllRules() } returns flowOf(listOf(rule))

        val result = engine.processCommand("play some music", null)

        assertNull(result)
    }

    @Test
    fun `processCommand extracts lazy query from spoken text`() = runTest {
        val rule = TestDataFactory.createAudioPlayRule(
            triggerWords = listOf("pune", "muzica", "de", "la"),
            lazyQuery = true
        )
        every { fastMapDao.getAllRules() } returns flowOf(listOf(rule))

        val result = engine.processCommand("pune muzica de la smiley", null)

        assertNotNull(result)
        val query = result?.logicalSubject
        assertNotNull(query)
        assertTrue(query!!.contains("smiley"))
    }

    @Test
    fun `processCommand maps query to destination for maps domain`() = runTest {
        val rule = TestDataFactory.createNavigationRule(
            triggerWords = listOf("du-ma", "la")
        )
        every { fastMapDao.getAllRules() } returns flowOf(listOf(rule))

        val result = engine.processCommand("du-ma la magazin", null)

        assertNotNull(result)
        assertEquals(IntentTaxonomy.Domains.MAPS, result?.domain)
        assertEquals(IntentTaxonomy.Actions.NAVIGATE, result?.action)
        val destination = result?.logicalSubject
        assertNotNull(destination)
        assertTrue(destination!!.contains("magazin"))
    }

    @Test
    fun `processCommand returns null for empty rules list`() = runTest {
        every { fastMapDao.getAllRules() } returns flowOf(emptyList())

        val result = engine.processCommand("anything", null)

        assertNull(result)
    }

    @Test
    fun `processCommand skips inactive rules`() = runTest {
        val rule = TestDataFactory.createFastMapRule(
            triggerWords = listOf("pune", "muzica"),
            isActive = false
        )
        every { fastMapDao.getAllRules() } returns flowOf(listOf(rule))

        val result = engine.processCommand("pune muzica", null)

        assertNull(result)
    }

    @Test
    fun `processCommand returns null for blank input`() = runTest {
        val rule = TestDataFactory.createAudioPlayRule()
        every { fastMapDao.getAllRules() } returns flowOf(listOf(rule))

        val result = engine.processCommand("", null)

        assertNull(result)
    }

    @Test
    fun `pure transport control with extra words skips to L2`() = runTest {
        val rule = TestDataFactory.createTransportControlRule(
            triggerWords = listOf("pune", "play")
        )
        every { fastMapDao.getAllRules() } returns flowOf(listOf(rule))

        val result = engine.processCommand("pune play smiley", null)

        assertNull(result)
    }

    @Test
    fun `pure transport control with only trigger words matches`() = runTest {
        val rule = TestDataFactory.createTransportControlRule(
            triggerWords = listOf("pune", "play")
        )
        every { fastMapDao.getAllRules() } returns flowOf(listOf(rule))

        val result = engine.processCommand("pune play", null)

        assertNotNull(result)
        assertEquals(IntentTaxonomy.Domains.AUDIO, result?.domain)
    }

    @Test
    fun `query-only rule matches without trigger words`() = runTest {
        val rule = TestDataFactory.createQueryOnlyRule(
            queryWords = listOf("ceasul")
        )
        every { fastMapDao.getAllRules() } returns flowOf(listOf(rule))

        val result = engine.processCommand("cat este ceasul", null)

        assertNotNull(result)
        assertEquals(IntentTaxonomy.Domains.SYSTEM, result?.domain)
        val query = result?.logicalSubject
        assertNotNull(query)
        assertTrue(query!!.contains("ceasul"))
    }

    @Test
    fun `messaging domain maps query to contact parameter`() = runTest {
        val rule = TestDataFactory.createMessagingRule(
            triggerWords = listOf("trimite", "mesaj", "la")
        )
        every { fastMapDao.getAllRules() } returns flowOf(listOf(rule))

        val result = engine.processCommand("trimite mesaj la maria", null)

        assertNotNull(result)
        assertEquals(IntentTaxonomy.Domains.MESSAGING, result?.domain)
        assertEquals(IntentTaxonomy.Actions.SEND, result?.action)
        val contact = result?.logicalSubject
        assertNotNull(contact)
        assertTrue(contact!!.contains("maria"))
    }

    @Test
    fun `settings domain rule matches volume up`() = runTest {
        val rule = TestDataFactory.createSettingsRule(
            triggerWords = listOf("volum", "up"),
            action = IntentTaxonomy.Actions.VOLUME_UP
        )
        every { fastMapDao.getAllRules() } returns flowOf(listOf(rule))

        val result = engine.processCommand("volum up", null)

        assertNotNull(result)
        assertEquals(IntentTaxonomy.Domains.SETTINGS, result?.domain)
        assertEquals(IntentTaxonomy.Actions.VOLUME_UP, result?.action)
    }

    @Test
    fun `mediaControlType is included in parameters when not blank`() = runTest {
        val rule = TestDataFactory.createTransportControlRule(
            triggerWords = listOf("next"),
            action = IntentTaxonomy.Actions.NEXT,
            mediaControlType = "audio_button"
        )
        every { fastMapDao.getAllRules() } returns flowOf(listOf(rule))

        val result = engine.processCommand("next", null)

        assertNotNull(result)
        assertEquals("audio_button", result?.mediaControlType)
    }

    @Test
    fun `rule with uriTemplate passes it through to NluIntent`() = runTest {
        val rule = TestDataFactory.createNavigationRule(
            triggerWords = listOf("du-ma", "la"),
            uriTemplate = "geo:0,0?q={destination}"
        )
        every { fastMapDao.getAllRules() } returns flowOf(listOf(rule))

        val result = engine.processCommand("du-ma la magazin", null)

        assertNotNull(result)
        assertEquals("geo:0,0?q={destination}", result?.uriTemplate)
    }

    @Test
    fun `rule with intentAction passes it through to NluIntent`() = runTest {
        val rule = TestDataFactory.createFastMapRule(
            triggerWords = listOf("deschide"),
            intentAction = "android.intent.action.VIEW",
            domain = "custom",
            action = "launch"
        )
        every { fastMapDao.getAllRules() } returns flowOf(listOf(rule))

        val result = engine.processCommand("deschide", null)

        assertNotNull(result)
        assertEquals("android.intent.action.VIEW", result?.intentAction)
    }

    @Test
    fun `rule with blank targetPackage sets targetApp to null`() = runTest {
        val rule = TestDataFactory.createFastMapRule(
            triggerWords = listOf("test"),
            targetPackage = "",
            domain = "custom",
            action = "launch"
        )
        every { fastMapDao.getAllRules() } returns flowOf(listOf(rule))

        val result = engine.processCommand("test", null)

        assertNotNull(result)
        assertNull(result?.targetApp)
    }

    @Test
    fun `rule with non-blank targetPackage sets targetApp`() = runTest {
        val rule = TestDataFactory.createFastMapRule(
            triggerWords = listOf("test"),
            targetPackage = "com.example.app",
            domain = "custom",
            action = "launch"
        )
        every { fastMapDao.getAllRules() } returns flowOf(listOf(rule))

        val result = engine.processCommand("test", null)

        assertNotNull(result)
        assertEquals("com.example.app", result?.targetApp)
    }

    @Test
    fun `anyOrder rule matches trigger words regardless of spoken order`() = runTest {
        val rule = TestDataFactory.createFastMapRule(
            triggerWords = listOf("flashlight", "on"),
            anyOrder = true,
            domain = IntentTaxonomy.Domains.SETTINGS,
            action = "flashlight_on"
        )
        every { fastMapDao.getAllRules() } returns flowOf(listOf(rule))

        val forward = engine.processCommand("turn flashlight on", null)
        val reversed = engine.processCommand("turn on flashlight", null)

        assertNotNull(forward)
        assertNotNull(reversed)
        assertEquals("flashlight_on", forward?.action)
        assertEquals("flashlight_on", reversed?.action)
    }

    @Test
    fun `anyOrder rule with both flags set falls back to queryWords instead of corrupting via replace`() = runTest {
        // Defensive guard: the UI enforces lazyQuery/anyOrder are mutually exclusive, but a
        // stale/hand-edited row could still have both set. Since an anyOrder trigger regex is
        // built from zero-width lookaheads, .replace()-ing it out of the spoken text (the
        // lazyQuery mechanism) would remove nothing — this pins that the engine instead falls
        // back to queryWords (here empty, so logicalSubject should be null) rather than emitting
        // a corrupted query still containing the trigger words.
        val rule = TestDataFactory.createFastMapRule(
            triggerWords = listOf("flashlight", "on"),
            lazyQuery = true,
            anyOrder = true,
            queryWords = emptyList(),
            domain = IntentTaxonomy.Domains.SETTINGS,
            action = "flashlight_on"
        )
        every { fastMapDao.getAllRules() } returns flowOf(listOf(rule))

        val result = engine.processCommand("turn on flashlight", null)

        assertNotNull(result)
        assertNull(result?.logicalSubject)
    }
}
