package com.voxapps.commander.domain.engine

import android.util.Log
import com.voxapps.commander.data.preferences.SettingsRepository
import com.voxapps.commander.testutil.TestDataFactory
import com.voxapps.commander.data.remote.EngineRuntime
import com.voxapps.commander.data.remote.EntryPoint
import com.voxapps.commander.data.remote.RemoteModelRegistry
import io.mockk.mockkObject
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import org.junit.Assert.assertEquals
import com.voxapps.commander.domain.model.ImportedModelId
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    fun `an imported id names its engine and language, both ways`() {
        val plain = ImportedModelId.of("stt_whisper")
        val perLang = ImportedModelId.of("wake_vosk", "en")

        assertTrue(ImportedModelId.isImported(plain))
        assertTrue(ImportedModelId.isImported(perLang))
        assertFalse(ImportedModelId.isImported("ggml-base"))

        assertEquals("stt_whisper", ImportedModelId.engineOf(plain))
        assertEquals("wake_vosk", ImportedModelId.engineOf(perLang))
        assertNull(ImportedModelId.langOf(plain))
        assertEquals("en", ImportedModelId.langOf(perLang))
    }

    @Test
    fun `an import becomes a row describing the file on disk`() {
        val file = temp.newFile("my-own-model.bin")
        file.writeBytes(ByteArray(3 * 1024 * 1024))
        withCustomPaths(mapOf("stt_whisper" to file.absolutePath))

        val row = EngineSpecs.importedRow(settingsRepo, "stt_whisper")

        assertEquals(ImportedModelId.of("stt_whisper"), row?.id)
        assertEquals("my-own-model.bin", row?.label)
        assertEquals("3 MB", row?.sizeDescription)
        // Nothing to fetch and nothing bundled: the row offers a delete and no download.
        assertEquals(false, row?.isBuiltIn)
        assertEquals("", row?.url)
    }

    @Test
    fun `a directory import is sized by what it contains`() {
        val dir = temp.newFolder("vosk-model-custom")
        java.io.File(dir, "am").mkdirs()
        java.io.File(dir, "am/final.mdl").writeBytes(ByteArray(2 * 1024 * 1024))
        java.io.File(dir, "conf.txt").writeBytes(ByteArray(1024 * 1024))
        withCustomPaths(mapOf("wake_vosk_en" to dir.absolutePath))

        assertEquals("3 MB", EngineSpecs.importedRow(settingsRepo, "wake_vosk", "en")?.sizeDescription)
    }

    @Test
    fun `a path whose file has gone is not offered as a row`() {
        withCustomPaths(mapOf("stt_whisper" to "/nowhere/model.bin"))

        assertNull(EngineSpecs.importedRow(settingsRepo, "stt_whisper"))
    }

    @Test
    fun `a selected import is what loads`() {
        val file = temp.newFile("my-own-model.bin")
        withCustomPaths(mapOf("stt_whisper" to file.absolutePath))
        mockkObject(RemoteModelRegistry)
        every { RemoteModelRegistry.runtimeOf("stt_whisper") } returns EngineRuntime.LOCAL_FILE
        every { RemoteModelRegistry.getEntryPoint("stt_whisper") } returns EntryPoint(self = true)

        val spec = EngineSpecs.build(
            context = mockk(relaxed = true),
            settingsRepo = settingsRepo,
            engineKey = "stt_whisper",
            modelId = ImportedModelId.of("stt_whisper"),
            language = "en"
        )

        assertEquals(file, (spec as? ModelSpec.LocalModel)?.entryPoint)
    }

    @Test
    fun `a selected import whose file has gone loads nothing rather than something else`() {
        // Falling through to the registry would transcribe with a model the user did not choose,
        // under the name of the one they did.
        withCustomPaths(mapOf("stt_whisper" to "/nowhere/model.bin"))
        mockkObject(RemoteModelRegistry)
        every { RemoteModelRegistry.runtimeOf("stt_whisper") } returns EngineRuntime.LOCAL_FILE

        val spec = EngineSpecs.build(
            context = mockk(relaxed = true),
            settingsRepo = settingsRepo,
            engineKey = "stt_whisper",
            modelId = ImportedModelId.of("stt_whisper"),
            language = "en"
        )

        assertNull(spec)
    }

    @Test
    fun `an engine with no import gets nothing`() {
        withCustomPaths(emptyMap())

        assertNull(EngineSpecs.importedModel(settingsRepo, "stt_whisper"))
    }
}
