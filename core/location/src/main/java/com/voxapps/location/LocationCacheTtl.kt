package com.voxapps.location

import java.util.concurrent.TimeUnit

/**
 * How long a cached GPS fix stays usable before [VoxLocationResolver] falls through to Home Town.
 * [NONE] means "never trust the cache" (every resolve either gets a live fix or falls to Home
 * Town); [FOREVER] means a cached fix never expires on its own (only an explicit refresh replaces
 * it).
 */
enum class LocationCacheTtl(private val maxAgeMillis: Long?) {
    NONE(0L),
    ONE_DAY(TimeUnit.DAYS.toMillis(1)),
    ONE_WEEK(TimeUnit.DAYS.toMillis(7)),
    ONE_MONTH(TimeUnit.DAYS.toMillis(30)),
    FOREVER(null);

    fun isFresh(ageMillis: Long): Boolean = when (this) {
        NONE -> false
        FOREVER -> true
        else -> ageMillis <= (maxAgeMillis ?: 0L)
    }
}
