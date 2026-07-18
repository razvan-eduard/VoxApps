package com.voxapps.commander.domain.intent.handler

import android.content.Context
import com.voxapps.commander.domain.intent.registry.ApiIntegration
import com.voxapps.commander.domain.intent.registry.ApiIntegrationRegistry
import com.voxapps.commander.service.DeclarativeApiExecutor
import com.voxapps.commander.service.OAuth2Manager
import com.voxapps.commander.service.OAuthConfig
import com.voxapps.commander.service.SpotifyRemoteManager
import com.voxapps.commander.utils.Logger

/**
 * Playback helpers used by [AudioIntentHandler]'s search-based `play` fallback chain. Renamed
 * from the original Spotify-only `SpotifyPlaybackHelper` — [pipedPlayDirect] was never actually
 * Spotify-specific (it's the YouTube/Piped search-and-play path), and [tryApiIntegrationPlaySearch]
 * now routes through the generic declarative API engine ([ApiIntegrationRegistry] +
 * [DeclarativeApiExecutor] + [OAuth2Manager]) instead of calling Spotify's Web API directly, so
 * any future service with an `api_integrations.json` entry gets this for free.
 */
object AudioPlaybackHelpers {

    private const val TAG = "AudioPlaybackHelpers"

    /**
     * Tries to search-and-play [query] via [pkg]'s declarative API integration (if one is loaded
     * and authorized). [mediaType] ("track"/"album"/"artist", from [com.voxapps.commander.domain.intent.model.NluIntent.mediaType])
     * selects the `play_track`/`play_album`/`play_artist` capability slot; falls back to
     * `play_track` if the service has no dedicated slot for the requested media type (a partial
     * match beats failing outright). Returns false — falling through to the generic chain — for
     * any app without a matching, authorized integration or with neither slot defined.
     */
    fun tryApiIntegrationPlaySearch(context: Context, pkg: String, query: String, mediaType: String? = null, waitMs: Long = 0): Boolean {
        val integration = ApiIntegrationRegistry.forPackage(pkg) ?: return false
        val auth = integration.auth ?: return false
        val capabilityName = "play_${mediaType ?: "track"}".takeIf { integration.capabilities.containsKey(it) }
            ?: "play_track".takeIf { integration.capabilities.containsKey(it) }
            ?: return false
        if (!OAuth2Manager.isAuthorized(integration.id)) return false

        val clientId = clientIdFor(integration.id) ?: return false
        val config = oauthConfigFor(integration, auth)
        val token = OAuth2Manager.getValidAccessToken(config, clientId) ?: run {
            Logger.log("${integration.id}: no valid access token", TAG)
            return false
        }

        if (waitMs > 0) {
            val launchIntent = context.packageManager.getLaunchIntentForPackage(pkg)
            if (launchIntent != null) {
                launchIntent.flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                try { context.startActivity(launchIntent) } catch (_: Exception) {}
                Thread.sleep(waitMs)
            }
        }

        val result = DeclarativeApiExecutor.run(integration, capabilityName, token, query)
        if (result != null) {
            Logger.log("playSearch via ${integration.id}.$capabilityName declarative API succeeded", TAG)
            return true
        }
        Logger.log("${integration.id}.$capabilityName declarative API failed, falling back to intent", TAG)
        return false
    }

    private fun clientIdFor(serviceId: String): String? = when (serviceId) {
        // Client id storage stays per-service-hardcoded here until a second OAuth-based
        // integration ships — generalizing SettingsRepository's client-id storage is deferred,
        // real follow-on work, not required for this pass (Spotify-only migration).
        "spotify" -> SpotifyRemoteManager.getClientId()
        else -> null
    }

    private fun oauthConfigFor(integration: ApiIntegration, auth: com.voxapps.commander.domain.intent.registry.AuthDef): OAuthConfig {
        return OAuthConfig(
            serviceId = integration.id,
            authorizeUrl = auth.authorizeUrl,
            tokenUrl = auth.tokenUrl,
            redirectUri = auth.redirectUri,
            scopes = auth.scopes,
            usePkce = auth.type == "oauth2_pkce"
        )
    }

    fun pipedPlayDirect(context: Context, pkg: String?, query: String): Boolean {
        return try {
            kotlinx.coroutines.runBlocking {
                PipedSearchHelper.searchAndPlay(context, query, pkg)
            }
        } catch (e: Exception) {
            Logger.log("Piped play direct failed: ${e.message}", TAG)
            false
        }
    }
}
