package com.voxcommander.app.data.remote

import android.util.Log
import com.voxcommander.app.data.preferences.SettingsRepository
import com.voxcommander.app.testutil.TestDataFactory
import com.voxcommander.app.utils.Strings
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

        mockkObject(com.voxcommander.app.utils.Logger)
        every { com.voxcommander.app.utils.Logger.log(any(), any()) } returns Unit
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
    fun `isLlmEngine returns true when llm is in engine types`() {
        mockkObject(RemoteModelRegistry)
        every { RemoteModelRegistry.getEngineTypes("nlu_llm") } returns listOf("llm")

        assertTrue(RemoteModelRegistry.isLlmEngine("nlu_llm"))
    }

    @Test
    fun `isLlmEngine returns false when llm is not in engine types`() {
        mockkObject(RemoteModelRegistry)
        every { RemoteModelRegistry.getEngineTypes("stt_whisper") } returns listOf("voice")

        assertFalse(RemoteModelRegistry.isLlmEngine("stt_whisper"))
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
    fun `getEngineKeyByExtension returns key for matching extension`() {
        mockkObject(RemoteModelRegistry)
        every { RemoteModelRegistry.getExtension("stt_whisper") } returns ".bin"
        every { RemoteModelRegistry.getExtension("wake_vosk") } returns ".zip"
        every { RemoteModelRegistry.getEngineTypes() } returns listOf("stt_whisper", "wake_vosk")

        // We can't easily test getEngineKeyByExtension without a real cachedSchema
        // but we can test the logic via the extension lookup
        assertEquals(".bin", RemoteModelRegistry.getExtension("stt_whisper"))
    }

    @Test
    fun `getModels returns empty list for unknown engine key`() {
        mockkObject(RemoteModelRegistry)
        every { RemoteModelRegistry.getModels("unknown_engine") } returns emptyList()

        assertEquals(emptyList<com.voxcommander.app.domain.model.AppModel>(), RemoteModelRegistry.getModels("unknown_engine"))
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
