package com.voxapps.commander.service

import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.browser.customtabs.CustomTabsIntent
import com.voxapps.commander.data.preferences.SettingsRepository
import com.voxapps.commander.utils.AppScope
import com.voxapps.logging.Logger
import com.voxapps.commander.utils.NetworkMonitor
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Per-service OAuth2 config, parsed from an [com.voxapps.commander.domain.intent.registry.AuthDef].
 * `usePkce` selects Authorization-Code-with-PKCE (no client_secret, e.g. Spotify) vs plain
 * Authorization-Code (wants a client_secret, e.g. Deezer's confidential-client flow).
 */
data class OAuthConfig(
    val serviceId: String,
    val authorizeUrl: String,
    val tokenUrl: String,
    val redirectUri: String,
    val scopes: String,
    val usePkce: Boolean = true,
    val clientSecret: String? = null
)

private class ServiceAuthState {
    var codeVerifier: String? = null
    var pendingClientId: String? = null
    var nonce: String? = null
    var callback: ((Boolean, String?) -> Unit)? = null
    var accessToken: String? = null
    var refreshToken: String? = null
    var tokenExpiry: Long = 0
    var isAuthorized: Boolean = false
    var cachedDeviceId: String? = null
}

/**
 * Generic OAuth2 Authorization-Code(+PKCE) client manager, parameterized per service instead of
 * hardcoded to one provider. Generalized from the original Spotify-only `SpotifyPkceManager` —
 * none of the verifier/challenge generation, Custom Tabs launch, redirect handling, token
 * exchange, or refresh logic was ever actually Spotify-specific.
 *
 * Every auth flow's `state` query parameter carries `<serviceId>:<nonce>` regardless of which
 * redirect URI catches it (a service's own dedicated host, e.g. Spotify's
 * `voxcommander://spotify/callback`, or the shared `SHARED_REDIRECT_URI` new services can use
 * with zero manifest changes). [handleRedirect] resolves the target service from `state` first —
 * this is what lets one manifest entry serve every future OAuth-based service, and the nonce
 * doubles as `state`'s standard CSRF protection.
 */
object OAuth2Manager {

    private const val TAG = "OAuth2Manager"
    const val SHARED_REDIRECT_URI = "voxcommander://oauth/callback"

    private val states = mutableMapOf<String, ServiceAuthState>()
    private val pendingConfigs = mutableMapOf<String, OAuthConfig>()
    private var settingsRepo: SettingsRepository? = null

    fun init(repo: SettingsRepository) {
        settingsRepo = repo
    }

    /** Loads persisted tokens/device id for [serviceId]. Call once per service at startup. */
    fun loadPersisted(serviceId: String) {
        val repo = settingsRepo ?: return
        val st = stateFor(serviceId)
        val token = repo.getServiceAccessTokenSync(serviceId)
        val refresh = repo.getServiceRefreshTokenSync(serviceId)
        val expiry = repo.getServiceTokenExpirySync(serviceId)

        if (token != null && refresh != null && expiry > System.currentTimeMillis()) {
            st.accessToken = token
            st.refreshToken = refresh
            st.tokenExpiry = expiry
            st.isAuthorized = true
            Logger.log("OAuth2Manager[$serviceId]: tokens loaded, expires in ${(expiry - System.currentTimeMillis()) / 1000}s", TAG)
        } else if (refresh != null) {
            st.refreshToken = refresh
            st.tokenExpiry = 0
            st.isAuthorized = true
            Logger.log("OAuth2Manager[$serviceId]: refresh token loaded, access token expired — will refresh on demand", TAG)
        }
        st.cachedDeviceId = repo.getServiceDeviceIdSync(serviceId)
    }

    fun isAuthorized(serviceId: String): Boolean = stateFor(serviceId).isAuthorized

    fun cachedDeviceId(serviceId: String): String? = stateFor(serviceId).cachedDeviceId

    fun setCachedDeviceId(serviceId: String, deviceId: String?) {
        stateFor(serviceId).cachedDeviceId = deviceId
        val repo = settingsRepo
        if (repo != null && deviceId != null) {
            AppScope.io.launch { repo.setServiceDeviceId(serviceId, deviceId) }
            Logger.log("OAuth2Manager[$serviceId]: device ID cached: ${deviceId.take(8)}...", TAG)
        }
    }

    /** Pre-registers a service's config so a shared-URI redirect can resolve it before [startAuthFlow] runs. */
    fun registerConfig(config: OAuthConfig) {
        pendingConfigs[config.serviceId] = config
    }

    fun startAuthFlow(context: Context, config: OAuthConfig, clientId: String, onResult: (Boolean, String?) -> Unit) {
        if (!NetworkMonitor.isOnline) {
            Logger.log("OAuth2Manager[${config.serviceId}]: no internet connection", TAG)
            onResult(false, "no_internet")
            return
        }

        pendingConfigs[config.serviceId] = config
        val st = stateFor(config.serviceId)
        st.pendingClientId = clientId
        st.callback = onResult
        val nonce = generateNonce()
        st.nonce = nonce

        val authBuilder = Uri.parse(config.authorizeUrl).buildUpon()
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("client_id", clientId)
            .appendQueryParameter("scope", config.scopes)
            .appendQueryParameter("redirect_uri", config.redirectUri)
            .appendQueryParameter("state", "${config.serviceId}:$nonce")

        if (config.usePkce) {
            val verifier = generateCodeVerifier()
            st.codeVerifier = verifier
            authBuilder
                .appendQueryParameter("code_challenge_method", "S256")
                .appendQueryParameter("code_challenge", generateCodeChallenge(verifier))
        }

        val authUri = authBuilder.build()
        Logger.log("OAuth2Manager[${config.serviceId}]: starting auth flow: $authUri", TAG)

        CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(context, authUri)
    }

    /**
     * Handles an OAuth redirect. [fallbackServiceId] lets a caller that knows which dedicated
     * host caught the redirect (e.g. MainActivity matching `host == "spotify"`) skip `state`
     * parsing if it's ever absent; normally `state` alone resolves the service.
     */
    fun handleRedirect(uri: Uri, fallbackServiceId: String? = null) {
        Logger.log("OAuth2Manager: redirect received: $uri", TAG)
        val state = uri.getQueryParameter("state")
        val serviceId = state?.substringBefore(":", missingDelimiterValue = "")?.takeIf { it.isNotEmpty() } ?: fallbackServiceId
        if (serviceId == null) {
            Logger.log("OAuth2Manager: redirect carries no resolvable service id: $uri", TAG)
            return
        }

        val st = stateFor(serviceId)
        val nonce = state?.substringAfter(":", "")
        if (!nonce.isNullOrEmpty() && nonce != st.nonce) {
            Logger.log("OAuth2Manager[$serviceId]: state nonce mismatch — ignoring redirect (possible CSRF)", TAG)
            return
        }

        val config = pendingConfigs[serviceId]
        val code = uri.getQueryParameter("code")
        val error = uri.getQueryParameter("error")
        val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

        if (error != null) {
            Logger.log("OAuth2Manager[$serviceId]: auth error: $error", TAG)
            val cb = st.callback; st.callback = null
            mainHandler.post { cb?.invoke(false, error) }
            return
        }
        if (code == null) {
            Logger.log("OAuth2Manager[$serviceId]: redirect missing code parameter", TAG)
            val cb = st.callback; st.callback = null
            mainHandler.post { cb?.invoke(false, "missing_code") }
            return
        }
        if (config == null) {
            Logger.log("OAuth2Manager[$serviceId]: no pending config for this service", TAG)
            val cb = st.callback; st.callback = null
            mainHandler.post { cb?.invoke(false, "unknown_service") }
            return
        }

        Logger.log("OAuth2Manager[$serviceId]: auth code received, exchanging for token...", TAG)

        Thread {
            try {
                val tokenResponse = exchangeCodeForToken(config, st, code)
                if (tokenResponse != null) {
                    st.accessToken = tokenResponse.getString("access_token")
                    val newRefresh = tokenResponse.optString("refresh_token", "")
                    if (newRefresh.isNotBlank()) st.refreshToken = newRefresh
                    val expiresIn = tokenResponse.optLong("expires_in", 3600)
                    st.tokenExpiry = System.currentTimeMillis() + expiresIn * 1000
                    st.isAuthorized = true
                    Logger.log("OAuth2Manager[$serviceId]: token exchange successful, expires in ${expiresIn}s", TAG)
                    persistTokens(serviceId, st)
                    val cb = st.callback; st.callback = null
                    mainHandler.post { cb?.invoke(true, null) }
                } else {
                    Logger.log("OAuth2Manager[$serviceId]: token exchange failed", TAG)
                    val cb = st.callback; st.callback = null
                    mainHandler.post { cb?.invoke(false, "token_exchange_failed") }
                }
            } catch (e: Exception) {
                Logger.log("OAuth2Manager[$serviceId]: token exchange exception: ${e.message}", TAG)
                val cb = st.callback; st.callback = null
                mainHandler.post { cb?.invoke(false, e.message) }
            }
        }.start()
    }

    private fun exchangeCodeForToken(config: OAuthConfig, st: ServiceAuthState, code: String): JSONObject? {
        val clientId = st.pendingClientId ?: return null

        val params = StringBuilder()
        params.append("client_id=").append(Uri.encode(clientId))
        params.append("&grant_type=authorization_code")
        params.append("&code=").append(Uri.encode(code))
        params.append("&redirect_uri=").append(Uri.encode(config.redirectUri))
        if (config.usePkce) {
            val verifier = st.codeVerifier ?: return null
            params.append("&code_verifier=").append(Uri.encode(verifier))
        }
        config.clientSecret?.let { params.append("&client_secret=").append(Uri.encode(it)) }

        val conn = URL(config.tokenUrl).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        conn.doOutput = true
        conn.outputStream.use { it.write(params.toString().toByteArray()) }

        val responseCode = conn.responseCode
        Logger.log("OAuth2Manager[${config.serviceId}]: token exchange response code: $responseCode", TAG)

        return if (responseCode == 200) {
            JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        } else {
            val errorResponse = conn.errorStream?.bufferedReader()?.use { it.readText() }
            Logger.log("OAuth2Manager[${config.serviceId}]: token exchange error: $errorResponse", TAG)
            null
        }
    }

    fun refreshAccessToken(config: OAuthConfig, clientId: String): Boolean {
        val st = stateFor(config.serviceId)
        val refresh = st.refreshToken ?: return false

        val params = StringBuilder()
        params.append("grant_type=refresh_token")
        params.append("&refresh_token=").append(Uri.encode(refresh))
        params.append("&client_id=").append(Uri.encode(clientId))
        config.clientSecret?.let { params.append("&client_secret=").append(Uri.encode(it)) }

        return try {
            val conn = URL(config.tokenUrl).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            conn.doOutput = true
            conn.outputStream.use { it.write(params.toString().toByteArray()) }

            if (conn.responseCode == 200) {
                val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                st.accessToken = json.getString("access_token")
                val expiresIn = json.optLong("expires_in", 3600)
                st.tokenExpiry = System.currentTimeMillis() + expiresIn * 1000
                val newRefresh = json.optString("refresh_token", "")
                if (newRefresh.isNotBlank()) st.refreshToken = newRefresh
                persistTokens(config.serviceId, st)
                Logger.log("OAuth2Manager[${config.serviceId}]: token refreshed successfully", TAG)
                true
            } else {
                Logger.log("OAuth2Manager[${config.serviceId}]: token refresh failed: ${conn.responseCode}", TAG)
                false
            }
        } catch (e: Exception) {
            Logger.log("OAuth2Manager[${config.serviceId}]: token refresh exception: ${e.message}", TAG)
            false
        }
    }

    fun getValidAccessToken(config: OAuthConfig, clientId: String): String? {
        val st = stateFor(config.serviceId)
        if (!st.isAuthorized) return null
        if (System.currentTimeMillis() < st.tokenExpiry - 60_000) {
            return st.accessToken
        }
        val refreshed = refreshAccessToken(config, clientId)
        return if (refreshed) st.accessToken else null
    }

    fun logout(serviceId: String) {
        val st = stateFor(serviceId)
        st.accessToken = null
        st.refreshToken = null
        st.tokenExpiry = 0
        st.isAuthorized = false
        st.cachedDeviceId = null
        st.codeVerifier = null
        st.pendingClientId = null
        st.callback = null
        persistTokens(serviceId, st)
        settingsRepo?.let { repo -> AppScope.io.launch { repo.setServiceDeviceId(serviceId, null) } }
        Logger.log("OAuth2Manager[$serviceId]: logout", TAG)
    }

    private fun persistTokens(serviceId: String, st: ServiceAuthState) {
        val repo = settingsRepo ?: return
        AppScope.io.launch { repo.setServiceTokens(serviceId, st.accessToken, st.refreshToken, st.tokenExpiry) }
    }

    private fun stateFor(serviceId: String): ServiceAuthState = states.getOrPut(serviceId) { ServiceAuthState() }

    // --- PKCE / state utility functions ---

    private fun generateNonce(): String {
        val random = ByteArray(16)
        SecureRandom().nextBytes(random)
        return Base64.encodeToString(random, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    private fun generateCodeVerifier(): String {
        val random = ByteArray(64)
        SecureRandom().nextBytes(random)
        return Base64.encodeToString(random, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    private fun generateCodeChallenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(verifier.toByteArray())
        return Base64.encodeToString(hash, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }
}
