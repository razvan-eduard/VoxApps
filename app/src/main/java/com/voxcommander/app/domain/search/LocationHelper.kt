package com.voxcommander.app.domain.search

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.location.Location
import android.os.Looper
import androidx.core.content.ContextCompat
import com.voxcommander.app.data.preferences.SettingsRepository
import com.voxcommander.app.utils.Logger
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import java.util.concurrent.TimeUnit

/**
 * Simple location helper using Android's built-in LocationManager.
 * Returns last known location from GPS or network provider.
 * No Google Play Services dependency needed.
 */
object LocationHelper {

    private const val TAG = "LocationHelper"
    private const val PREFS_NAME = "location_cache"
    private const val KEY_LAT = "last_lat"
    private const val KEY_LON = "last_lon"
    private const val KEY_TIMESTAMP = "last_timestamp"
    private val MAX_AGE_MS = TimeUnit.DAYS.toMillis(30) // Cache valid for 30 days

    var settingsRepo: SettingsRepository? = null

    /**
     * Returns the last known location, or null if unavailable or no permission.
     * Tries GPS first, then network provider.
     */
    @SuppressLint("MissingPermission")
    fun getLastKnownLocation(context: Context): Location? {
        if (!hasLocationPermission(context)) {
            Logger.log("No location permission granted, trying cached location", TAG)
            return getCachedLocation(context)
        }

        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

        // Try GPS first (more accurate)
        if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            val loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            if (loc != null) {
                Logger.log("Got location from GPS: lat=${loc.latitude}, lon=${loc.longitude}", TAG)
                saveCachedLocation(context, loc)
                return loc
            }
        }

        // Fallback to network provider
        if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
            val loc = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            if (loc != null) {
                Logger.log("Got location from network: lat=${loc.latitude}, lon=${loc.longitude}", TAG)
                saveCachedLocation(context, loc)
                return loc
            }
        }

        Logger.log("No last known location available, trying cached", TAG)
        return getCachedLocation(context)
    }

    /**
     * Tries last known location first. If null, requests a single fresh update.
     * Use this from coroutines for weather search.
     */
    @SuppressLint("MissingPermission")
    suspend fun getLocation(context: Context): Location? {
        val lastKnown = getLastKnownLocation(context)
        if (lastKnown != null) return lastKnown

        if (!hasLocationPermission(context)) {
            Logger.log("No location permission, trying cached location", TAG)
            return getCachedLocation(context)
        }

        Logger.log("Requesting fresh location update...", TAG)
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

        val provider = when {
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> {
                Logger.log("No location provider enabled, trying cached location", TAG)
                return getCachedLocation(context)
            }
        }

        return withTimeoutOrNull(10_000L) {
            suspendCancellableCoroutine<Location?> { cont ->
                val listener = object : android.location.LocationListener {
                    override fun onLocationChanged(location: Location) {
                        Logger.log("Fresh location: lat=${location.latitude}, lon=${location.longitude}", TAG)
                        saveCachedLocation(context, location)
                        lm.removeUpdates(this)
                        if (cont.isActive) cont.resume(location)
                    }
                    override fun onProviderEnabled(provider: String) {}
                    override fun onProviderDisabled(provider: String) {
                        lm.removeUpdates(this)
                        if (cont.isActive) cont.resume(null)
                    }
                    override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
                }

                try {
                    lm.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper())
                } catch (e: Exception) {
                    Logger.log("Failed to request location updates: ${e.message}", TAG)
                    if (cont.isActive) cont.resume(getCachedLocation(context))
                }

                cont.invokeOnCancellation {
                    try { lm.removeUpdates(listener) } catch (_: Exception) {}
                }
            }
        } ?: run {
            Logger.log("Location request timed out after 10s, trying cached", TAG)
            getCachedLocation(context)
        }
    }

    fun hasLocationPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun saveCachedLocation(context: Context, loc: Location) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putFloat(KEY_LAT, loc.latitude.toFloat())
            .putFloat(KEY_LON, loc.longitude.toFloat())
            .putLong(KEY_TIMESTAMP, System.currentTimeMillis())
            .apply()
        Logger.log("Cached location saved: lat=${loc.latitude}, lon=${loc.longitude}", TAG)
    }

    private fun getCachedLocation(context: Context): Location? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY_LAT) || !prefs.contains(KEY_LON)) {
            Logger.log("No cached location, trying manual location", TAG)
            return getManualLocation()
        }

        val age = System.currentTimeMillis() - prefs.getLong(KEY_TIMESTAMP, 0)
        if (age > MAX_AGE_MS) {
            Logger.log("Cached location expired (age=${age / TimeUnit.DAYS.toMillis(1)}d), trying manual", TAG)
            return getManualLocation()
        }

        val lat = prefs.getFloat(KEY_LAT, 0f).toDouble()
        val lon = prefs.getFloat(KEY_LON, 0f).toDouble()
        Logger.log("Using cached location: lat=$lat, lon=$lon (age=${age / TimeUnit.MINUTES.toMillis(1)}min)", TAG)
        return Location("cached").apply {
            latitude = lat
            longitude = lon
        }
    }

    private fun getManualLocation(): Location? {
        val lat = settingsRepo?.getManualLocationLatSync()
        val lon = settingsRepo?.getManualLocationLonSync()
        return if (lat != null && lon != null) {
            Logger.log("Using manual location: lat=$lat, lon=$lon", TAG)
            Location("manual").apply {
                latitude = lat
                longitude = lon
            }
        } else null
    }
}
