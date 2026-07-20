package com.voxapps.expenses.domain.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.voxapps.expenses.data.preferences.DataStoreProvider
import com.voxapps.logging.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

private const val TAG = "ExpensesLocationHelper"

/**
 * Resolves the user's current city for prefilling an expense's location field (voice, scan, and
 * manual entry — see each caller's own doc comment for exactly when this runs). Deliberately not
 * vox-commander's `LocationHelper` pattern verbatim: that one caches raw coordinates for up to 30
 * *days* (fine for a rough weather query), whereas a stale cached city here would be actively
 * wrong, not just imprecise — so the cache here stores the *resolved city name* itself, with a
 * 30-*minute* TTL instead.
 *
 * No Google anywhere in this chain, by design: `LocationManager` (not Play Services'
 * `FusedLocationProviderClient` — mirrors the precedent vox-commander's own `LocationHelper` already
 * established in this monorepo) for the fix itself, and OpenStreetMap's Nominatim (not Android's
 * on-device `Geocoder`, whose backend is Google-owned on most real devices) for reverse geocoding.
 */
object ExpensesLocationHelper {

    private val CACHE_MAX_AGE_MILLIS = TimeUnit.MINUTES.toMillis(30)
    private val FRESH_FIX_TIMEOUT_MILLIS = TimeUnit.SECONDS.toMillis(10)

    private object Keys {
        val CACHED_CITY = stringPreferencesKey("location_cached_city")
        val CACHED_AT = longPreferencesKey("location_cached_at")
    }

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    fun hasLocationPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /**
     * Synchronous (DataStore read only, no location/network I/O) — the fast path for prefilling a
     * screen the instant it opens (see `ExpenseEditScreen`'s new-expense case). Returns null on any
     * cache miss or staleness; never triggers a fetch itself.
     */
    suspend fun getCachedCityIfFresh(context: Context): String? {
        val prefs = DataStoreProvider.get(context).data.first()
        val city = prefs[Keys.CACHED_CITY] ?: return null
        val cachedAt = prefs[Keys.CACHED_AT] ?: return null
        return if (System.currentTimeMillis() - cachedAt <= CACHE_MAX_AGE_MILLIS) city else null
    }

    /**
     * Full resolution, used by every caller that isn't the manual-entry fast path above: fresh
     * cache -> last-known fix -> one fresh fix (up to 10s) -> reverse geocode -> cache the result.
     * Returns null (never throws) if permission is missing, no fix could be obtained, or reverse
     * geocoding failed — every caller treats null the same as "leave the field blank," never a
     * forced prompt or a blocking retry.
     */
    suspend fun resolveCurrentCity(context: Context): String? = withContext(Dispatchers.IO) {
        getCachedCityIfFresh(context)?.let { return@withContext it }
        if (!hasLocationPermission(context)) return@withContext null

        val location = getLastKnownLocation(context) ?: getFreshLocation(context) ?: return@withContext null
        val city = reverseGeocode(location) ?: return@withContext null
        cacheCity(context, city)
        city
    }

    @SuppressLint("MissingPermission")
    private fun getLastKnownLocation(context: Context): Location? {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let { return it }
        }
        if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
            lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)?.let { return it }
        }
        return null
    }

    @SuppressLint("MissingPermission")
    private suspend fun getFreshLocation(context: Context): Location? {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val provider = when {
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> return null
        }
        return withTimeoutOrNull(FRESH_FIX_TIMEOUT_MILLIS) {
            suspendCancellableCoroutine { cont ->
                val listener = object : android.location.LocationListener {
                    override fun onLocationChanged(location: Location) {
                        lm.removeUpdates(this)
                        if (cont.isActive) cont.resume(location)
                    }
                    override fun onProviderEnabled(provider: String) {}
                    override fun onProviderDisabled(provider: String) {
                        lm.removeUpdates(this)
                        if (cont.isActive) cont.resume(null)
                    }
                    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                }
                try {
                    lm.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper())
                } catch (e: Exception) {
                    Logger.w(TAG, "Failed to request location updates", e)
                    if (cont.isActive) cont.resume(null)
                }
                cont.invokeOnCancellation {
                    try { lm.removeUpdates(listener) } catch (e: Exception) { /* already removed */ }
                }
            }
        }
    }

    /**
     * OpenStreetMap Nominatim reverse geocoding — free, keyless, no Google. A `User-Agent`
     * identifying this app is required by Nominatim's usage policy (unauthenticated requests
     * without one may be blocked); this call happens at most once per 30-minute cache window per
     * device, well within fair-use for a personal-scale app.
     */
    private fun reverseGeocode(location: Location): String? = try {
        val url = "https://nominatim.openstreetmap.org/reverse" +
            "?format=json&lat=${location.latitude}&lon=${location.longitude}&zoom=10&addressdetails=1"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "VoxExpenses (github.com/razvan-eduard/VoxApps)")
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

    private suspend fun cacheCity(context: Context, city: String) {
        DataStoreProvider.get(context).edit {
            it[Keys.CACHED_CITY] = city
            it[Keys.CACHED_AT] = System.currentTimeMillis()
        }
    }
}
