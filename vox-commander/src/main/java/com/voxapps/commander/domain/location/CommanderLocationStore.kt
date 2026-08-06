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
        prefs.edit()
            .putFloat(KEY_LAT, coord.lat.toFloat())
            .putFloat(KEY_LON, coord.lon.toFloat())
            .putLong(KEY_TIMESTAMP, coord.timestampMillis)
            .apply {
                if (coord.resolvedName != null) putString(KEY_NAME, coord.resolvedName) else remove(KEY_NAME)
            }
            .apply()
    }

    override suspend fun setCacheTtl(ttl: LocationCacheTtl) = settingsRepo.setLocationCacheTtl(ttl.name)

    override suspend fun setHomeTown(homeTown: HomeTown?) =
        settingsRepo.setLocationHomeTown(homeTown?.lat, homeTown?.lon)

    override suspend fun setAlwaysUseHomeTown(enabled: Boolean) = settingsRepo.setLocationAlwaysUseHomeTown(enabled)
}
