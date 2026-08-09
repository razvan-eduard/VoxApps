package com.voxapps.location

enum class LocationSource { LIVE, CACHE, HOME_TOWN }

/** What [VoxLocationResolver.resolveLocation] returns — a coordinate plus where it came from. */
data class ResolvedLocation(
    val lat: Double,
    val lon: Double,
    val source: LocationSource,
    val displayName: String? = null
)
