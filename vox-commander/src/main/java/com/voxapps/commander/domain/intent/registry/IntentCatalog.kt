package com.voxapps.commander.domain.intent.registry

import android.content.Context
import com.voxapps.services.RemoteSchema
import com.google.gson.annotations.SerializedName
import com.voxapps.commander.data.preferences.SettingsRepository
import com.voxapps.commander.domain.intent.taxonomy.IntentTaxonomy

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

    private val schema = RemoteSchema(
        fileName = "intents.json",
        type = IntentsSchema::class.java,
        usable = { it.intents.isNotEmpty() },
        tag = TAG
    )

    fun init(context: Context) = schema.init(context)


    // ---- Public API ---------------------------------------------------------

    /** All intent definitions. Falls back to a compact hardcoded seed if the JSON never loaded. */
    fun getAll(): List<IntentDef> = schema.value?.intents?.takeIf { it.isNotEmpty() } ?: FALLBACK_SEED

    /** Maps a templateAction (navigate/search/send) to its domain, per the JSON (or the seed). */
    fun domainFor(templateAction: String): String? {
        val map = schema.value?.templateActionDomains?.takeIf { it.isNotEmpty() } ?: FALLBACK_DOMAINS
        return map[templateAction]
    }

    val isInitialized: Boolean
        get() = schema.value?.intents?.isNotEmpty() == true

    // ---- NLU taxonomy (domains/actions vocabulary) — JSON primary, compact seed fallback,
    //      PLUS domains/actions contributed dynamically by discovered Vox satellites (:core:ipc
    //      contract). Satellite verticals are NOT defined in intents.json — they self-register. ----

    fun taxonomyDomains(): List<String> {
        val base = schema.value?.taxonomy?.domains?.takeIf { it.isNotEmpty() } ?: FALLBACK_TAXONOMY.domains
        return (base + com.voxapps.commander.domain.integration.VoxSatelliteRegistry.domains()).distinct()
    }

    fun taxonomyActions(): List<String> {
        val base = schema.value?.taxonomy?.actions?.takeIf { it.isNotEmpty() } ?: FALLBACK_TAXONOMY.actions
        val satellite = com.voxapps.commander.domain.integration.VoxSatelliteRegistry.apps.value
            .flatMap { it.actions }
        return (base + satellite).distinct()
    }

    /** Actions for a domain, or null if neither the JSON, the seed, nor a satellite knows it. */
    fun taxonomyActionsForDomain(domain: String): List<String>? {
        val satellite = com.voxapps.commander.domain.integration.VoxSatelliteRegistry.actionsFor(domain)
        if (satellite.isNotEmpty()) return satellite
        return schema.value?.taxonomy?.actionsByDomain?.get(domain) ?: FALLBACK_TAXONOMY.actionsByDomain[domain]
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
