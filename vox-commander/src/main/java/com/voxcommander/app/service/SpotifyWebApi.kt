package com.voxcommander.app.service

import com.voxcommander.app.utils.Logger
import com.voxcommander.app.utils.NetworkMonitor
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Spotify Web API helper.
 * Uses the access token from SpotifyPkceManager to control playback.
 * Requires Spotify Premium and an active device (Spotify app running on device).
 *
 * Key endpoints used:
 * - PUT /me/player/play — start/resume playback
 * - PUT /me/player/pause — pause playback
 * - POST /me/player/next — skip to next track
 * - GET /search — search for tracks/artists
 * - GET /me/player/devices — list available devices
 */
object SpotifyWebApi {

    private const val TAG = "SpotifyWebApi"
    private const val BASE_URL = "https://api.spotify.com/v1"

    /**
     * Searches for a track and plays it on the active device.
     * Falls back to transferring playback to an available device if none is active.
     *
     * @param clientId The Spotify Client ID (for token refresh)
     * @param query The search query (e.g. "artist name song name")
     * @return true if playback was started successfully
     */
    fun playSearch(clientId: String, query: String): Boolean {
        if (!NetworkMonitor.isOnline) {
            Logger.log("SpotifyWebApi: no internet connection, skipping playSearch", TAG)
            return false
        }

        val token = SpotifyPkceManager.getValidAccessToken(clientId)
        if (token == null) {
            Logger.log("SpotifyWebApi: no valid access token", TAG)
            return false
        }

        // 1. Search for the track
        val trackUri = searchTrack(token, query)
        if (trackUri == null) {
            Logger.log("SpotifyWebApi: no track found for '$query'", TAG)
            return false
        }

        Logger.log("SpotifyWebApi: found track $trackUri, starting playback", TAG)

        // 2. Try cached device ID first
        val cachedDeviceId = SpotifyPkceManager.cachedDeviceId
        if (cachedDeviceId != null) {
            Logger.log("SpotifyWebApi: trying cached device ${cachedDeviceId.take(8)}...", TAG)
            transferPlayback(token, cachedDeviceId)
            Thread.sleep(500)
            if (startPlaybackOnDevice(token, trackUri, cachedDeviceId)) {
                Logger.log("SpotifyWebApi: playback started on cached device", TAG)
                return true
            }
            Logger.log("SpotifyWebApi: cached device failed, discovering fresh...", TAG)
        }

        // 3. Discover available devices
        val deviceId = findAvailableDevice(token)
        if (deviceId != null) {
            // Cache the device ID for future use
            SpotifyPkceManager.setCachedDeviceId(deviceId)

            Logger.log("SpotifyWebApi: transferring playback to device $deviceId", TAG)
            transferPlayback(token, deviceId)
            Thread.sleep(500)

            if (startPlaybackOnDevice(token, trackUri, deviceId)) {
                Logger.log("SpotifyWebApi: playback started on device $deviceId", TAG)
                return true
            }
            // Retry after longer delay
            Thread.sleep(2000)
            if (startPlaybackOnDevice(token, trackUri, deviceId)) {
                Logger.log("SpotifyWebApi: playback started on second retry", TAG)
                return true
            }
        }

        // 4. Fallback: try playing on active device
        val played = startPlayback(token, trackUri)
        if (played) {
            Logger.log("SpotifyWebApi: playback started on active device", TAG)
            return true
        }

        Logger.log("SpotifyWebApi: could not start playback — no active device", TAG)
        return false
    }

    /**
     * Pauses playback on the active device.
     */
    fun pause(clientId: String): Boolean {
        val token = SpotifyPkceManager.getValidAccessToken(clientId) ?: return false
        return putRequest("$BASE_URL/me/player/pause", token, null)
    }

    /**
     * Resumes playback on the active device.
     */
    fun resume(clientId: String): Boolean {
        val token = SpotifyPkceManager.getValidAccessToken(clientId) ?: return false
        return putRequest("$BASE_URL/me/player/play", token, null)
    }

    /**
     * Skips to the next track.
     */
    fun nextTrack(clientId: String): Boolean {
        val token = SpotifyPkceManager.getValidAccessToken(clientId) ?: return false
        return postRequest("$BASE_URL/me/player/next", token)
    }

