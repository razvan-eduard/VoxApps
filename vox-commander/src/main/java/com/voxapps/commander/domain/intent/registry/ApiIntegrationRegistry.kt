package com.voxapps.commander.domain.intent.registry

import android.content.Context
import com.google.gson.annotations.SerializedName
import com.voxapps.commander.data.preferences.SettingsRepository
import com.voxapps.commander.data.remote.RemoteSchema

/**
 * Declarative external-app-API integration schema. One entry replaces what used to require three
 * hand-written Kotlin classes per service (OAuth manager, REST client, playback orchestrator) —
 * see [com.voxapps.commander.service.OAuth2Manager] and
 * [com.voxapps.commander.service.DeclarativeApiExecutor] for the generic engines this data feeds.
 *
 * Every service fills in only the capability slots it actually supports; an absent slot means
 * "no override, fall back to the existing generic mechanism" (MEDIA_PLAY_FROM_SEARCH / NewPipe /
 * intents.json / MediaSessionListenerService) — mirrors [com.voxapps.commander.service.ITtsEngine]'s
 * "fixed interface, silent no-op for unsupported parts" pattern already trusted in this codebase.
 */
data class ApiIntegrationsSchema(
    @SerializedName("schema_version") val schemaVersion: Int = 1,
    val integrations: List<ApiIntegration> = emptyList()
)

data class ApiIntegration(
    val id: String = "",
    val label: String = "",
    @SerializedName("package_name") val packageName: String = "",
    val auth: com.voxapps.commander.domain.service.AuthDeclaration? = null,
    /** Where the service lives. `base_url` is the older spelling and is still read, since a copy of
     *  this file may predate the shared vocabulary. */
    val endpoint: String? = null,
    @SerializedName("base_url") val legacyBaseUrl: String? = null,
    /** Path that proves the credential still works — `/me` for Spotify. Relative to [endpoint]. */
    @SerializedName("probe_url") val probeUrl: String? = null,

    /** Which of the icons the app carries represents this service, and the colour it is known by.
     *  A closed set, chosen the same way an auth style is: a declaration selects among what the app
     *  has compiled, it does not ship an image. Without them a card is a generic link on a themed
     *  circle, which is what every integration looked like after the screen stopped being written
     *  by hand for one service. */
    val icon: String? = null,
    @SerializedName("accent_color") val accentColor: String? = null,
    val capabilities: Map<String, CapabilitySlot> = emptyMap()
) {
    /** The endpoint under either spelling. */
    val serviceUrl: String get() = (endpoint ?: legacyBaseUrl).orEmpty()
}

data class CapabilitySlot(
    val type: String = "", // api_call | api_sequence | deep_link
    val method: String? = null,
    val path: String? = null,
    val body: String? = null,
    @SerializedName("response_path") val responsePath: String? = null,
    @SerializedName("uri_template") val uriTemplate: String? = null,
    val steps: List<SequenceStep>? = null,
    // Extra named extractions from the same response, alongside the primary response_path — e.g.
    // search_track's primary result is the track URI, but play_track's queueing step also needs
    // that track's artist id from the SAME search response, without a second HTTP call.
    val extract: Map<String, String>? = null
)

/**
 * One step of an `api_sequence` slot. Either a `capability` reference (invoke another slot and
 * store its result) or a `type`-dispatched step (`api_call` / `device_select` / `queue_array`).
 *
 * `optional`/`group`/`retry`/`delay_before_ms` exist specifically to let a purely declarative
 * sequence reproduce real, already-shipped retry/fallback behavior. Steps sharing the same
 * `group` are tried in order as alternatives — the first to succeed satisfies the whole group
 * (later members are skipped, but the sequence continues to steps *after* the group); the group
 * only aborts the sequence if its LAST member fails and isn't `optional`. E.g. Spotify's
 * device-transfer dance ignores the transfer call's own result (`optional: true`), then tries
 * play-on-device (with a retry) and play-without-a-device as two `group: "play"` alternatives —
 * whichever succeeds lets the sequence continue on to queue a few more tracks afterward.
 *
 * `queue_array` fields (`from`/`uriField`/`limit`/`skip`/`queuePath`) implement "queue a few more
 * items from an array a prior step fetched" — e.g. play_track queueing an artist's other top
 * tracks after the requested one starts playing, since Spotify's real Recommendations endpoint is
 * deprecated (Nov 2024) and unavailable to new apps; top-tracks is the honest, still-live substitute.
 */
data class SequenceStep(
    val capability: String? = null,
    val type: String? = null, // api_call | device_select | queue_array
    val method: String? = null,
    val path: String? = null,
    val body: String? = null,
    @SerializedName("response_path") val responsePath: String? = null,
    val from: String? = null,
    val prefer: List<PreferRule>? = null,
    @SerializedName("id_field") val idField: String? = null,
    val `as`: String? = null,
    val optional: Boolean = false,
    val group: String? = null,
    @SerializedName("delay_before_ms") val delayBeforeMs: Long = 0,
    val retry: RetryDef? = null,
    // queue_array only:
    @SerializedName("uri_field") val uriField: String? = null,
    val limit: Int = 0,
    val skip: String? = null,
    @SerializedName("queue_path") val queuePath: String? = null
)

data class RetryDef(val times: Int = 0, @SerializedName("delay_ms") val delayMs: Long = 0)

data class PreferRule(val field: String = "", val equals: Any? = null)

/**
 * Loads `api_integrations.json` from assets (repo-root single source of truth, copied at build
 * time by the `copyApiIntegrationsJson` Gradle task — same convention as [IntentCatalog]'s
 * `intents.json`, minus the remote-hot-reload layer, which isn't needed until a service ships
 * that requires updating without an app release).
 */
object ApiIntegrationRegistry {

    private const val TAG = "ApiIntegrationRegistry"

    /**
     * The last schema that was read from assets and nowhere else.
     *
     * It is also the one whose contents change without the app changing: an authorize URL moves, a
     * scope is added, a service starts requiring a client id. Those were app releases, while the
     * engines, the search providers and the media backends could all be corrected from the
     * repository — for no reason other than which loader this file happened to be written against.
     */
    private val schema = RemoteSchema(
        fileName = "api_integrations.json",
        type = ApiIntegrationsSchema::class.java,
        versionOf = { it.schemaVersion },
        usable = { it.integrations.isNotEmpty() },
        tag = TAG
    )

    fun init(context: Context) = schema.init(context)

    suspend fun fetchRemote(repo: SettingsRepository, force: Boolean = false): Boolean =
        schema.fetchRemote(repo, force)

    private val cached: List<ApiIntegration> get() = schema.value?.integrations ?: emptyList()

    fun forPackage(packageName: String): ApiIntegration? = cached.firstOrNull { it.packageName == packageName }

    /**
     * Every declared integration.
     *
     * Its absence is why the integrations screen could only ever show Spotify: the screen asked for
     * one integration by package name and drew a card written for it by hand, so a second entry in
     * `api_integrations.json` rendered nowhere.
     */
    fun all(): List<ApiIntegration> = cached

    fun byId(serviceId: String): ApiIntegration? = cached.firstOrNull { it.id == serviceId }
}
