package com.voxapps.commander.receiver

import com.voxapps.commander.data.preferences.AppSettings
import com.voxapps.commander.data.preferences.Credentials
import com.voxapps.commander.testutil.TestDataFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.reflect.full.memberProperties

class CommanderExportHandlerTest {

    /**
     * The secrets no longer live on [com.voxapps.commander.data.preferences.AppSettings] — they come
     * from the encrypted store as [Credentials]. A backup that quietly carried empty keys would look
     * exactly like a backup made without them, and would only be found out on the restore that was
     * supposed to bring them back.
     */
    @Test
    fun `an export with secrets carries the credentials, not the settings' empty copies`() {
        val json = CommanderExportHandler.buildExportJson(
            settings = TestDataFactory.createAppSettings(),
            includeSecrets = true,
            searchProviderApiKeys = mapOf("brave" to "brave-key"),
            credentials = Credentials(
                mapOf(
                    "OPENAI" to "openai-key",
                    "GEMINI_CLOUD" to "gemini-key",
                    "wake_porcupine" to "picovoice-key",
                    "WHISPER_API" to "whisper-key"
                )
            )
        )

        val restored = CommanderExportHandler.parsePortableSettings(json)!!
        // Every engine's credential travels, including the one that has no single-key field of its
        // own — which is the whole reason the map exists.
        assertEquals("whisper-key", restored.engineApiKeys["WHISPER_API"])
        assertEquals("openai-key", restored.engineApiKeys["OPENAI"])
        assertEquals(mapOf("brave" to "brave-key"), restored.searchProviderApiKeys)

        // ...and the single-key fields are still written, so an older build can restore this file.
        assertEquals("openai-key", restored.apiKey)
        assertEquals("gemini-key", restored.geminiApiKey)
        assertEquals("picovoice-key", restored.picovoiceAccessKey)
    }

    @Test
    fun `an export without secrets carries none of them, however they were passed`() {
        val json = CommanderExportHandler.buildExportJson(
            settings = TestDataFactory.createAppSettings(),
            includeSecrets = false,
            searchProviderApiKeys = mapOf("brave" to "brave-key"),
            credentials = Credentials(
                mapOf(
                    "OPENAI" to "openai-key",
                    "GEMINI_CLOUD" to "gemini-key",
                    "wake_porcupine" to "picovoice-key",
                    "WHISPER_API" to "whisper-key"
                )
            )
        )

        val restored = CommanderExportHandler.parsePortableSettings(json)!!
        assertTrue(restored.engineApiKeys.isEmpty())
        assertNull(restored.apiKey)
        assertNull(restored.geminiApiKey)
        assertNull(restored.picovoiceAccessKey)
        assertTrue(restored.searchProviderApiKeys.isEmpty())
    }

    @Test
    fun `buildFastMapRulesJson then parseFastMapRules round-trips every field`() {
        val rules = listOf(
            TestDataFactory.createFastMapRule(
                id = 1L,
                allWords = listOf("turn", "on", "the", "flashlight"),
                triggerWords = listOf("flashlight"),
                queryWords = emptyList(),
                domain = "settings",
                action = "flashlight_on"
            ),
            TestDataFactory.createFastMapRule(
                id = 2L,
                targetPackage = "com.example.app",
                intentAction = "android.intent.action.VIEW",
                anyOrder = true
            )
        )

        val json = CommanderExportHandler.buildFastMapRulesJson(rules)
        val parsed = CommanderExportHandler.parseFastMapRules(json)

        assertEquals(rules, parsed)
    }

    @Test
    fun `parseFastMapRules returns null for invalid json`() {
        assertNull(CommanderExportHandler.parseFastMapRules("{ not json"))
    }

    @Test
    fun `parseFastMapRules returns empty list for an empty array`() {
        val parsed = CommanderExportHandler.parseFastMapRules("[]")
        assertTrue(parsed != null && parsed.isEmpty())
    }

    /**
     * Every collection survives an export that spells it `null`.
     *
     * Gson fills these by reflection, so a field written as null is genuinely null behind a non-null
     * Kotlin type — and the first thing to iterate it throws. (An *absent* field is harmless: every
     * parameter has a default, so Gson uses the no-arg constructor. It is the written null that
     * bites, which is what a foreign or older exporter produces.) The parser coalesces each one
     * back to its empty default, which works only for as long as someone remembers to add the next
     * field to that list. This asks the class itself instead of naming them, so the field nobody
     * remembered fails here rather than in an import of a real backup.
     */
    @Test
    fun `every collection field survives an export that spells it null`() {
        val collections = AppSettings::class.memberProperties.filter { property ->
            val type = property.returnType.classifier
            type == Map::class || type == List::class || type == Set::class
        }
        val allNull = collections.joinToString(prefix = "{", postfix = "}") { "\"${it.name}\": null" }

        val parsed = CommanderExportHandler.parsePortableSettings(allNull)
            ?: error("an export of nulls should still parse")

        val stillNull = collections.filter { it.get(parsed) == null }.map { it.name }

        assertEquals(emptyList<String>(), stillNull)
    }
}
