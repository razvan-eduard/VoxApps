package com.voxapps.location.ui

import com.voxapps.location.HomeTown
import com.voxapps.location.LocationCacheTtl
import com.voxapps.location.ResolvedLocation

/** What [VoxLocationSettingsCard] renders — the host app builds this from its own repo snapshot. */
data class VoxLocationUiState(
    val lastKnownLocation: ResolvedLocation?,
    val homeTown: HomeTown?,
    val cacheTtl: LocationCacheTtl,
    val alwaysUseHomeTown: Boolean,
    val isRefreshing: Boolean = false
)

/**
 * Which sub-features [VoxLocationSettingsCard] shows. Every feature defaults to visible — both
 * current apps enable everything — but each is independently toggleable so a future caller can
 * e.g. disable the Home Town override without any change to this module.
 */
data class VoxLocationCardFeatures(
    val showHomeTownOverride: Boolean = true,
    val showCacheTtlSelector: Boolean = true,
    val showRefreshButton: Boolean = true,
    val showLastLocationDisplay: Boolean = true,
    val showAlwaysUseToggle: Boolean = true
)

/** All user-facing copy, overridable per host app's own i18n system. */
data class VoxLocationStrings(
    val sectionTitle: String = "Location",
    val lastLocationLabel: String = "Last known location",
    val lastLocationUnavailable: String = "No location available yet",
    val refreshButton: String = "Refresh",
    val cacheTtlLabel: String = "Cache duration",
    val cacheTtlNone: String = "None",
    val cacheTtlOneDay: String = "1 day",
    val cacheTtlOneWeek: String = "1 week",
    val cacheTtlOneMonth: String = "1 month",
    val cacheTtlForever: String = "Forever",
    val alwaysUseToggleLabel: String = "Always use this location",
    val alwaysUseToggleDescription: String = "Skip GPS entirely and always use Home town below. Clears any cached location.",
    val homeTownTitle: String = "Home town",
    val homeTownDescription: String = "Used when GPS is unavailable and no cached location exists, or when \"Always use this location\" is on.",
    val homeTownSearchLabel: String = "Search a place",
    val latitudeLabel: String = "Latitude",
    val longitudeLabel: String = "Longitude",
    val clearButton: String = "Clear",
    val pickOnMapButton: String = "Pick on map"
)

internal fun VoxLocationStrings.labelFor(ttl: LocationCacheTtl): String = when (ttl) {
    LocationCacheTtl.NONE -> cacheTtlNone
    LocationCacheTtl.ONE_DAY -> cacheTtlOneDay
    LocationCacheTtl.ONE_WEEK -> cacheTtlOneWeek
    LocationCacheTtl.ONE_MONTH -> cacheTtlOneMonth
    LocationCacheTtl.FOREVER -> cacheTtlForever
}
