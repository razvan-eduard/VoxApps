package com.voxapps.location

/** A GPS fix written after a successful live resolve, plus its Nominatim name if one was resolved. */
data class CachedCoordinate(
    val lat: Double,
    val lon: Double,
    val timestampMillis: Long,
    val resolvedName: String? = null
)
