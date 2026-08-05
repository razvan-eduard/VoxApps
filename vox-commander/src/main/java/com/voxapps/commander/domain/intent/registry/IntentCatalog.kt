package com.voxapps.commander.domain.intent.registry

import android.content.Context
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.voxapps.commander.data.preferences.SettingsRepository
import com.voxapps.commander.domain.intent.taxonomy.IntentTaxonomy
import com.voxapps.logging.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URL

/**
 * Data-driven catalog of standard Android intents we probe for per app.
 *
 * Mirrors [com.voxapps.commander.domain.search.SearchProviderRegistry] and
 * [com.voxapps.commander.data.remote.RemoteModelRegistry]: the source of truth is
 * `intents.json` at the repo root, copied into assets by the `copyIntentsJson`
 * Gradle task, loaded from filesDir at runtime, and hot-reloadable via the same
 * remote-refresh mechanism (schema-versioned, no downgrade).
 *
 * The catalog is pure data (`action` + probe/template metadata). The behavioral
 * handlers (Spotify/NewPipe/Piped chains, settings toggles) stay in code — they
 * are only *fed* by this catalog via [AppRegistry.KnownIntents.probeMetadata].
 */
object IntentCatalog {

    private const val TAG = "IntentCatalog"
    private const val LOCAL_FILE_NAME = "intents.json"

    private val gson = Gson()

    private var appContext: Context? = null
    private var cachedSchema: IntentsSchema? = null

    // ---- Gson models --------------------------------------------------------

    data class IntentsSchema(
        @SerializedName("schema_version") val schemaVersion: Int = 1,
        @SerializedName("template_action_domains") val templateActionDomains: Map<String, String> = emptyMap(),
        val taxonomy: TaxonomyDef? = null,
        val intents: List<IntentDef> = emptyList()
    )

    /** The NLU vocabulary (domains + actions) fed to the prompt and the Rules UI. */
    data class TaxonomyDef(
        val domains: List<String> = emptyList(),
        val actions: List<String> = emptyList(),
        @SerializedName("actions_by_domain") val actionsByDomain: Map<String, List<String>> = emptyMap()
    )

    data class IntentDef(
        val action: String = "",
        @SerializedName("probe_uri") val probeUri: String? = null,
        @SerializedName("uri_template") val uriTemplate: String? = null,
        val label: String = "",
        @SerializedName("template_action") val templateAction: String? = null,
        @SerializedName("requires_query") val requiresQuery: Boolean = true,
        @SerializedName("mime_type") val mimeType: String? = null
    )

    // ---- Lifecycle ----------------------------------------------------------

    fun init(context: Context) {
        appContext = context.applicationContext
        loadFromFilesDir()
    }

    suspend fun fetchRemote(repo: SettingsRepository, force: Boolean = false): Boolean =
        withContext(Dispatchers.IO) {
            if (!force && cachedSchema != null) return@withContext true

            val baseUrl = repo.getSettingsSnapshot().modelRepoBaseUrl
            val rawUrlBase = if (baseUrl.contains("github.com") && !baseUrl.contains("raw.githubusercontent.com")) {
                baseUrl.replace("github.com", "raw.githubusercontent.com").removeSuffix("/") + "/main/$LOCAL_FILE_NAME"
            } else {
                if (baseUrl.endsWith("/")) "${baseUrl}$LOCAL_FILE_NAME" else "$baseUrl/$LOCAL_FILE_NAME"
            }

            val rawUrl = "$rawUrlBase?t=${System.currentTimeMillis()}"
            Logger.log("Fetching remote intents catalog from: $rawUrl", TAG)

            return@withContext try {
                val jsonText = URL(rawUrl).readText()
                val schema = gson.fromJson(jsonText, IntentsSchema::class.java)
                if (schema != null && schema.intents.isNotEmpty()) {
                    // Never downgrade — if assets has higher schema_version, use assets
                    val assetVersion = getAssetSchemaVersion()
                    if (assetVersion > schema.schemaVersion) {
                        Logger.log("Remote schema_version=${schema.schemaVersion} < assets=$assetVersion — skipping remote (no downgrade)", TAG)
                        ensureLocalFile()
                        loadFromFilesDir()
                    } else {
                        saveLocalFile(jsonText)
                        cachedSchema = schema
                        Logger.log("Remote intents catalog parsed. ${schema.intents.size} intents.", TAG)
                    }
                    true
                } else {
                    Logger.log("Failed to parse remote intents catalog", TAG)
                    false
                }
            } catch (e: Exception) {
                Logger.log("Remote intents catalog fetch failed: ${e.message}. Falling back to assets.", TAG)
                ensureLocalFile()
                loadFromFilesDir()
                cachedSchema != null
            }
        }

    private fun getAssetSchemaVersion(): Int {
        val ctx = appContext ?: return 0
        return try {
            ctx.assets.open(LOCAL_FILE_NAME).use { input ->
                val text = input.readBytes().decodeToString()
                gson.fromJson(text, IntentsSchema::class.java)?.schemaVersion ?: 0
            }
        } catch (e: Exception) { 0 }
    }

