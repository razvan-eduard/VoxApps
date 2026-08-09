package com.voxapps.location

/**
 * The seam a host app implements over its own existing settings repo (DataStore-backed) so
 * [VoxLocationResolver] and [com.voxapps.location.ui.VoxLocationSettingsCard] never depend on any
 * particular app's persistence layer. Sync reads mirror the existing per-app settings-repo
 * convention (an in-memory snapshot backing instant Compose reads); writes are suspend since
 * they hit DataStore.
 */
interface VoxLocationStore {
    fun getCachedLocationSync(): CachedCoordinate?
    fun getCacheTtlSync(): LocationCacheTtl
    fun getHomeTownSync(): HomeTown?
    fun getAlwaysUseHomeTownSync(): Boolean

    suspend fun setCachedLocation(coord: CachedCoordinate?)
    suspend fun setCacheTtl(ttl: LocationCacheTtl)
    suspend fun setHomeTown(homeTown: HomeTown?)
    suspend fun setAlwaysUseHomeTown(enabled: Boolean)
}
