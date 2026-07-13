package com.voxapps.commander.receiver

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.voxapps.commander.data.preferences.AppSettings

/**
 * Vox Hub's export/import for Commander's own settings, extracted from the BroadcastReceiver so
 * it's unit-testable without Android. Commander has no notes/expenses-style records of its own —
 * this is settings-only, and (unlike vox-notes/vox-expenses, which keep secrets in a completely
 * separate store) [AppSettings] mixes real secrets in with ordinary preferences, so they're
 * explicitly stripped in [buildExportJson] rather than relied on to already be absent.
 * [parsePortableSettings] is the import-side mirror: parses an exported blob back into an
 * [AppSettings] (never containing secrets/paths/caches to begin with, since export never wrote
 * them), which the caller then merges into the current on-device settings via
 * [com.voxapps.commander.data.preferences.SettingsRepository.restoreImportedSettings] — that merge,
 * not this parse, is what guarantees the excluded fields are preserved rather than reset to
 * [AppSettings]'s bare defaults.
 */
object CommanderExportHandler {

    private val gson = Gson()

    /**
     * Fields deliberately excluded from [buildExportJson] unless [includeSecrets] is set:
     *  - Secrets: [AppSettings.apiKey], [AppSettings.geminiApiKey], [AppSettings.picovoiceAccessKey],
     *    [AppSettings.searchProviderApiKeys] (the last one isn't even carried on [settings] — see
     *    [searchProviderApiKeys] param — since it lives entirely in encrypted prefs, never in the
     *    DataStore-backed settings flow).
     * Always excluded, regardless of [includeSecrets]:
     *  - Raw local filesystem paths, meaningless on another device/after reinstall:
     *    [AppSettings.wakeWordModelPath], [AppSettings.customModelPaths].
     *  - Pure caches/device-capability probe results, all auto-regenerated at runtime:
     *    [AppSettings.modelsJsonCache], [AppSettings.appCacheJson], [AppSettings.downloadedModelIds],
     *    [AppSettings.vulkanIncompatible], [AppSettings.vulkanProbeDone],
     *    [AppSettings.vulkanRuntimeAttempt], [AppSettings.vulkanRuntimeVerified],
     *    [AppSettings.geminiIncompatible].
     *  - [AppSettings.wakeWordProfileJson]: a trained voice-print blob, out of scope for v1.
     * Everything else (language, engine choices, wake word tuning, TTS, app aliases, domain-app
     * selections, etc.) round-trips.
     */
    fun buildExportJson(
        settings: AppSettings,
        includeSecrets: Boolean = false,
        searchProviderApiKeys: Map<String, String> = emptyMap()
    ): String {
        val portable = settings.copy(
            apiKey = if (includeSecrets) settings.apiKey else null,
            geminiApiKey = if (includeSecrets) settings.geminiApiKey else null,
            picovoiceAccessKey = if (includeSecrets) settings.picovoiceAccessKey else null,
            searchProviderApiKeys = if (includeSecrets) searchProviderApiKeys else emptyMap(),
            wakeWordModelPath = null,
            customModelPaths = emptyMap(),
            modelsJsonCache = null,
            appCacheJson = null,
            downloadedModelIds = emptySet(),
            vulkanIncompatible = false,
            vulkanProbeDone = false,
            vulkanRuntimeAttempt = false,
            vulkanRuntimeVerified = false,
            geminiIncompatible = false,
            wakeWordProfileJson = null
        )
        return gson.toJson(portable)
    }

    /** Returns `null` if [json] isn't valid [AppSettings] JSON (e.g. a corrupt/foreign import file). */
    fun parsePortableSettings(json: String): AppSettings? = try {
        gson.fromJson(json, AppSettings::class.java)
    } catch (e: JsonSyntaxException) {
        null
    }
}
