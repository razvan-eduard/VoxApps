package com.voxapps.hub.data.preferences

import androidx.compose.runtime.Immutable

/**
 * Immutable snapshot of persisted Vox Hub settings. Hub otherwise holds no local database or
 * settings (see [com.voxapps.hub.di.HubContainer]'s doc comment) — this exists solely for the
 * theme controls every satellite app now exposes.
 *
 * - [themeDarkMode]/[themeColored]: same theme controls as vox-commander's AppSettings — "SYSTEM"/
 *   "LIGHT"/"DARK" and Material You dynamic color, fed into the shared `:core:design` VoxTheme.
 */
@Immutable
data class HubSettings(
    val themeDarkMode: String = THEME_SYSTEM,
    val themeColored: Boolean = true
) {
    companion object {
        const val THEME_SYSTEM = "SYSTEM"
        const val THEME_LIGHT = "LIGHT"
        const val THEME_DARK = "DARK"
    }
}
