package com.voxapps.commander.receiver

import com.google.gson.Gson
import com.voxapps.commander.data.preferences.AppSettings

/**
 * Vox Hub's export for Commander's own settings, extracted from the BroadcastReceiver so it's
 * unit-testable without Android. Commander has no notes/expenses-style records of its own — this is
 * settings-only, and (unlike vox-notes/vox-expenses, which keep secrets in a completely separate
 * store) [AppSettings] mixes real secrets in with ordinary preferences, so they're explicitly
 * stripped here rather than relied on to already be absent. Export-only for now — no import op —
 * since applying someone else's/an old device's settings snapshot wholesale (aliases, domain-app
 * choices, wake word tuning) is a much larger correctness surface than notes/expenses' plain
 * data-restore, and wasn't asked for.
 */
object CommanderExportHandler {

    private val gson = Gson()

    /**
     * Fields deliberately excluded from [buildExportJson]:
     *  - Secrets: [AppSettings.apiKey], [AppSettings.geminiApiKey], [AppSettings.picovoiceAccessKey],
     *    [AppSettings.searchProviderApiKeys].
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
    fun buildExportJson(settings: AppSettings): String {
        val portable = settings.copy(
            apiKey = null,
            geminiApiKey = null,
            picovoiceAccessKey = null,
            searchProviderApiKeys = emptyMap(),
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
}
