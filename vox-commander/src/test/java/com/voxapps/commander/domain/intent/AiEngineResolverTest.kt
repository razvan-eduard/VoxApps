package com.voxapps.commander.domain.intent

import com.voxapps.commander.data.remote.RemoteModelRegistry
import com.voxapps.commander.domain.intent.interpreter.AssistantEngine
import com.voxapps.commander.utils.Strings
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class AiEngineResolverTest {

    private lateinit var openAiEngine: AssistantEngine
    private lateinit var localLlmEngine: AssistantEngine
    private lateinit var resolver: AiEngineResolver

    @Before
    fun setup() {
        openAiEngine = mockk()
        localLlmEngine = mockk()
        resolver = AiEngineResolver(openAiEngine, localLlmEngine)
    }

    @After
    fun tearDown() = unmockkAll()

    @Test
    fun `OPENAI resolves to the cloud engine, gated on cloud intelligence`() {
        val choice = resolver.resolve(Strings.AiProcessors.OPENAI)!!
        assertEquals(openAiEngine, choice.engine)
        assertEquals(true, choice.requiresCloud)
    }

    @Test
    fun `a local_llm-capable key resolves to the local engine, ungated`() {
        mockkObject(RemoteModelRegistry)
        every { RemoteModelRegistry.isLlmEngine("nlu_llm") } returns true

        val choice = resolver.resolve("nlu_llm")!!
        assertEquals(localLlmEngine, choice.engine)
        assertEquals(false, choice.requiresCloud)
    }

    /**
     * A stored selection naming a retired engine resolves to nothing, which silently removes the
     * cascade's primary stage. This is exactly why every path a retired key can enter through is
     * remapped: `migrateGoogleLlmRemoval` for values already on the device, `normalizeEngineKey`
     * for imported backups. If this test starts failing because a retired key resolves again, one
     * of those remaps has probably been re-routed — check both before changing this expectation.
     */
    @Test
    fun `retired engine keys resolve to nothing — the migrations are what keep them out`() {
        mockkObject(RemoteModelRegistry)
        every { RemoteModelRegistry.isLlmEngine(any()) } returns false

        assertNull(resolver.resolve("GEMINI_CLOUD"))
        assertNull(resolver.resolve("GEMINI_NATIVE"))
    }
}
