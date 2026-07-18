package com.voxapps.commander.domain.intent.registry

import android.content.Context
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.voxapps.commander.utils.Logger

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
    val auth: AuthDef? = null,
    @SerializedName("base_url") val baseUrl: String = "",
    val capabilities: Map<String, CapabilitySlot> = emptyMap()
)

data class AuthDef(
    val type: String = "", // oauth2_pkce | oauth2_authorization_code
    @SerializedName("authorize_url") val authorizeUrl: String = "",
    @SerializedName("token_url") val tokenUrl: String = "",
    @SerializedName("redirect_uri") val redirectUri: String = "",
    val scopes: String = ""
)

data class CapabilitySlot(
    val type: String = "", // api_call | api_sequence | deep_link
    val method: String? = null,
    val path: String? = null,
    val body: String? = null,
    @SerializedName("response_path") val responsePath: String? = null,
    @SerializedName("uri_template") val uriTemplate: String? = null,
    val steps: List<SequenceStep>? = null
)

/**
 * One step of an `api_sequence` slot. Either a `capability` reference (invoke another slot and
 * store its result) or a `type`-dispatched step (`api_call` / `device_select`).
 *
 * `optional`/`stop_on_success`/`retry`/`delay_before_ms` exist specifically to let a purely
 * declarative sequence reproduce real, already-shipped retry/fallback behavior (Spotify's
 * device-transfer dance ignores the transfer call's own result, waits before the first play
 * attempt, and retries once after a longer delay before falling back to a device-less play call)
 * without hardcoding that behavior into the executor.
 */
data class SequenceStep(
    val capability: String? = null,
    val type: String? = null, // api_call | device_select
    val method: String? = null,
    val path: String? = null,
    val body: String? = null,
    @SerializedName("response_path") val responsePath: String? = null,
    val from: String? = null,
    val prefer: List<PreferRule>? = null,
    @SerializedName("id_field") val idField: String? = null,
    val `as`: String? = null,
    val optional: Boolean = false,
    @SerializedName("stop_on_success") val stopOnSuccess: Boolean = false,
    @SerializedName("delay_before_ms") val delayBeforeMs: Long = 0,
    val retry: RetryDef? = null
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
    private const val ASSET_FILE_NAME = "api_integrations.json"

    private val gson = Gson()
    private var cached: List<ApiIntegration> = emptyList()

    fun init(context: Context) {
        cached = try {
            context.assets.open(ASSET_FILE_NAME).use { input ->
                val text = input.readBytes().decodeToString()
                val schema = gson.fromJson(text, ApiIntegrationsSchema::class.java)
                Logger.log("Loaded api_integrations.json: ${schema?.integrations?.size ?: 0} integrations", TAG)
                schema?.integrations ?: emptyList()
            }
        } catch (e: Exception) {
            Logger.log("Failed to load api_integrations.json: ${e.message}", TAG)
            emptyList()
        }
    }

    fun forPackage(packageName: String): ApiIntegration? = cached.firstOrNull { it.packageName == packageName }
}
