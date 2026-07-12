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
 * - [debugLoggingEnabled]: gates `com.voxapps.logging.Logger` output — off by default.
 */
@Immutable
data class CalendarSettings(
    val isBiometricRequired: Boolean = false,
    val sessionTimeoutMinutes: Int = TIMEOUT_30M,
    val language: String = DEFAULT_LANGUAGE,
    val defaultLayerId: Long? = null,
    val autoCreateLayer: Boolean = false,
    val debugLoggingEnabled: Boolean = false
) {
    companion object {
        const val TIMEOUT_30M = 30
        const val TIMEOUT_1H = 60
        const val TIMEOUT_1D = 1440
        const val TIMEOUT_UNLIMITED = -1
        const val DEFAULT_LANGUAGE = "en"
    }
}
