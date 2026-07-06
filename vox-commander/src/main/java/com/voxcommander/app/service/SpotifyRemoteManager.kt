package com.voxcommander.app.service

import android.content.Context
import com.spotify.android.appremote.api.ConnectionParams
import com.spotify.android.appremote.api.Connector
import com.spotify.android.appremote.api.SpotifyAppRemote
import com.voxcommander.app.utils.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Manages Spotify App Remote connection and playback control.
 * Uses the Spotify Android SDK to connect to the Spotify app and send
 * play/pause/search commands directly — same mechanism Google Assistant uses.
 *
 * Requires:
 * - Spotify app installed on device
 * - Client ID registered at https://developer.spotify.com/dashboard
 * - Redirect URI whitelisted in the dashboard
 * - User must approve the app-remote-control scope on first connect
 */
object SpotifyRemoteManager {

    private const val TAG = "SpotifyRemote"

    private const val REDIRECT_URI = "voxcommander://spotify/callback"

    private var spotifyAppRemote: SpotifyAppRemote? = null
    private var clientId: String? = null
    var connectionError: String? = null
        private set

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    /**
     * Sets the Spotify Client ID from user settings.
     * Call this before connect().
     */
    fun setClientId(id: String?) {
        clientId = id
    }

    fun getClientId(): String? = clientId

    val isConnected: Boolean get() = spotifyAppRemote != null

    /**
     * Connects to the Spotify app asynchronously. Does NOT block.
     * The callback is invoked on the main thread when connection succeeds or fails.
     * Use this from UI code so the auth view has unlimited time for the user to complete OAuth.
     */
    fun connectAsync(context: Context, onResult: (Boolean) -> Unit) {
        connectionError = null
        if (spotifyAppRemote != null) {
            Logger.log("Spotify already connected", TAG)
            onResult(true)
            return
        }

        if (clientId.isNullOrBlank()) {
            Logger.log("Spotify Client ID not configured — cannot connect", TAG)
            connectionError = "NO_CLIENT_ID"
            onResult(false)
            return
        }

        Logger.log("Spotify connectAsync starting with clientId=${clientId?.take(8)}...", TAG)

        val connectionParams = ConnectionParams.Builder(clientId)
            .setRedirectUri(REDIRECT_URI)
            .showAuthView(true)
            .build()

        val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
        var callbackCalled = false

        val timeoutRunnable = Runnable {
            if (!callbackCalled) {
                callbackCalled = true
                connectionError = "TIMEOUT"
                Logger.log("Spotify connectAsync timed out after 60s — SDK did not respond", TAG)
                onResult(false)
            }
        }
        mainHandler.postDelayed(timeoutRunnable, 60_000)

        SpotifyAppRemote.connect(context, connectionParams, object : Connector.ConnectionListener {
            override fun onConnected(appRemote: SpotifyAppRemote) {
                if (callbackCalled) return
                callbackCalled = true
                mainHandler.removeCallbacks(timeoutRunnable)
                spotifyAppRemote = appRemote
                _connected.value = true
                Logger.log("Spotify App Remote connected", TAG)
                onResult(true)
            }

            override fun onFailure(throwable: Throwable) {
                if (callbackCalled) return
                callbackCalled = true
                mainHandler.removeCallbacks(timeoutRunnable)
                val msg = throwable.message ?: "Unknown error"
                connectionError = when {
                    msg.contains("authorization", ignoreCase = true) ||
                    msg.contains("auth-flow", ignoreCase = true) ->
                        "AUTH_REQUIRED"
                    msg.contains("Could not find registered app", ignoreCase = true) ||
                    msg.contains("redirect", ignoreCase = true) ->
                        "REDIRECT_URI_MISMATCH"
                    msg.contains("offline", ignoreCase = true) ||
                    msg.contains("network", ignoreCase = true) ->
                        "NETWORK_ERROR"
                    else -> "GENERIC"
                }
                Logger.log("Spotify App Remote connection failed: $msg (code=$connectionError)", TAG)
                onResult(false)
            }
        })
    }

