package com.voxapps.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat
import com.voxapps.logging.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

private const val TAG = "VoxLocationResolver"
private const val FRESH_FIX_TIMEOUT_MILLIS = 10_000L

/**
 * A single lat/lon pair, decoupled from Android's [Location] so [VoxLocationResolver]'s
 * live-location seam is fake-able in plain JVM unit tests.
 */
data class LiveFix(val lat: Double, val lon: Double)

/**
 * The GPS-facing seam [VoxLocationResolver] depends on. The real implementation talks to
 * [LocationManager] directly (no Play Services); tests supply a fake instead.
 */
interface LiveLocationProvider {
    fun getLastKnownLocation(): LiveFix?
    suspend fun requestFreshFix(timeoutMillis: Long): LiveFix?
}

/**
 * Real [LiveLocationProvider] — GPS provider first, network provider as fallback, mirroring the
 * behavior both pre-unification LocationHelper/ExpensesLocationHelper implementations shared.
 */
class AndroidLiveLocationProvider(private val context: Context) : LiveLocationProvider {

    @SuppressLint("MissingPermission")
    override fun getLastKnownLocation(): LiveFix? {
        if (!hasLocationPermission(context)) return null
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let { return LiveFix(it.latitude, it.longitude) }
        }
        if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
            lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)?.let { return LiveFix(it.latitude, it.longitude) }
        }
        return null
    }

    @SuppressLint("MissingPermission")
    override suspend fun requestFreshFix(timeoutMillis: Long): LiveFix? {
        if (!hasLocationPermission(context)) return null
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val provider = when {
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> return null
        }
        return withTimeoutOrNull(timeoutMillis) {
            suspendCancellableCoroutine<LiveFix?> { cont ->
                val listener = object : android.location.LocationListener {
                    override fun onLocationChanged(location: Location) {
                        lm.removeUpdates(this)
                        if (cont.isActive) cont.resume(LiveFix(location.latitude, location.longitude))
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
                    try { lm.removeUpdates(listener) } catch (_: Exception) {}
                }
            }
        }
    }

    companion object {
        fun hasLocationPermission(context: Context): Boolean =
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }
}

/**
 * Merges vox-commander's `LocationHelper` and vox-expenses' `ExpensesLocationHelper` into one
 * priority chain:
 *  1. [VoxLocationStore.getAlwaysUseHomeTownSync] on -> Home Town, no GPS attempt at all.
 *  2. Live fix (last-known, then a fresh-fix request up to [freshFixTimeoutMillis]) -> cache it
 *     (+ reverse geocode if [needsReverseGeocode]) and return it.
 *  3. Cached fix, if still fresh per [VoxLocationStore.getCacheTtlSync].
 *  4. Home Town, if set.
 *  5. null.
 *
 * This preserves the exact attempt order both pre-unification helpers already used (live beats
 * cache beats manual fallback) — the only new behavior is step 1's short-circuit.
 */
class VoxLocationResolver(
    private val store: VoxLocationStore,
    private val liveLocationProvider: LiveLocationProvider,
    private val needsReverseGeocode: Boolean = false,
    private val geocoder: VoxNominatimGeocoder? = null,
    private val freshFixTimeoutMillis: Long = FRESH_FIX_TIMEOUT_MILLIS
) {

    suspend fun resolveLocation(): ResolvedLocation? {
        if (store.getAlwaysUseHomeTownSync()) {
            Logger.log("Always-use-Home-Town is on, skipping GPS entirely", TAG)
            return store.getHomeTownSync()?.toResolved(LocationSource.HOME_TOWN)
        }

        val live = liveLocationProvider.getLastKnownLocation()
            ?: liveLocationProvider.requestFreshFix(freshFixTimeoutMillis)
        if (live != null) {
            // reverseGeocode is blocking network I/O — callers construct this resolver from a
            // plain rememberCoroutineScope(), which runs on Main, so this must hop to IO itself
            // rather than assume the caller already did.
            val name = if (needsReverseGeocode) {
                withContext(Dispatchers.IO) { geocoder?.reverseGeocode(live.lat, live.lon) }
            } else null
            store.setCachedLocation(CachedCoordinate(live.lat, live.lon, System.currentTimeMillis(), name))
            return ResolvedLocation(live.lat, live.lon, LocationSource.LIVE, name)
        }

        val cached = store.getCachedLocationSync()
        if (cached != null) {
            val age = System.currentTimeMillis() - cached.timestampMillis
            if (store.getCacheTtlSync().isFresh(age)) {
                return ResolvedLocation(cached.lat, cached.lon, LocationSource.CACHE, cached.resolvedName)
            }
        }

        return store.getHomeTownSync()?.toResolved(LocationSource.HOME_TOWN)
    }

    private fun HomeTown.toResolved(source: LocationSource) = ResolvedLocation(lat, lon, source)

    companion object {
        /**
         * Convenience factory for app call sites — wires the real Android GPS provider and an
         * optional geocoder. Tests should construct [VoxLocationResolver] directly with fakes
         * instead of going through this factory.
         */
        fun create(
            context: Context,
            store: VoxLocationStore,
            needsReverseGeocode: Boolean = false,
            userAgent: String = VOX_NOMINATIM_USER_AGENT
        ): VoxLocationResolver = VoxLocationResolver(
            store = store,
            liveLocationProvider = AndroidLiveLocationProvider(context),
            needsReverseGeocode = needsReverseGeocode,
            geocoder = if (needsReverseGeocode) VoxNominatimGeocoder(userAgent) else null
        )

        /**
         * Sets Always-use-Home-Town and clears the cache atomically — toggling this on must wipe
         * any existing cached fix (spec: "no gps lock is ever tried" while it's on, and a
         * previously cached fix shouldn't cheat around that).
         */
        suspend fun setAlwaysUseHomeTown(store: VoxLocationStore, enabled: Boolean) {
            store.setAlwaysUseHomeTown(enabled)
            if (enabled) store.setCachedLocation(null)
        }
    }
}
