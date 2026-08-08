package com.voxapps.commander.receiver

import com.voxapps.backup.VoxSettingsRoundTrip
import com.voxapps.commander.data.preferences.AppSettings
import com.voxapps.commander.data.preferences.Credentials
import com.voxapps.commander.domain.intent.model.FastMapRule
import com.voxapps.commander.utils.Strings

/**
 * Vox Hub's export/import for Commander's own settings and FastMap rules, extracted from the
 * BroadcastReceiver so it's unit-testable without Android. (Unlike vox-notes/vox-expenses, which
 * keep secrets in a completely separate store) [AppSettings] mixes real secrets in with ordinary
 * preferences, so they're explicitly stripped in [buildExportJson] rather than relied on to
 * already be absent. [parsePortableSettings] is the import-side mirror: parses an exported blob
 * back into an [AppSettings] (never containing secrets/paths/caches to begin with, since export
 * never wrote them), which the caller then merges into the current on-device settings via
 * [com.voxapps.commander.data.preferences.SettingsRepository.restoreImportedSettings] — that merge,
 * not this parse, is what guarantees the excluded fields are preserved rather than reset to
 * [AppSettings]'s bare defaults.
 *
 * [buildFastMapRulesJson]/[parseFastMapRules] are the FastMap Rules Manager's own round-trip —
 * [FastMapRule] is a flat data class with no secrets/paths, so it serializes via plain Gson
 * reflection (no hand-maintained field list to drift out of sync, same reasoning as
 * [buildExportJson] itself). Shared verbatim by both Hub's whole-device backup (via
 * [com.voxapps.commander.receiver.VoxCommandReceiver]) and the standalone "Import/Export Rules
 * JSON" file picker in the Rules Manager screen — one schema serves both call sites.
 */
object CommanderExportHandler {

    /** The models.json key for Porcupine, whose licence key is a credential like any other. A
     *  literal because this object is deliberately free of Android and service-layer imports. */
    private const val PORCUPINE_ENGINE_KEY = "wake_porcupine"

    /**
     * Fields deliberately excluded from [buildExportJson] unless [includeSecrets] is set:
     *  - Secrets: [AppSettings.engineApiKeys] and the three single-key fields it replaced
     *    ([AppSettings.apiKey], [AppSettings.geminiApiKey], [AppSettings.picovoiceAccessKey]), plus
     *    [AppSettings.searchProviderApiKeys]. None of the encrypted ones are carried on [settings]
     *    — they live in the encrypted store and reach this function through [credentials] and
     *    [searchProviderApiKeys], never through the DataStore-backed settings flow. The
     *    [AppSettings] fields exist so that a backup can carry them and [SettingsRepository
     *    .restoreImportedSettings] can put them back; they are transport, not state.
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
        searchProviderApiKeys: Map<String, String> = emptyMap(),
        // Deliberately has no default. A caller that forgot it would export a backup whose secrets
        // are silently empty — indistinguishable from a backup made without them, and only
        // discovered on the restore that was supposed to bring the keys back.
        credentials: Credentials
    ): String {
        val portable = settings.copy(
            engineApiKeys = if (includeSecrets) credentials.byEngine else emptyMap(),
            // Written alongside the map so a backup taken here still restores on a build that
            // predates per-engine credentials. Costs three fields and removes a one-way door.
            apiKey = if (includeSecrets) credentials.forEngine(Strings.AiProcessors.OPENAI) else null,
            geminiApiKey = if (includeSecrets) credentials.forEngine(Strings.AiProcessors.GEMINI_CLOUD) else null,
            picovoiceAccessKey = if (includeSecrets) credentials.forEngine(PORCUPINE_ENGINE_KEY) else null,
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
        return VoxSettingsRoundTrip.toJson(portable)
    }

    /**
     * Returns `null` if [json] isn't valid [AppSettings] JSON (e.g. a corrupt/foreign import file).
     *
     * Gson deserializes via reflection, not Kotlin's constructor — a JSON field that's absent or
     * explicitly `null` leaves the corresponding Kotlin property genuinely `null` at runtime,
     * bypassing [AppSettings]' non-null default entirely (confirmed the hard way: an older/foreign
     * export missing `searchProviderApiKeys` crashed [SettingsRepositoryImpl.restoreImportedSettings]
     * with a NullPointerException iterating a "non-null" map). Every collection-typed field is
     * coalesced back to its safe empty default here, once, so no caller has to re-derive this.
     */
    fun parsePortableSettings(json: String): AppSettings? =
        VoxSettingsRoundTrip.parseOrNull(json, AppSettings::class.java) { parsed ->
            parsed.copy(
                engineModelSelections = parsed.engineModelSelections ?: emptyMap(),
                downloadedModelIds = parsed.downloadedModelIds ?: emptySet(),
                customModelPaths = parsed.customModelPaths ?: emptyMap(),
                defaultAppPackages = parsed.defaultAppPackages ?: emptyMap(),
                domainAppPackages = parsed.domainAppPackages ?: emptyMap(),
                customDomains = parsed.customDomains ?: emptyList(),
                domainAppFilters = parsed.domainAppFilters ?: emptyMap(),
                searchProviderApiKeys = parsed.searchProviderApiKeys ?: emptyMap(),
                // Gson leaves an absent map null behind a non-null type — every collection here is
                // coalesced once for exactly that reason, and a new one is no exception.
                engineApiKeys = parsed.engineApiKeys ?: emptyMap(),
                returnAfterActionApps = parsed.returnAfterActionApps ?: emptyList(),
                appAliasRules = parsed.appAliasRules ?: emptyList(),
                locationCacheTtl = parsed.locationCacheTtl ?: "ONE_DAY"
            )
        }

    fun buildFastMapRulesJson(rules: List<FastMapRule>): String = VoxSettingsRoundTrip.toJson(rules)

    /** Returns `null` if [json] isn't a valid FastMapRule array (e.g. a corrupt/foreign import file) —
     *  same fail-safe contract as [parsePortableSettings], so callers never have to distinguish
     *  "empty file" from "unparseable file". */
    fun parseFastMapRules(json: String): List<FastMapRule>? =
        VoxSettingsRoundTrip.parseOrNull(json, Array<FastMapRule>::class.java)?.toList()
}