    /**
     * Connects to the Spotify app. Blocks until connected or timeout.
     * Used internally by playSearch() — not suitable for UI-triggered OAuth (timeout too short).
     */
    fun connect(context: Context, timeoutSeconds: Long = 30): Boolean {
        connectionError = null
        if (spotifyAppRemote != null) {
            Logger.log("Spotify already connected", TAG)
            return true
        }

        if (clientId.isNullOrBlank()) {
            Logger.log("Spotify Client ID not configured — cannot connect", TAG)
            return false
        }

        val connectionParams = ConnectionParams.Builder(clientId)
            .setRedirectUri(REDIRECT_URI)
            .showAuthView(true)
            .build()

        val latch = CountDownLatch(1)
        var success = false

        val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
        mainHandler.post {
            SpotifyAppRemote.connect(context, connectionParams, object : Connector.ConnectionListener {
                override fun onConnected(appRemote: SpotifyAppRemote) {
                    spotifyAppRemote = appRemote
                    _connected.value = true
                    Logger.log("Spotify App Remote connected", TAG)
                    success = true
                    latch.countDown()
                }

                override fun onFailure(throwable: Throwable) {
                    val msg = throwable.message ?: "Unknown error"
                    connectionError = when {
                        msg.contains("authorization", ignoreCase = true) ||
                        msg.contains("auth-flow", ignoreCase = true) ->
                            "AUTH_REQUIRED"
                        msg.contains("Could not find registered app", ignoreCase = true) ||
                        msg.contains("redirect", ignoreCase = true) ->
                            "REDIRECT_URI_MISMATCH"
                        msg.contains("offline", ignoreCase = true) ||
                        msg.contains("network", ignoreCase = true) ->
                            "NETWORK_ERROR"
                        else -> "GENERIC"
                    }
                    Logger.log("Spotify App Remote connection failed: $msg (code=$connectionError)", TAG)
                    latch.countDown()
                }
            })
        }

        latch.await(timeoutSeconds, TimeUnit.SECONDS)
        return success
    }

    /**
     * Plays a search query in Spotify.
     * Connects if not already connected, then uses playFromSearchUri.
     * Must be called on a background thread.
     */
    fun playSearch(context: Context, query: String): Boolean {
        if (!connect(context)) {
            Logger.log("Cannot play search — Spotify not connected", TAG)
            return false
        }

        val remote = spotifyAppRemote ?: return false

        return try {
            // Use the search URI format: spotify:search:query
            val searchUri = "spotify:search:${query}"
            remote.playerApi.play(searchUri)
            Logger.log("Spotify playing search: $query", TAG)
            true
        } catch (e: Exception) {
            Logger.log("Spotify playSearch failed: ${e.message}", TAG)
            false
        }
    }

    /**
     * Plays a specific Spotify URI (playlist, album, artist, track).
     */
    fun playUri(context: Context, uri: String): Boolean {
        if (!connect(context)) {
            return false
        }

        val remote = spotifyAppRemote ?: return false

        return try {
            remote.playerApi.play(uri)
            Logger.log("Spotify playing URI: $uri", TAG)
            true
        } catch (e: Exception) {
            Logger.log("Spotify playUri failed: ${e.message}", TAG)
            false
        }
    }

    /**
     * Resumes playback.
     */
    fun resume(): Boolean {
        val remote = spotifyAppRemote ?: return false
        return try {
            remote.playerApi.resume()
            Logger.log("Spotify resumed", TAG)
            true
        } catch (e: Exception) {
            Logger.log("Spotify resume failed: ${e.message}", TAG)
            false
        }
    }

    /**
     * Pauses playback.
     */
    fun pause(): Boolean {
        val remote = spotifyAppRemote ?: return false
        return try {
            remote.playerApi.pause()
            Logger.log("Spotify paused", TAG)
            true
        } catch (e: Exception) {
            Logger.log("Spotify pause failed: ${e.message}", TAG)
            false
        }
    }

    /**
     * Skips to next track.
     */
    fun skipNext(): Boolean {
        val remote = spotifyAppRemote ?: return false
        return try {
            remote.playerApi.skipNext()
            Logger.log("Spotify skip next", TAG)
            true
        } catch (e: Exception) {
            Logger.log("Spotify skipNext failed: ${e.message}", TAG)
            false
        }
    }

    /**
     * Skips to previous track.
     */
    fun skipPrevious(): Boolean {
        val remote = spotifyAppRemote ?: return false
        return try {
            remote.playerApi.skipPrevious()
            Logger.log("Spotify skip previous", TAG)
            true
        } catch (e: Exception) {
            Logger.log("Spotify skipPrevious failed: ${e.message}", TAG)
            false
        }
    }

    /**
     * Disconnects from Spotify App Remote.
     */
    fun disconnect() {
        spotifyAppRemote?.let {
            SpotifyAppRemote.disconnect(it)
            Logger.log("Spotify App Remote disconnected", TAG)
        }
        spotifyAppRemote = null
        _connected.value = false
    }
}
