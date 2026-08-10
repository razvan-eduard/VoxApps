package com.voxapps.location

import com.voxapps.identity.VoxRepo
import com.voxapps.logging.Logger
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

private const val TAG = "VoxNominatimGeocoder"

/** Nominatim's usage policy requires callers to identify themselves. One definition, so every
 *  caller says the same thing and a policy complaint has a single place to be answered. */
const val VOX_NOMINATIM_USER_AGENT =
    "${VoxRepo.NAME} (github.com/${VoxRepo.OWNER}/${VoxRepo.NAME})"

/**
 * OpenStreetMap Nominatim reverse geocoding — free, keyless, no Google. A `User-Agent` identifying
 * the calling app is required by Nominatim's usage policy (unauthenticated requests without one
 * may be blocked). Public (not just used internally by [VoxLocationResolver]) so a settings
 * card's manual refresh button can resolve a display name directly.
 */
class VoxNominatimGeocoder(private val userAgent: String = VOX_NOMINATIM_USER_AGENT) {

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    fun reverseGeocode(lat: Double, lon: Double): String? = try {
        val url = "https://nominatim.openstreetmap.org/reverse" +
            "?format=json&lat=$lat&lon=$lon&zoom=10&addressdetails=1"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .get()
            .build()
        httpClient.newCall(request).execute().use { response ->
            // Every way of coming back empty says so. Nominatim rate-limits and blocks clients by
            // policy, and both answers arrive as an ordinary non-200 — so a silent `return null`
            // here left a screen showing bare coordinates with nothing anywhere explaining why, and
            // no way to tell "blocked" from "this spot genuinely has no name".
            if (!response.isSuccessful) {
                Logger.w(TAG, "Reverse geocoding refused: HTTP ${'$'}{response.code}")
                return null
            }
            val body = response.body?.string()
            if (body == null) {
                Logger.w(TAG, "Reverse geocoding returned an empty body")
                return null
            }
            val address = JSONObject(body).optJSONObject("address")
            if (address == null) {
                Logger.w(TAG, "Reverse geocoding returned no address for ${'$'}lat, ${'$'}lon")
                return null
            }
            val name = address.optStringOrNull("city")
                ?: address.optStringOrNull("town")
                ?: address.optStringOrNull("village")
                ?: address.optStringOrNull("municipality")
                ?: address.optStringOrNull("county")
            if (name == null) Logger.w(TAG, "No place name in the address for ${'$'}lat, ${'$'}lon")
            name
        }
    } catch (e: Exception) {
        Logger.w(TAG, "Reverse geocoding failed", e)
        null
    }

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (has(key) && !isNull(key)) optString(key) else null
}