    /**
     * Copies intents.json from assets to filesDir if local is missing or assets
     * has a newer schema_version. Called as fallback when repo download fails.
     */
    private fun ensureLocalFile() {
        val ctx = appContext ?: return
        val localFile = java.io.File(ctx.filesDir, LOCAL_FILE_NAME)

        val assetText = try {
            ctx.assets.open(LOCAL_FILE_NAME).use { it.readBytes().decodeToString() }
        } catch (e: Exception) {
            Logger.log("Failed to read intents.json from assets: ${e.message}", TAG)
            return
        }

        val localVersion = if (localFile.exists()) {
            try {
                gson.fromJson(localFile.readText(), IntentsSchema::class.java)?.schemaVersion ?: 0
            } catch (e: Exception) { 0 }
        } else 0

        val assetVersion = try {
            gson.fromJson(assetText, IntentsSchema::class.java)?.schemaVersion ?: 0
        } catch (e: Exception) { 0 }

        if (!localFile.exists() || assetVersion > localVersion) {
            try {
                localFile.writeText(assetText)
                Logger.log("Copied intents.json from assets to filesDir (asset v$assetVersion > local v$localVersion)", TAG)
            } catch (e: Exception) {
                Logger.log("Failed to copy intents.json from assets: ${e.message}", TAG)
            }
        }
    }

    private fun loadFromFilesDir() {
        val ctx = appContext ?: return
        val localFile = java.io.File(ctx.filesDir, LOCAL_FILE_NAME)
        if (!localFile.exists()) {
            ensureLocalFile()
            if (!localFile.exists()) return
        }

        try {
            val jsonText = localFile.readText()
            val schema = gson.fromJson(jsonText, IntentsSchema::class.java)
            if (schema != null && schema.intents.isNotEmpty()) {
                cachedSchema = schema
                Logger.log("Loaded intents.json from filesDir. ${schema.intents.size} intents.", TAG)
            } else {
                Logger.log("Local intents.json has empty intents. Overwriting from assets.", TAG)
                throw com.google.gson.JsonParseException("Empty intents — likely outdated schema")
            }
        } catch (e: Exception) {
            Logger.log("Failed to parse local intents.json: ${e.message}. Recovering from assets.", TAG)
            try {
                ctx.assets.open(LOCAL_FILE_NAME).use { input ->
                    localFile.outputStream().use { output -> input.copyTo(output) }
                }
                val freshSchema = gson.fromJson(localFile.readText(), IntentsSchema::class.java)
                if (freshSchema != null && freshSchema.intents.isNotEmpty()) {
                    cachedSchema = freshSchema
                    Logger.log("Recovered intents.json from assets. ${freshSchema.intents.size} intents.", TAG)
                } else {
                    Logger.log("Assets intents.json also empty!", TAG)
                }
            } catch (e2: Exception) {
                Logger.log("Failed to recover intents.json from assets: ${e2.message}", TAG)
            }
        }
    }

    private fun saveLocalFile(jsonText: String) {
        val ctx = appContext ?: return
        try {
            java.io.File(ctx.filesDir, LOCAL_FILE_NAME).writeText(jsonText)
            Logger.log("Saved updated intents.json to filesDir", TAG)
        } catch (e: Exception) {
            Logger.log("Failed to save intents.json: ${e.message}", TAG)
        }
    }

    // ---- Public API ---------------------------------------------------------

    /** All intent definitions. Falls back to a compact hardcoded seed if the JSON never loaded. */
    fun getAll(): List<IntentDef> = cachedSchema?.intents?.takeIf { it.isNotEmpty() } ?: FALLBACK_SEED

    /** Maps a templateAction (navigate/search/send) to its domain, per the JSON (or the seed). */
    fun domainFor(templateAction: String): String? {
        val map = cachedSchema?.templateActionDomains?.takeIf { it.isNotEmpty() } ?: FALLBACK_DOMAINS
        return map[templateAction]
    }

    val isInitialized: Boolean
        get() = cachedSchema != null && cachedSchema!!.intents.isNotEmpty()

    // ---- NLU taxonomy (domains/actions vocabulary) — JSON primary, compact seed fallback,
    //      PLUS domains/actions contributed dynamically by discovered Vox satellites (:core:ipc
    //      contract). Satellite verticals are NOT defined in intents.json — they self-register. ----

    fun taxonomyDomains(): List<String> {
        val base = cachedSchema?.taxonomy?.domains?.takeIf { it.isNotEmpty() } ?: FALLBACK_TAXONOMY.domains
        return (base + com.voxapps.commander.domain.integration.VoxSatelliteRegistry.domains()).distinct()
    }

    fun taxonomyActions(): List<String> {
        val base = cachedSchema?.taxonomy?.actions?.takeIf { it.isNotEmpty() } ?: FALLBACK_TAXONOMY.actions
        val satellite = com.voxapps.commander.domain.integration.VoxSatelliteRegistry.apps.value
            .flatMap { it.actions }
        return (base + satellite).distinct()
    }

