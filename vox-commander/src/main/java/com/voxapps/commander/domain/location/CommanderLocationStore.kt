package com.voxapps.commander.domain.location

import android.content.Context
import com.voxapps.commander.data.preferences.SettingsRepository
import com.voxapps.location.CachedCoordinate
import com.voxapps.location.HomeTown
import com.voxapps.location.LocationCacheTtl
import com.voxapps.location.VoxLocationStore

private const val PREFS_NAME = "location_cache"
private const val KEY_LAT = "last_lat"
private const val KEY_LON = "last_lon"
private const val KEY_TIMESTAMP = "last_timestamp"
private const val KEY_NAME = "last_name"

/** How far a fix may move and still be described by the same cached place name. */
private const val SAME_PLACE_METRES = 500.0

/**
 * Adapts [SettingsRepository] (Home Town / cache TTL / always-use — real user preferences,
 * exported/imported with the rest of Commander's settings) plus a dedicated SharedPreferences
 * file for the cached fix itself (mirrors the pre-unification `LocationHelper`'s own
 * "location_cache" file) — kept out of the DataStore-backed settings snapshot since a GPS fix
 * writes far more often than the user edits actual preferences.
 */
class CommanderLocationStore(
    private val context: Context,
    private val settingsRepo: SettingsRepository
) : VoxLocationStore {

    private val prefs by lazy { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    override fun getCachedLocationSync(): CachedCoordinate? {
        if (!prefs.contains(KEY_LAT) || !prefs.contains(KEY_LON)) return null
        return CachedCoordinate(
            lat = prefs.getFloat(KEY_LAT, 0f).toDouble(),
            lon = prefs.getFloat(KEY_LON, 0f).toDouble(),
            timestampMillis = prefs.getLong(KEY_TIMESTAMP, 0L),
            resolvedName = prefs.getString(KEY_NAME, null)
        )
    }

    override fun getCacheTtlSync(): LocationCacheTtl =
        LocationCacheTtl.entries.find { it.name == settingsRepo.getLocationCacheTtlSync() } ?: LocationCacheTtl.ONE_DAY

    override fun getHomeTownSync(): HomeTown? {
        val lat = settingsRepo.getLocationHomeTownLatSync()
        val lon = settingsRepo.getLocationHomeTownLonSync()
        return if (lat != null && lon != null) HomeTown(lat, lon) else null
    }

    override fun getAlwaysUseHomeTownSync(): Boolean = settingsRepo.getLocationAlwaysUseHomeTownSync()

    override suspend fun setCachedLocation(coord: CachedCoordinate?) {
        if (coord == null) {
            prefs.edit().clear().apply()
            return
        }
        // The place name belongs to the coordinates, not to the writer.
        //
        // Reverse geocoding is a display concern, so only the settings screen constructs a resolver
        // that asks for it; the voice search path deliberately does not, to keep a Nominatim round
        // trip out of every spoken query. Both write this one record, so a nameless fix used to
        // delete a name that was already known and correct — the settings screen then showed bare
        // coordinates until someone pressed refresh. A writer that does not know the name now leaves
        // the known one alone, provided the position has not meaningfully moved.
        val previous = getCachedLocationSync()
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
     * Whether two fixes are close enough to share a place name.
     *
     * A town name stays true across a town, so the threshold is generous — but not unlimited: a
     * stale name on a fix from somewhere else is worse than no name, since the user would read it as
     * where they are. Equirectangular approximation, which is accurate well beyond this distance and
     * avoids trigonometry for a comparison this coarse.
     */
    private fun isSamePlace(a: CachedCoordinate, b: CachedCoordinate): Boolean {
        val metresPerDegree = 111_320.0
        val dLat = (a.lat - b.lat) * metresPerDegree
        val dLon = (a.lon - b.lon) * metresPerDegree * kotlin.math.cos(Math.toRadians(a.lat))
        return kotlin.math.hypot(dLat, dLon) <= SAME_PLACE_METRES
    }

    override suspend fun setCacheTtl(ttl: LocationCacheTtl) = settingsRepo.setLocationCacheTtl(ttl.name)

    override suspend fun setHomeTown(homeTown: HomeTown?) =
        settingsRepo.setLocationHomeTown(homeTown?.lat, homeTown?.lon)

    override suspend fun setAlwaysUseHomeTown(enabled: Boolean) = settingsRepo.setLocationAlwaysUseHomeTown(enabled)
}
