package com.voxapps.location

import android.content.Context

private const val KEY_LAT = "last_lat"
private const val KEY_LON = "last_lon"
private const val KEY_TIMESTAMP = "last_timestamp"
private const val KEY_NAME = "last_name"

/** How far a fix may move and still be described by the same cached place name. */
private const val SAME_PLACE_METRES = 500.0

/**
 * The cached-GPS-fix record every caching [VoxLocationStore] keeps — one SharedPreferences file,
 * deliberately outside each app's DataStore-backed settings snapshot since a fix writes far more
 * often than the user edits preferences. Hosts delegate [VoxLocationStore.getCachedLocationSync]/
 * [VoxLocationStore.setCachedLocation] here and keep only their own preference-backed parts
 * (TTL, Home Town, always-use).
 *
 * The place name belongs to the coordinates, not to the writer: reverse geocoding is a display
 * concern, so some writers store a nameless fix — a writer that does not know the name leaves a
 * known, still-nearby one alone rather than deleting it (see [isSamePlace]).
 */
class PrefsCachedFix(context: Context, prefsName: String = "location_cache") {

    private val prefs by lazy { context.getSharedPreferences(prefsName, Context.MODE_PRIVATE) }

    fun get(): CachedCoordinate? {
        if (!prefs.contains(KEY_LAT) || !prefs.contains(KEY_LON)) return null
        return CachedCoordinate(
            lat = prefs.getFloat(KEY_LAT, 0f).toDouble(),
            lon = prefs.getFloat(KEY_LON, 0f).toDouble(),
            timestampMillis = prefs.getLong(KEY_TIMESTAMP, 0L),
            resolvedName = prefs.getString(KEY_NAME, null)
        )
    }

    fun set(coord: CachedCoordinate?) {
        if (coord == null) {
            prefs.edit().clear().apply()
            return
        }
        val previous = get()
        val name = coord.resolvedName
            ?: previous?.resolvedName?.takeIf { isSamePlace(previous, coord) }
        prefs.edit()
            .putFloat(KEY_LAT, coord.lat.toFloat())
            .putFloat(KEY_LON, coord.lon.toFloat())
            .putLong(KEY_TIMESTAMP, coord.timestampMillis)
            .apply {
                if (name != null) putString(KEY_NAME, name) else remove(KEY_NAME)
            }
            .apply()
    }

    /**
     * Whether two fixes are close enough to share a place name. A town name stays true across a
     * town, so the threshold is generous — but not unlimited: a stale name on a fix from somewhere
     * else is worse than no name. Equirectangular approximation, accurate well beyond this distance.
     */
    private fun isSamePlace(a: CachedCoordinate, b: CachedCoordinate): Boolean {
        val metresPerDegree = 111_320.0
        val dLat = (a.lat - b.lat) * metresPerDegree
        val dLon = (a.lon - b.lon) * metresPerDegree * kotlin.math.cos(Math.toRadians(a.lat))
        return kotlin.math.hypot(dLat, dLon) <= SAME_PLACE_METRES
    }
}
