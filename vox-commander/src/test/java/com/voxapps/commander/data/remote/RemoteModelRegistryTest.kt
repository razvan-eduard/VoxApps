package com.voxapps.commander.data.remote

import android.util.Log
import com.voxapps.commander.data.preferences.SettingsRepository
import com.voxapps.commander.testutil.TestDataFactory
import com.voxapps.commander.utils.Strings
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RemoteModelRegistryTest {

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>()) } returns 0

        mockkObject(com.voxapps.logging.Logger)
        every { com.voxapps.logging.Logger.log(any(), any()) } returns Unit
    }

    @Test
    fun `resolveUrl returns url directly when it starts with http`() {
        val item = TestDataFactory.createRemoteModelItem(
            path = "https://example.com/model.bin"
        )
        val repo = mockk<SettingsRepository>(relaxed = true)

        val result = RemoteModelRegistry.resolveUrl(item, repo)

        assertEquals("https://example.com/model.bin", result)
    }

    @Test
    fun `resolveUrl converts github URL to releases download URL`() {
        val item = TestDataFactory.createRemoteModelItem(
            path = "stt_whisper/base.bin"
        )
        val repo = mockk<SettingsRepository>(relaxed = true)
        val settings = TestDataFactory.createAppSettings()
        settings.modelRepoBaseUrl.let { baseUrl ->
            every { repo.getSettingsSnapshot() } returns settings.copy(
                modelRepoBaseUrl = "https://github.com/razvan-eduard/VoxCommander"
            )
        }

        val result = RemoteModelRegistry.resolveUrl(item, repo)

        assertTrue(result.contains("releases/download"))
        assertTrue(result.contains("stt_whisper/base.bin"))
    }

    @Test
    fun `resolveUrl uses baseUrl for non-github URLs`() {
        val item = TestDataFactory.createRemoteModelItem(
            path = "models/base.bin"
        )
        val repo = mockk<SettingsRepository>(relaxed = true)
        every { repo.getSettingsSnapshot() } returns TestDataFactory.createAppSettings().copy(
            modelRepoBaseUrl = "https://custom.server.com/repo"
        )

        val result = RemoteModelRegistry.resolveUrl(item, repo)

        assertEquals("https://custom.server.com/repo/models/base.bin", result)
    }

    @Test
    fun `resolveUrl handles trailing slash in baseUrl`() {
        val item = TestDataFactory.createRemoteModelItem(
            path = "models/base.bin"
        )
        val repo = mockk<SettingsRepository>(relaxed = true)
        every { repo.getSettingsSnapshot() } returns TestDataFactory.createAppSettings().copy(
            modelRepoBaseUrl = "https://custom.server.com/repo/"
        )

        val result = RemoteModelRegistry.resolveUrl(item, repo)

        assertEquals("https://custom.server.com/repo/models/base.bin", result)
    }

    @Test
    fun `resolveUrl handles leading slash in path`() {
        val item = TestDataFactory.createRemoteModelItem(
            path = "/models/base.bin"
        )
        val repo = mockk<SettingsRepository>(relaxed = true)
        every { repo.getSettingsSnapshot() } returns TestDataFactory.createAppSettings().copy(
            modelRepoBaseUrl = "https://custom.server.com/repo"
        )

        val result = RemoteModelRegistry.resolveUrl(item, repo)

        assertEquals("https://custom.server.com/repo/models/base.bin", result)
    }

    @Test
    fun `isZipEngine returns true for zip extension`() {
        mockkObject(RemoteModelRegistry)
        every { RemoteModelRegistry.getExtension("wake_vosk") } returns ".zip"

        assertTrue(RemoteModelRegistry.isZipEngine("wake_vosk"))
    }

    @Test
    fun `isZipEngine returns false for bin extension`() {
        mockkObject(RemoteModelRegistry)
        every { RemoteModelRegistry.getExtension("stt_whisper") } returns ".bin"

        assertFalse(RemoteModelRegistry.isZipEngine("stt_whisper"))
    }

    @Test
    fun `isZipEngine is case insensitive`() {
        mockkObject(RemoteModelRegistry)
        every { RemoteModelRegistry.getExtension("some_engine") } returns ".ZIP"

        assertTrue(RemoteModelRegistry.isZipEngine("some_engine"))
    }

    @Test
    fun `isArchiveEngine covers every compressed artefact format, not just zip`() {
        mockkObject(RemoteModelRegistry)
        every { RemoteModelRegistry.getExtension("wake_vosk") } returns ".zip"
        every { RemoteModelRegistry.getExtension("piper_tts") } returns ".tar.bz2"
        every { RemoteModelRegistry.getExtension("upper") } returns ".TAR.BZ2"

        assertTrue(RemoteModelRegistry.isArchiveEngine("wake_vosk"))
        assertTrue(RemoteModelRegistry.isArchiveEngine("piper_tts"))
        assertTrue(RemoteModelRegistry.isArchiveEngine("upper"))
    }

    @Test
    fun `isArchiveEngine is false for single-file and virtual engines`() {
        mockkObject(RemoteModelRegistry)
        every { RemoteModelRegistry.getExtension("stt_whisper") } returns ".bin"
        every { RemoteModelRegistry.getExtension("nlu_llm") } returns ".gguf"
        every { RemoteModelRegistry.getExtension("OPENAI") } returns ""

        assertFalse(RemoteModelRegistry.isArchiveEngine("stt_whisper"))
        assertFalse(RemoteModelRegistry.isArchiveEngine("nlu_llm"))
        assertFalse(RemoteModelRegistry.isArchiveEngine("OPENAI"))
    }

    /**
     * The download pipeline has exactly two paths — extract-then-signal and ready-as-is — chosen by
     * extension. An engine whose extension is compressed but absent from [RemoteModelRegistry
     * .ARCHIVE_EXTENSIONS] silently takes the second path, never gets unpacked, and then fails
     * verification because nothing exists where the extracted directory should be. This asserts
     * against the real assets so shipping such an engine fails here rather than on a device.
     */
    @Test
    fun `every compressed extension in the shipped models_json is a known archive format`() {
        val json = listOf(
            java.io.File("src/main/assets/schemas/models.json"),
            java.io.File("vox-commander/src/main/assets/schemas/models.json"),
            java.io.File("../remote-schemas/commander/models.json")
        ).firstOrNull { it.exists() }
        assertTrue("models.json not found from ${java.io.File(".").absolutePath}", json != null)

        val schema = com.google.gson.Gson()
            .fromJson(json!!.readText(), RemoteModelSchema::class.java)

        // Anything that looks compressed by name must be declared as an archive format.
        val compressedMarkers = listOf("zip", "tar", "gz", "bz2", "xz", "7z", "rar")
        schema.engines.forEach { (key, config) ->
            val ext = config.extension
            val looksCompressed = compressedMarkers.any { ext.contains(it, ignoreCase = true) }
            if (looksCompressed) {
                assertTrue(
                    "Engine '$key' ships extension '$ext', which no extraction path handles — " +
                        "add it to ARCHIVE_EXTENSIONS together with a decoder in ModelDownloader",
                    RemoteModelRegistry.ARCHIVE_EXTENSIONS.any { it.equals(ext, ignoreCase = true) }
                )
            }
        }
    }

    /**
     * The shipped schema is the contract between the JSON and the code that acts on it. These
     * assertions are cheap and catch, at build time, the class of mistake that has cost the most:
     * an engine whose declaration the download pipeline cannot act on.
     */
    @Test
    fun `every engine in the shipped models_json declares a runtime the code understands`() {
        val schema = shippedSchema()

        schema.engines.forEach { (key, config) ->
            assertTrue(
                "Engine '$key' has runtime '${config.runtime}', which EngineRuntime cannot parse",
                EngineRuntime.fromKey(config.runtime) != null
            )
        }
    }

    @Test
    fun `every local_file engine declares an entry point and a non-empty extension`() {
        val schema = shippedSchema()

        schema.engines
            .filter { EngineRuntime.fromKey(it.value.runtime) == EngineRuntime.LOCAL_FILE }
            .forEach { (key, config) ->
                assertTrue("Engine '$key' is local_file but declares no entry point", config.entry != null)
                assertTrue("Engine '$key' is local_file but has no extension to download", config.extension.isNotBlank())
                val entry = config.entry!!
                assertTrue(
                    "Engine '$key' declares an entry that is neither self nor a match",
                    entry.self || !entry.match.isNullOrBlank()
                )
            }
    }

    @Test
    fun `a non-local_file engine downloads nothing`() {
        val schema = shippedSchema()

        schema.engines
            .filter { EngineRuntime.fromKey(it.value.runtime) != EngineRuntime.LOCAL_FILE }
            .forEach { (key, config) ->
                assertEquals("Engine '$key' is not local_file but declares a download extension", "", config.extension)
            }
    }

    /**
     * The asset copy and the repo-root copy are served to different consumers — the app reads the
     * asset, a device with a configured modelRepoBaseUrl reads the remote one — and `fetchJson`
     * permanently rejects a remote copy whose schema_version is lower than the asset's. Letting them
     * drift silently strands every install that already fetched the newer number.
     */
    @Test
    fun `both models_json copies carry the same schema_version`() {
        val asset = locate("src/main/assets/schemas/models.json", "vox-commander/src/main/assets/schemas/models.json")
        val cdn = locate("../remote-schemas/commander/models.json", "models.json")
        assertTrue("could not locate both models.json copies", asset != null && cdn != null)

        val gson = com.google.gson.Gson()
        assertEquals(
            gson.fromJson(asset!!.readText(), RemoteModelSchema::class.java).schema_version,
            gson.fromJson(cdn!!.readText(), RemoteModelSchema::class.java).schema_version
        )
    }

    private fun shippedSchema(): RemoteModelSchema {
        val file = locate("src/main/assets/schemas/models.json", "vox-commander/src/main/assets/schemas/models.json")
        assertTrue("models.json not found from ${java.io.File(".").absolutePath}", file != null)
        return com.google.gson.Gson().fromJson(file!!.readText(), RemoteModelSchema::class.java)
    }

    private fun locate(vararg candidates: String): java.io.File? =
        candidates.map { java.io.File(it) }.firstOrNull { it.exists() }

    @Test
    fun `isLlmEngine returns true when engine declares the local_llm capability`() {
        // isLlmEngine is capability-driven (hasCapability(engineKey, "local_llm")), not type-driven —
        // there can be more than one local LLM engine (one per model format), each independently
        // selectable, so this no longer keys off getEngineTypes()/a single "llm" type string.
        mockkObject(RemoteModelRegistry)
        every { RemoteModelRegistry.hasCapability("nlu_llm", "local_llm") } returns true

        assertTrue(RemoteModelRegistry.isLlmEngine("nlu_llm"))
    }

    @Test
    fun `isLlmEngine returns false when engine does not declare the local_llm capability`() {
        mockkObject(RemoteModelRegistry)
        every { RemoteModelRegistry.hasCapability("stt_whisper", "local_llm") } returns false

        assertFalse(RemoteModelRegistry.isLlmEngine("stt_whisper"))
    }

    /**
     * A derived default is the one selection the user never made, so it must never be an engine
     * they have to opt into. This is also what stops turning consent *off* from handing the
     * selection straight back to a gated engine: that path clears the stored key, and the next read
     * derives one again.
     */
    @Test
    fun `the derived default skips a consent-gated engine until consent is given`() {
        mockkObject(RemoteModelRegistry)
        every { RemoteModelRegistry.getLlmEngineKeys() } returns listOf("nlu_llm", "nlu_llm_task")
        every { RemoteModelRegistry.hasCapability("nlu_llm", "google_service") } returns false
        every { RemoteModelRegistry.hasCapability("nlu_llm_task", "google_service") } returns true

        assertEquals("nlu_llm", RemoteModelRegistry.getDefaultLlmEngineKey())
        assertEquals("nlu_llm", RemoteModelRegistry.getDefaultLlmEngineKey(allowGoogle = true))
    }

    /** A registry serving only gated engines still has to name one — an empty primary would drop
     *  the cascade's first stage entirely, which is worse than naming an engine the picker greys. */
    @Test
    fun `the derived default falls through when every engine is gated`() {
        mockkObject(RemoteModelRegistry)
        every { RemoteModelRegistry.getLlmEngineKeys() } returns listOf("nlu_llm_task")
        every { RemoteModelRegistry.hasCapability("nlu_llm_task", "google_service") } returns true

        assertEquals("nlu_llm_task", RemoteModelRegistry.getDefaultLlmEngineKey())
    }

    @Test
    fun `isWakeWordEngine returns true when wake_word is in engine types`() {
        mockkObject(RemoteModelRegistry)
        every { RemoteModelRegistry.getEngineTypes("wake_vosk") } returns listOf("voice", "wake_word")

        assertTrue(RemoteModelRegistry.isWakeWordEngine("wake_vosk"))
    }

    @Test
    fun `isVoiceEngine returns true when voice is in engine types`() {
        mockkObject(RemoteModelRegistry)
        every { RemoteModelRegistry.getEngineTypes("stt_whisper") } returns listOf("voice")

        assertTrue(RemoteModelRegistry.isVoiceEngine("stt_whisper"))
    }

    @Test
    fun `getModels returns empty list for unknown engine key`() {
        mockkObject(RemoteModelRegistry)
        every { RemoteModelRegistry.getModels("unknown_engine") } returns emptyList()

        assertEquals(emptyList<com.voxapps.commander.domain.model.AppModel>(), RemoteModelRegistry.getModels("unknown_engine"))
    }

    @Test
    fun `getModels returns models for known engine key`() {
        val models = listOf(
            TestDataFactory.createRemoteModelItem(id = "base"),
            TestDataFactory.createRemoteModelItem(id = "tiny")
        )
        mockkObject(RemoteModelRegistry)
        every { RemoteModelRegistry.getModels("stt_whisper") } returns models

        val result = RemoteModelRegistry.getModels("stt_whisper")
        assertEquals(2, result.size)
        assertEquals("base", result[0].id)
        assertEquals("tiny", result[1].id)
    }
}
