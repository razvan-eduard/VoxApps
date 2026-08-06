package com.voxapps.location

import com.voxapps.logging.Logger
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

private const val TAG = "VoxNominatimGeocoder"

/**
 * OpenStreetMap Nominatim reverse geocoding — free, keyless, no Google. A `User-Agent` identifying
 * the calling app is required by Nominatim's usage policy (unauthenticated requests without one
 * may be blocked). Public (not just used internally by [VoxLocationResolver]) so a settings
 * card's manual refresh button can resolve a display name directly.
 */
class VoxNominatimGeocoder(private val userAgent: String) {

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
            if (!response.isSuccessful) return null
            val body = response.body?.string() ?: return null
            val address = JSONObject(body).optJSONObject("address") ?: return null
            address.optStringOrNull("city")
                ?: address.optStringOrNull("town")
                ?: address.optStringOrNull("village")
                ?: address.optStringOrNull("municipality")
                ?: address.optStringOrNull("county")
        }
    } catch (e: Exception) {
        Logger.w(TAG, "Reverse geocoding failed", e)
        null
    }

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (has(key) && !isNull(key)) optString(key) else null
}
