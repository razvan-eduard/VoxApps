package com.voxapps.calendarapp.data.preferences

import androidx.compose.runtime.Immutable

/**
 * Immutable snapshot of persisted Vox Calendar settings (mirrors vox-expenses' ExpensesSettings).
 *
 * - [isBiometricRequired]/[sessionTimeoutMinutes]: same read-gate semantics as vox-notes/vox-expenses.
 * - [language]: drives the app's own UI copy (LanguageManager) and the language instruction sent to
 *   Commander's LLM for voice-event parsing — one setting serves both.
 * - [defaultLayerId]/[autoCreateLayer]: how a voice/LLM-created event resolves its target layer when
 *   the spoken layer name doesn't match an existing one — same semantics as vox-expenses' equivalent
 *   voice-category fields.
 * - [attachPhotoOnScan]: whether a scanned document's photo is attached to the OCR-cleanup LLM call
 *   (multimodal engines only) — same semantics as vox-notes'/vox-expenses' equivalent field.
 * - [debugLoggingEnabled]: gates `com.voxapps.logging.Logger` output — off by default.
 * - [debugToastsEnabled]: gates `com.voxapps.logging.Logger` toast output — off by default.
 * - [themeDarkMode]/[themeColored]: same theme controls as vox-commander's AppSettings — "SYSTEM"/
 *   "LIGHT"/"DARK" and Material You dynamic color, fed into the shared `:core:design` VoxTheme.
 * - [onboardingCompleted]: whether the first-launch welcome + permissions flow has been shown.
 *   Device-local UI state, not portable user data — deliberately excluded from Hub export/import
 *   (mirrors vox-expenses' `appCacheJson` exclusion rationale).
 */
@Immutable
data class CalendarSettings(
    val isBiometricRequired: Boolean = false,
    val sessionTimeoutMinutes: Int = TIMEOUT_30M,
    val language: String = DEFAULT_LANGUAGE,
    val defaultLayerId: Long? = null,
    val autoCreateLayer: Boolean = false,
    val attachPhotoOnScan: Boolean = false,
    val debugLoggingEnabled: Boolean = false,
    val debugToastsEnabled: Boolean = false,
    val themeDarkMode: String = THEME_SYSTEM,
    val themeColored: Boolean = true,
    val onboardingCompleted: Boolean = false
) {
    companion object {
        const val TIMEOUT_30M = 30
        const val TIMEOUT_1H = 60
        const val TIMEOUT_1D = 1440
        const val TIMEOUT_UNLIMITED = -1
        const val DEFAULT_LANGUAGE = "en"

        const val THEME_SYSTEM = "SYSTEM"
        const val THEME_LIGHT = "LIGHT"
        const val THEME_DARK = "DARK"
    }
}
