package com.voxapps.expenses.domain.location

import android.content.Context
import com.voxapps.expenses.data.preferences.ExpensesSettingsRepository
import com.voxapps.location.CachedCoordinate
import com.voxapps.location.HomeTown
import com.voxapps.location.LocationCacheTtl
import com.voxapps.location.PrefsCachedFix
import com.voxapps.location.VoxLocationResolver
import com.voxapps.location.VoxLocationStore

/**
 * Adapts [ExpensesSettingsRepository] (Home Town / cache TTL / always-use — real user preferences)
 * plus a dedicated SharedPreferences file for the cached fix itself (mirrors vox-commander's
 * `CommanderLocationStore` and the pre-unification `ExpensesLocationHelper`'s own city cache) —
 * kept out of the DataStore-backed settings snapshot since a GPS fix writes far more often than
 * the user edits actual preferences.
 */
class ExpensesLocationStore(
    private val context: Context,
    private val settingsRepo: ExpensesSettingsRepository
) : VoxLocationStore {

    private val cachedFix = PrefsCachedFix(context)

    override fun getCachedLocationSync(): CachedCoordinate? = cachedFix.get()

    override fun getCacheTtlSync(): LocationCacheTtl =
        LocationCacheTtl.entries.find { it.name == settingsRepo.getSnapshot().locationCacheTtl } ?: LocationCacheTtl.ONE_DAY

    override fun getHomeTownSync(): HomeTown? {
        val snapshot = settingsRepo.getSnapshot()
        val lat = snapshot.locationHomeTownLat
        val lon = snapshot.locationHomeTownLon
        return if (lat != null && lon != null) HomeTown(lat, lon) else null
    }

    override fun getAlwaysUseHomeTownSync(): Boolean = settingsRepo.getSnapshot().locationAlwaysUseHomeTown

    override suspend fun setCachedLocation(coord: CachedCoordinate?) = cachedFix.set(coord)

    override suspend fun setCacheTtl(ttl: LocationCacheTtl) = settingsRepo.setLocationCacheTtl(ttl.name)

    override suspend fun setHomeTown(homeTown: HomeTown?) =
        settingsRepo.setLocationHomeTown(homeTown?.lat, homeTown?.lon)

    override suspend fun setAlwaysUseHomeTown(enabled: Boolean) = settingsRepo.setLocationAlwaysUseHomeTown(enabled)
}

/**
 * Resolves a display city name for prefilling an expense's location field — the same role
 * `ExpensesLocationHelper.resolveCurrentCity` used to play, now backed by the shared resolver.
 * Returns null exactly when the old helper did: no permission, no fix obtainable, or reverse
 * geocoding failed — every caller treats null as "leave the field blank."
 */
suspend fun resolveCurrentCityName(context: Context, settingsRepo: ExpensesSettingsRepository): String? =
    VoxLocationResolver.create(context, ExpensesLocationStore(context, settingsRepo), needsReverseGeocode = true)
        .resolveLocation()?.displayName
