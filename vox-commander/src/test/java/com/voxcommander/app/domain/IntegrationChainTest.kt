package com.voxcommander.app.domain

import android.util.Log
import com.voxcommander.app.data.local.dao.FastMapDao
import com.voxcommander.app.data.preferences.AppSettings
import com.voxcommander.app.data.preferences.SettingsRepository
import com.voxcommander.app.domain.intent.IntentDecisionMap
import com.voxcommander.app.domain.intent.interpreter.AssistantEngine
import com.voxcommander.app.domain.intent.interpreter.FastMapEngine
import com.voxcommander.app.domain.intent.taxonomy.IntentTaxonomy
import com.voxcommander.app.testutil.TestDataFactory
import com.voxcommander.app.utils.Strings
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test

/**
 * TRUE Integration Test: Verifies the interaction between REAL components.
 * Chain: Spoken Text -> Real FastMapEngine (Regex) -> IntentDecisionMap -> NluIntent
 */
@OptIn(ExperimentalCoroutinesApi::class)
class IntegrationChainTest {

    private lateinit var settingsRepo: SettingsRepository
    private lateinit var fastMapDao: FastMapDao

    private lateinit var realL1Engine: FastMapEngine
    private lateinit var l2CloudEngine: AssistantEngine
    private lateinit var l3LocalEngine: AssistantEngine
    private lateinit var geminiNanoEngine: AssistantEngine
    private lateinit var geminiCloudEngine: AssistantEngine

    private lateinit var decisionMap: IntentDecisionMap

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>()) } returns 0

        settingsRepo = mockk(relaxed = true)
        fastMapDao = mockk()

        // Use the REAL regex engine
        realL1Engine = FastMapEngine(fastMapDao)

        // Keep external AI as mocks
        l2CloudEngine = mockk()
        l3LocalEngine = mockk()
        geminiNanoEngine = mockk()
        geminiCloudEngine = mockk()

        decisionMap = IntentDecisionMap(
            realL1Engine,
            l2CloudEngine,
            l3LocalEngine,
            geminiNanoEngine,
            geminiCloudEngine,
            settingsRepo
        )

        val defaultSettings = AppSettings(
            cloudIntelligenceEnabled = true,
            aiProcessor = Strings.AiProcessors.OPENAI
        )
        every { settingsRepo.getSettingsSnapshot() } returns defaultSettings
    }

    @Test
    fun `full chain flow through REAL Regex engine`() = runTest {
        val rule = TestDataFactory.createNavigationRule(
            triggerWords = listOf("du-ma", "la")
        )
        every { fastMapDao.getAllRules() } returns flowOf(listOf(rule))

        val spokenText = "du-ma la bucuresti"
        val finalIntent = decisionMap.processCommand(spokenText, null)

        assertNotNull("Intent should not be null for a match", finalIntent)
        assertEquals(IntentTaxonomy.Domains.MAPS, finalIntent?.domain)
        assertEquals(IntentTaxonomy.Actions.NAVIGATE, finalIntent?.action)
        val destination = finalIntent?.logicalSubject
        assertNotNull(destination)
        assertEquals("bucuresti", destination)
    }

    @Test
    fun `full chain fallback when REAL Regex misses`() = runTest {
        every { fastMapDao.getAllRules() } returns flowOf(emptyList())

        val spokenText = "cine a castigat meciul?"
        val expectedIntent = TestDataFactory.createNluIntent(
            domain = IntentTaxonomy.Domains.SYSTEM,
            action = "query"
        )

        every { settingsRepo.getSettingsSnapshot() } returns AppSettings(
            cloudIntelligenceEnabled = true,
            aiProcessor = Strings.AiProcessors.OPENAI
        )
        coEvery { l2CloudEngine.processCommand(spokenText, any()) } returns expectedIntent

        val finalIntent = decisionMap.processCommand(spokenText, null)

        assertNotNull(finalIntent)
        assertEquals(IntentTaxonomy.Domains.SYSTEM, finalIntent?.domain)

        // Verify the flow: L1 was called (but missed), L2 handled it
        coVerify(exactly = 1) { l2CloudEngine.processCommand(spokenText, any()) }
    }
}
