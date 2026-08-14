package com.voxapps.location

/**
 * A [VoxLocationStore] that remembers nothing — for hosts whose location use is strictly "where am
 * I right now" with no cache, no Home Town, no TTL (vox-calendar's GPS lock). Every read answers
 * empty and every write is dropped, so [VoxLocationResolver] built on this always attempts a live
 * fix and never falls back to stale state.
 */
class EphemeralLocationStore : VoxLocationStore {
    override fun getCachedLocationSync(): CachedCoordinate? = null
    override fun getCacheTtlSync(): LocationCacheTtl = LocationCacheTtl.entries.first()
    override fun getHomeTownSync(): HomeTown? = null
    override fun getAlwaysUseHomeTownSync(): Boolean = false

    override suspend fun setCachedLocation(coord: CachedCoordinate?) = Unit
    override suspend fun setCacheTtl(ttl: LocationCacheTtl) = Unit
    override suspend fun setHomeTown(homeTown: HomeTown?) = Unit
    override suspend fun setAlwaysUseHomeTown(enabled: Boolean) = Unit
}
