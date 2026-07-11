package com.voxapps.apppicker

import androidx.compose.runtime.Immutable

/**
 * Minimal, app-agnostic app model for [AppPickerCard] — callers map their own richer model (e.g.
 * a domain/intent-aware registry entry, or a raw PackageManager query result) into this rather than
 * this module knowing about any specific app's data classes.
 */
@Immutable
data class AppPickerEntry(
    val packageName: String,
    val displayName: String,
    val isSystemApp: Boolean = false
)
