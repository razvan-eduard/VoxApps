package com.voxapps.commander.data.preferences

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppSettingsTest {

    @Test
    fun `default AppSettings has null apiKey`() {
        val settings = AppSettings()
        assertNull(settings.apiKey)
    }

    @Test
    fun `default AppSettings has empty downloadedModelIds`() {
        val settings = AppSettings()
        assertTrue(settings.downloadedModelIds.isEmpty())
    }

    @Test
    fun `isModelDownloaded returns true when model is in downloadedModelIds`() {
        val settings = AppSettings(downloadedModelIds = setOf("base", "tiny"))
        assertTrue(settings.isModelDownloaded("base"))
        assertTrue(settings.isModelDownloaded("tiny"))
    }

    @Test
    fun `isModelDownloaded returns false when model is not in downloadedModelIds`() {
        val settings = AppSettings(downloadedModelIds = setOf("base"))
        assertFalse(settings.isModelDownloaded("tiny"))
    }

    @Test
    fun `customModelPathKey returns engineKey when langCode is null`() {
        val settings = AppSettings()
        assertEquals("stt_whisper", settings.customModelPathKey("stt_whisper"))
    }

    @Test
    fun `customModelPathKey returns engineKey_langCode when langCode is provided`() {
        val settings = AppSettings()
        assertEquals("stt_whisper_ro", settings.customModelPathKey("stt_whisper", "ro"))
    }

    @Test
    fun `getCustomModelPath returns path from customModelPaths`() {
        val settings = AppSettings(
            customModelPaths = mapOf("stt_whisper" to "/data/model.bin")
        )
        assertEquals("/data/model.bin", settings.getCustomModelPath("stt_whisper"))
    }

    @Test
    fun `getCustomModelPath with langCode returns path from customModelPaths`() {
        val settings = AppSettings(
            customModelPaths = mapOf("wake_vosk_ro" to "/data/vosk-ro")
        )
        assertEquals("/data/vosk-ro", settings.getCustomModelPath("wake_vosk", "ro"))
    }

    @Test
    fun `getCustomModelPath returns null when not found`() {
        val settings = AppSettings()
        assertNull(settings.getCustomModelPath("stt_whisper"))
    }

    @Test
    fun `default cloudIntelligenceEnabled is false`() {
        val settings = AppSettings()
        assertFalse(settings.cloudIntelligenceEnabled)
    }

    @Test
    fun `default offlineFallbackTimeout is 10`() {
        val settings = AppSettings()
        assertEquals(10, settings.offlineFallbackTimeout)
    }

    @Test
    fun `default externalTriggerEnabled is true`() {
        val settings = AppSettings()
        assertTrue(settings.externalTriggerEnabled)
    }

    @Test
    fun `externalTriggerEnabled can be set to false`() {
        val settings = AppSettings(externalTriggerEnabled = false)
        assertFalse(settings.externalTriggerEnabled)
    }

    @Test
    fun `default returnAfterActionApps is empty`() {
        val settings = AppSettings()
        assertTrue(settings.returnAfterActionApps.isEmpty())
    }

    @Test
    fun `returnAfterActionApps can contain package names`() {
        val settings = AppSettings(returnAfterActionApps = listOf("com.spotify.music", "org.libretube"))
        assertEquals(2, settings.returnAfterActionApps.size)
        assertTrue(settings.returnAfterActionApps.contains("com.spotify.music"))
    }

    /**
     * A model imported for a language nobody listed is still a model the user imported.
     *
     * The caller walked a written-down list of four languages and asked for each, so anything
     * outside it was stored and never read back — the path was there, and nothing went looking.
     */
    @Test
    fun `custom model paths are found for every language stored, not a listed few`() {
        val settings = AppSettings(
            customModelPaths = mapOf(
                "stt_vosk_en" to "/models/vosk-en",
                "stt_vosk_es" to "/models/vosk-es",
                "stt_vosk" to "/models/vosk-default",
                "stt_whisper_en" to "/models/whisper-en"
            )
        )

        val vosk = settings.customModelPathsByLanguage("stt_vosk")

        assertEquals(mapOf("en" to "/models/vosk-en", "es" to "/models/vosk-es"), vosk)
    }

    /** The engine's own unsuffixed entry is not a language, and neither is another engine's. */
    @Test
    fun `custom model paths by language exclude the engine's own entry`() {
        val settings = AppSettings(customModelPaths = mapOf("stt_vosk" to "/models/vosk"))

        assertTrue(settings.customModelPathsByLanguage("stt_vosk").isEmpty())
    }
}
