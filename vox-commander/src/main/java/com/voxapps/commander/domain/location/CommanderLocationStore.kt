package com.voxapps.commander.domain.location

import android.content.Context
import com.voxapps.commander.data.preferences.SettingsRepository
import com.voxapps.location.CachedCoordinate
import com.voxapps.location.HomeTown
import com.voxapps.location.LocationCacheTtl
import com.voxapps.location.PrefsCachedFix
import com.voxapps.location.VoxLocationStore

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

    private val cachedFix = PrefsCachedFix(context)

    override fun getCachedLocationSync(): CachedCoordinate? = cachedFix.get()

    override fun getCacheTtlSync(): LocationCacheTtl =
        LocationCacheTtl.entries.find { it.name == settingsRepo.getLocationCacheTtlSync() } ?: LocationCacheTtl.ONE_DAY

    override fun getHomeTownSync(): HomeTown? {
        val lat = settingsRepo.getLocationHomeTownLatSync()
        val lon = settingsRepo.getLocationHomeTownLonSync()
        return if (lat != null && lon != null) HomeTown(lat, lon) else null
    }

    override fun getAlwaysUseHomeTownSync(): Boolean = settingsRepo.getLocationAlwaysUseHomeTownSync()

    override suspend fun setCachedLocation(coord: CachedCoordinate?) = cachedFix.set(coord)

    override suspend fun setCacheTtl(ttl: LocationCacheTtl) = settingsRepo.setLocationCacheTtl(ttl.name)

    override suspend fun setHomeTown(homeTown: HomeTown?) =
        settingsRepo.setLocationHomeTown(homeTown?.lat, homeTown?.lon)

    override suspend fun setAlwaysUseHomeTown(enabled: Boolean) = settingsRepo.setLocationAlwaysUseHomeTown(enabled)
}