    /**
     * Searches for a track and returns its Spotify URI.
     */
    private fun searchTrack(token: String, query: String): String? {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val url = "$BASE_URL/search?q=$encodedQuery&type=track&limit=1"

        val response = getRequest(url, token) ?: return null
        val json = JSONObject(response)
        val tracks = json.optJSONObject("tracks") ?: return null
        val items = tracks.optJSONArray("items") ?: return null
        if (items.length() == 0) return null

        val track = items.getJSONObject(0)
        return track.getString("uri")
    }

    /**
     * Starts playback of a specific track URI on the active device.
     */
    private fun startPlayback(token: String, trackUri: String): Boolean {
        val body = JSONObject().put("uris", org.json.JSONArray().put(trackUri)).toString()
        return putRequest("$BASE_URL/me/player/play", token, body)
    }

    /**
     * Starts playback of a specific track URI on a specific device.
     */
    private fun startPlaybackOnDevice(token: String, trackUri: String, deviceId: String): Boolean {
        val body = JSONObject().put("uris", org.json.JSONArray().put(trackUri)).toString()
        val encodedDeviceId = URLEncoder.encode(deviceId, "UTF-8")
        return putRequest("$BASE_URL/me/player/play?device_id=$encodedDeviceId", token, body)
    }

    /**
     * Finds an available Spotify device (e.g. the phone running Spotify app).
     */
    private fun findAvailableDevice(token: String): String? {
        val response = getRequest("$BASE_URL/me/player/devices", token) ?: return null
        val json = JSONObject(response)
        val devices = json.optJSONArray("devices") ?: return null

        val deviceList = mutableListOf<JSONObject>()
        for (i in 0 until devices.length()) {
            val device = devices.getJSONObject(i)
            deviceList.add(device)
            Logger.log("SpotifyWebApi: device[${i}] name=${device.optString("name")} type=${device.optString("type")} id=${device.optString("id")} active=${device.optBoolean("is_active")}", TAG)
        }

        // 1. Prefer Smartphone (likely this phone)
        deviceList.firstOrNull { it.optString("type") == "Smartphone" }?.let {
            Logger.log("SpotifyWebApi: selecting smartphone device: ${it.optString("name")}", TAG)
            return it.optString("id")
        }

        // 2. Prefer active device
        deviceList.firstOrNull { it.optBoolean("is_active") }?.let {
            Logger.log("SpotifyWebApi: selecting active device: ${it.optString("name")}", TAG)
            return it.optString("id")
        }

        // 3. Prefer Computer
        deviceList.firstOrNull { it.optString("type") == "Computer" }?.let {
            Logger.log("SpotifyWebApi: selecting computer device: ${it.optString("name")}", TAG)
            return it.optString("id")
        }

        // 4. Fallback: first available
        return deviceList.firstOrNull()?.optString("id")
    }

    /**
     * Transfers playback to a specific device.
     */
    private fun transferPlayback(token: String, deviceId: String): Boolean {
        val body = JSONObject().put("device_ids", org.json.JSONArray().put(deviceId)).put("play", false).toString()
        return putRequest("$BASE_URL/me/player", token, body)
    }

    // --- HTTP Helpers ---

    private fun getRequest(url: String, token: String): String? {
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("Authorization", "Bearer $token")
            if (conn.responseCode == 200) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else {
                Logger.log("SpotifyWebApi GET $url failed: ${conn.responseCode}", TAG)
                null
            }
        } catch (e: Exception) {
            Logger.log("SpotifyWebApi GET exception: ${e.message}", TAG)
            null
        }
    }

    private fun putRequest(url: String, token: String, body: String?): Boolean {
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "PUT"
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            if (body != null) {
                conn.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = conn.responseCode
            Logger.log("SpotifyWebApi PUT $url response: $code", TAG)
            code in 200..299
        } catch (e: Exception) {
            Logger.log("SpotifyWebApi PUT exception: ${e.message}", TAG)
            false
        }
    }

    private fun postRequest(url: String, token: String): Boolean {
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.doOutput = true
            conn.outputStream.use { it.write(ByteArray(0)) }
            val code = conn.responseCode
            Logger.log("SpotifyWebApi POST $url response: $code", TAG)
            code in 200..299
        } catch (e: Exception) {
            Logger.log("SpotifyWebApi POST exception: ${e.message}", TAG)
            false
        }
    }
}
