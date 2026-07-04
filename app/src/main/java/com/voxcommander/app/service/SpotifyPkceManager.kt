package com.voxcommander.app.service

import android.content.Context
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import com.voxcommander.app.data.preferences.SettingsRepository
import com.voxcommander.app.utils.Logger
import com.voxcommander.app.utils.NetworkMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL
import java.net.HttpURLConnection
import java.security.MessageDigest
import java.security.SecureRandom
import android.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Spotify PKCE OAuth Flow Manager.
 * Implements Authorization Code with PKCE for Spotify Web API.
 * Opens a Chrome Custom Tab for user authorization, receives the auth code
 * via deep link redirect, and exchanges it for an access token.
 */
object SpotifyPkceManager {

    private const val TAG = "SpotifyPkce"
    private const val AUTH_URL = "https://accounts.spotify.com/authorize"
    private const val TOKEN_URL = "https://accounts.spotify.com/api/token"
    private const val REDIRECT_URI = "voxcommander://spotify/callback"
    private const val SCOPES = "user-read-playback-state user-modify-playback-state user-read-currently-playing streaming app-remote-control"

    private var codeVerifier: String? = null
    private var pendingClientId: String? = null

    var accessToken: String? = null
        private set
    var refreshToken: String? = null
        private set
    var tokenExpiry: Long = 0
        private set

    var isAuthorized: Boolean = false
        private set

    var cachedDeviceId: String? = null
        private set

    private var settingsRepo: SettingsRepository? = null

    /**
     * Initializes PKCE manager with settings repo and loads persisted tokens.
     * Call from Application.onCreate.
     */
    fun init(repo: SettingsRepository) {
        settingsRepo = repo
        loadPersistedTokens()
        cachedDeviceId = repo.getSpotifyDeviceIdSync()
        if (cachedDeviceId != null) {
            Logger.log("PKCE cached device ID loaded: ${cachedDeviceId!!.take(8)}...", TAG)
        }
    }

    private fun loadPersistedTokens() {
        val repo = settingsRepo ?: return
        val token = repo.getSpotifyAccessTokenSync()
        val refresh = repo.getSpotifyRefreshTokenSync()
        val expiry = repo.getSpotifyTokenExpirySync()

        if (token != null && refresh != null && expiry > System.currentTimeMillis()) {
            accessToken = token
            refreshToken = refresh
            tokenExpiry = expiry
            isAuthorized = true
            Logger.log("PKCE tokens loaded from storage, expires in ${(expiry - System.currentTimeMillis()) / 1000}s", TAG)
        } else if (refresh != null) {
            // Access token expired but we have refresh token — will refresh on demand
            refreshToken = refresh
            tokenExpiry = 0
            isAuthorized = true
            Logger.log("PKCE refresh token loaded, access token expired — will refresh on demand", TAG)
        }
    }

    private fun persistTokens() {
        val repo = settingsRepo ?: return
        CoroutineScope(Dispatchers.IO).launch {
            repo.setSpotifyTokens(accessToken, refreshToken, tokenExpiry)
        }
    }

    /** Callback invoked when the OAuth redirect is received */
    private var authCallback: ((Boolean, String?) -> Unit)? = null

    /**
     * Starts the PKCE OAuth flow by opening a Chrome Custom Tab.
     * The activity must handle the redirect URI in onNewIntent / onIntent.
     */
    fun startAuthFlow(context: Context, clientId: String, onResult: (Boolean, String?) -> Unit) {
        if (!NetworkMonitor.isOnline) {
            Logger.log("PKCE auth flow: no internet connection", TAG)
            onResult(false, "no_internet")
            return
        }

        pendingClientId = clientId
        authCallback = onResult

        // Generate PKCE code verifier and challenge
        codeVerifier = generateCodeVerifier()
        val verifier = codeVerifier ?: return
        val codeChallenge = generateCodeChallenge(verifier)

        Logger.log("Starting PKCE auth flow, clientId=${clientId.take(8)}...", TAG)

        val authUri = Uri.parse(AUTH_URL).buildUpon()
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("client_id", clientId)
            .appendQueryParameter("scope", SCOPES)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("code_challenge", codeChallenge)
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .build()

        Logger.log("Auth URL: $authUri", TAG)

        val customTabsIntent = CustomTabsIntent.Builder()
            .setShowTitle(true)
            .build()
        customTabsIntent.launchUrl(context, authUri)
    }

