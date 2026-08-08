package com.voxapps.commander.domain.engine

import android.util.Log
import com.voxapps.commander.data.preferences.SettingsRepository
import com.voxapps.commander.testutil.TestDataFactory
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The rule every domain has to apply before asking the registry where a model is.
 *
 * It lives here because it was written per domain and one domain forgot: the wake-word service
 * resolved the registry's model and never looked at what the user had imported, so an import there
 * was stored, shown in settings as configured, and never loaded by anything.
 */
class EngineSpecsTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var settingsRepo: SettingsRepository

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>()) } returns 0
        settingsRepo = mockk(relaxed = true)
    }

    private fun withCustomPaths(paths: Map<String, String>) {
        every { settingsRepo.getSettingsSnapshot() } returns
            TestDataFactory.createAppSettings(customModelPaths = paths)
    }

    @Test
    fun `an imported model is found for its engine`() {
        val file = temp.newFile("my-own-model.onnx")
        withCustomPaths(mapOf("wake_openwakeword" to file.absolutePath))

        assertEquals(file, EngineSpecs.importedModel(settingsRepo, "wake_openwakeword"))
    }

    /** Vosk keeps one import per language, so the key carries the language code. */
    @Test
    fun `an import is found under the language it was stored for`() {
        val file = temp.newFolder("vosk-model-ro")
        withCustomPaths(mapOf("wake_vosk_ro" to file.absolutePath))

        assertEquals(file, EngineSpecs.importedModel(settingsRepo, "wake_vosk", "ro"))
        assertNull("an import for one language answered for another",
            EngineSpecs.importedModel(settingsRepo, "wake_vosk", "en"))
    }

    /**
     * The file lives outside the app and can be deleted, moved or on storage that is not mounted.
     * Reporting it as present would make the engine fail to load with nothing explaining why.
     */
    @Test
    fun `an import whose file has gone is not offered`() {
        withCustomPaths(mapOf("stt_whisper" to "/nowhere/gone.bin"))

        assertNull(EngineSpecs.importedModel(settingsRepo, "stt_whisper"))
    }

    @Test
    fun `an engine with no import gets nothing`() {
        withCustomPaths(emptyMap())

        assertNull(EngineSpecs.importedModel(settingsRepo, "stt_whisper"))
    }
}