    /** Actions for a domain, or null if neither the JSON, the seed, nor a satellite knows it. */
    fun taxonomyActionsForDomain(domain: String): List<String>? {
        val satellite = com.voxapps.commander.domain.integration.VoxSatelliteRegistry.actionsFor(domain)
        if (satellite.isNotEmpty()) return satellite
        return cachedSchema?.taxonomy?.actionsByDomain?.get(domain) ?: FALLBACK_TAXONOMY.actionsByDomain[domain]
    }

    // ---- Last-resort fallback (only used if assets read fails — near-impossible) ----
    // Covers the core routing intents (those carrying a templateAction → domain/uriTemplate).
    // The extended catalog (settings toggles, camera, alarms) lives in intents.json.

    private val FALLBACK_DOMAINS: Map<String, String> = mapOf(
        AppRegistry.TemplateActions.NAVIGATE to IntentTaxonomy.Domains.MAPS,
        AppRegistry.TemplateActions.SEARCH to IntentTaxonomy.Domains.AUDIO,
        AppRegistry.TemplateActions.SEND to IntentTaxonomy.Domains.MESSAGING
    )

    // NLU taxonomy seed — the single fallback for the domain/action vocabulary when intents.json
    // isn't loaded. The live values come from intents.json `taxonomy`.
    private val FALLBACK_TAXONOMY: TaxonomyDef = TaxonomyDef(
        domains = listOf("audio", "settings", "maps", "messaging", "system", "home", "search"),
        actions = listOf("play", "pause", "stop", "next", "prev", "volume_up", "volume_down",
            "wifi_toggle", "bluetooth_toggle", "gps_toggle",
            "flashlight_on", "flashlight_off", "flashlight_toggle", "airplane_mode_toggle",
            "dnd_on", "dnd_off", "dnd_toggle", "nfc_toggle",
            "auto_rotate_on", "auto_rotate_off", "auto_rotate_toggle",
            "silent_mode_on", "silent_mode_off", "silent_mode_toggle",
            "navigate", "send", "query", "launch"),
        actionsByDomain = mapOf(
            "audio" to listOf("play", "pause", "stop", "next", "prev"),
            "settings" to listOf("volume_up", "volume_down", "wifi_toggle", "bluetooth_toggle", "gps_toggle",
                "flashlight_on", "flashlight_off", "flashlight_toggle", "airplane_mode_toggle",
                "dnd_on", "dnd_off", "dnd_toggle", "nfc_toggle",
                "auto_rotate_on", "auto_rotate_off", "auto_rotate_toggle",
                "silent_mode_on", "silent_mode_off", "silent_mode_toggle"),
            "maps" to listOf("navigate"),
            "messaging" to listOf("send"),
            "search" to listOf("query"),
            "system" to listOf("toggle", "status"),
            "home" to listOf("toggle", "status")
        )
    )

    private val FALLBACK_SEED: List<IntentDef> = listOf(
        IntentDef("android.media.action.MEDIA_PLAY_FROM_SEARCH", null, null, "Play from search (Media/Music)", AppRegistry.TemplateActions.SEARCH),
        IntentDef("android.intent.action.SEARCH", null, null, "In-App Search", AppRegistry.TemplateActions.SEARCH),
        IntentDef("android.intent.action.VIEW", "geo:0,0?q=test", "geo:0,0?q={destination}", "View / Search Location (geo:)", AppRegistry.TemplateActions.NAVIGATE),
        IntentDef("android.intent.action.VIEW", "google.navigation:q=test", "google.navigation:q={destination}", "Turn-by-Turn Google Maps", AppRegistry.TemplateActions.NAVIGATE),
        IntentDef("android.intent.action.VIEW", "waze://?q=test&navigate=yes", "waze://?q={destination}&navigate=yes", "Turn-by-Turn Waze", AppRegistry.TemplateActions.NAVIGATE),
        IntentDef("android.intent.action.VIEW", "https://api.whatsapp.com/send?phone=40700000000", "https://api.whatsapp.com/send?phone={contact}", "WhatsApp Direct Message", AppRegistry.TemplateActions.SEND),
        IntentDef("android.intent.action.VIEW", "https://example.com", "{query}", "Open URL in Browser (http/https)", AppRegistry.TemplateActions.SEARCH),
        IntentDef("android.intent.action.VIEW", "https://www.youtube.com/watch?v=dQw4w9WgXcQ", "https://www.youtube.com/watch?v={query}", "Play YouTube Video (LibreTube)", AppRegistry.TemplateActions.SEARCH),
        IntentDef("android.intent.action.SEND", null, null, "Share Text / Link (General Share Sheet)", AppRegistry.TemplateActions.SEND, mimeType = "text/plain"),
        IntentDef("android.intent.action.WEB_SEARCH", null, null, "Web Search (Google Search)", AppRegistry.TemplateActions.SEARCH),
        IntentDef("android.intent.action.DIAL", "tel:0700000000", "tel:{contact}", "Open Dialer (tel:)"),
        IntentDef("android.intent.action.SENDTO", "smsto:0700000000", "smsto:{contact}", "Compose SMS (smsto:)"),
        IntentDef("android.intent.action.SENDTO", "mailto:test@example.com", "mailto:{contact}", "Compose Email (mailto:)")
    )
}