    /**
     * Called from MainActivity.onNewIntent when the redirect URI is received.
     * Extracts the authorization code and exchanges it for tokens.
     */
    fun handleRedirect(uri: Uri) {
        Logger.log("PKCE redirect received: $uri", TAG)

        val code = uri.getQueryParameter("code")
        val error = uri.getQueryParameter("error")

        val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

        if (error != null) {
            Logger.log("PKCE auth error: $error", TAG)
            mainHandler.post { authCallback?.invoke(false, error) }
            authCallback = null
            return
        }

        if (code == null) {
            Logger.log("PKCE redirect missing code parameter", TAG)
            mainHandler.post { authCallback?.invoke(false, "missing_code") }
            authCallback = null
            return
        }

        Logger.log("PKCE auth code received, exchanging for token...", TAG)

        // Exchange code for token in background
        Thread {
            try {
                val tokenResponse = exchangeCodeForToken(code)
                if (tokenResponse != null) {
                    accessToken = tokenResponse.getString("access_token")
                    val newRefresh = tokenResponse.optString("refresh_token", "")
                    if (newRefresh.isNotBlank()) refreshToken = newRefresh
                    val expiresIn = tokenResponse.optLong("expires_in", 3600)
                    tokenExpiry = System.currentTimeMillis() + expiresIn * 1000
                    isAuthorized = true
                    Logger.log("PKCE token exchange successful, expires in ${expiresIn}s", TAG)
                    persistTokens()
                    val cb = authCallback
                    authCallback = null
                    mainHandler.post { cb?.invoke(true, null) }
                } else {
                    Logger.log("PKCE token exchange failed", TAG)
                    val cb = authCallback
                    authCallback = null
                    mainHandler.post { cb?.invoke(false, "token_exchange_failed") }
                }
            } catch (e: Exception) {
                Logger.log("PKCE token exchange exception: ${e.message}", TAG)
                val cb = authCallback
                authCallback = null
                mainHandler.post { cb?.invoke(false, e.message) }
            }
        }.start()
    }

    /**
     * Exchanges the authorization code for access and refresh tokens.
     */
    private fun exchangeCodeForToken(code: String): JSONObject? {
        val verifier = codeVerifier ?: return null
        val clientId = pendingClientId ?: return null

        val params = StringBuilder()
        params.append("client_id=").append(Uri.encode(clientId))
        params.append("&grant_type=authorization_code")
        params.append("&code=").append(Uri.encode(code))
        params.append("&redirect_uri=").append(Uri.encode(REDIRECT_URI))
        params.append("&code_verifier=").append(Uri.encode(verifier))

        val url = URL(TOKEN_URL)
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        conn.doOutput = true

        conn.outputStream.use { it.write(params.toString().toByteArray()) }

        val responseCode = conn.responseCode
        Logger.log("Token exchange response code: $responseCode", TAG)

        if (responseCode == 200) {
            val response = conn.inputStream.bufferedReader().use { it.readText() }
            return JSONObject(response)
        } else {
            val errorResponse = conn.errorStream?.bufferedReader()?.use { it.readText() }
            Logger.log("Token exchange error: $errorResponse", TAG)
            return null
        }
    }

    /**
     * Refreshes the access token using the stored refresh token.
     */
    fun refreshAccessToken(clientId: String): Boolean {
        val refresh = refreshToken ?: return false

        val params = StringBuilder()
        params.append("grant_type=refresh_token")
        params.append("&refresh_token=").append(Uri.encode(refresh))
        params.append("&client_id=").append(Uri.encode(clientId))

        return try {
            val url = URL(TOKEN_URL)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            conn.doOutput = true

            conn.outputStream.use { it.write(params.toString().toByteArray()) }

            if (conn.responseCode == 200) {
                val response = conn.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(response)
                accessToken = json.getString("access_token")
                val expiresIn = json.optLong("expires_in", 3600)
                tokenExpiry = System.currentTimeMillis() + expiresIn * 1000
                // Update refresh token if a new one is provided
                val newRefresh = json.optString("refresh_token", "")
                if (newRefresh.isNotBlank()) refreshToken = newRefresh
                persistTokens()
                Logger.log("Token refreshed successfully", TAG)
                true
            } else {
                Logger.log("Token refresh failed: ${conn.responseCode}", TAG)
                false
            }
        } catch (e: Exception) {
            Logger.log("Token refresh exception: ${e.message}", TAG)
            false
        }
    }

    /**
     * Returns a valid access token, refreshing if necessary.
     */
    fun getValidAccessToken(clientId: String): String? {
        if (!isAuthorized) return null
        if (System.currentTimeMillis() < tokenExpiry - 60_000) {
            return accessToken
        }
        // Token expired, try refresh
        val refreshed = refreshAccessToken(clientId)
        return if (refreshed) accessToken else null
    }

    fun setCachedDeviceId(deviceId: String?) {
        cachedDeviceId = deviceId
        val repo = settingsRepo
        if (repo != null && deviceId != null) {
            CoroutineScope(Dispatchers.IO).launch { repo.setSpotifyDeviceId(deviceId) }
            Logger.log("PKCE device ID cached: ${deviceId.take(8)}...", TAG)
        }
    }

    fun logout() {
        accessToken = null
        refreshToken = null
        tokenExpiry = 0
        isAuthorized = false
        cachedDeviceId = null
        codeVerifier = null
        pendingClientId = null
        authCallback = null
        persistTokens()
        settingsRepo?.let { repo ->
            CoroutineScope(Dispatchers.IO).launch { repo.setSpotifyDeviceId(null) }
        }
        Logger.log("PKCE logout", TAG)
    }

    // --- PKCE Utility Functions ---

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
