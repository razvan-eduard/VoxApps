package com.voxapps.notes.data.preferences

import androidx.compose.runtime.Immutable

/**
 * Immutable snapshot of persisted VoxNotes settings (mirrors vox-commander's AppSettings).
 *
 * - [isBiometricRequired]: whether reading notes is gated behind a fingerprint/credential prompt.
 * - [sessionTimeoutMinutes]: idle window before the prompt is required again; [TIMEOUT_UNLIMITED]
 *   (-1) means the session never expires for the lifetime of the process.
 * - [defaultVoiceCategoryId]: category assigned to VoxCommander-created notes when none is spoken
 *   (null = uncategorized).
 * - [voiceSaveToastEnabled]: show a toast (text + category) when a note is saved via VoxCommander.
 * - [language]: drives both the app's own UI copy (LanguageManager) and the language instruction sent
 *   to Commander's LLM for the category Auto-Merge feature — one setting serves both, mirroring
 *   vox-commander's own precedent of reusing a single language setting for UI + NLU hints.
 * - [scheduledMergeInterval]: how often Auto-Merge Categories runs automatically in the background
 *   ([INTERVAL_OFF] = manual button only).
 * - [scheduledNoteDedupInterval]: same idea, for the note-deduplication feature — kept as its own
 *   independent setting rather than reusing [scheduledMergeInterval] since the two features run on
 *   independent schedules.
 * - [debugLoggingEnabled]/[debugToastsEnabled]: gate `com.voxapps.logging.Logger` output to logcat
 *   and to on-screen toasts respectively. Both off by default so a normal install never floods
 *   logcat; flip them on only while actively debugging (mirrors vox-expenses' identical pair).
 * - [calendarViewEnabled]: swaps the main screen's chronological list for a month-paged, per-day
 *   calendar view (see `:core:calendar`). Off by default — it changes the primary browsing
 *   paradigm, so it's an explicit opt-in rather than a silent default switch.
 * - [themeDarkMode]/[themeColored]: same theme controls as vox-commander's AppSettings — "SYSTEM"/
 *   "LIGHT"/"DARK" and Material You dynamic color, fed into the shared `:core:design` VoxTheme.
 * - [onboardingCompleted]: whether the first-launch welcome + permissions flow has been shown.
 *   Device-local UI state, not portable user data — deliberately excluded from Hub export/import
 *   (mirrors vox-expenses' `appCacheJson` exclusion rationale).
 * - [scanImageRetention]: whether a scanned photo is kept as an attachment on the resulting note
 *   after Commander's cleanup finishes — independent of [attachPhotoOnScan], which only controls
 *   whether the LLM *sees* the photo during that cleanup call. [RETENTION_ON_FAILURE] (the default)
 *   is what turns an otherwise-discarded "Unclear Document" scan into a raw note holding just the
 *   photo instead of losing it outright.
 */
@Immutable
data class NotesSettings(
    val isBiometricRequired: Boolean = false,
    val sessionTimeoutMinutes: Int = TIMEOUT_30M,
    val defaultVoiceCategoryId: Long? = null,
    val voiceSaveToastEnabled: Boolean = false,
    /** If true, a spoken category that doesn't exist is created; otherwise it falls back. */
    val autoCreateVoiceCategory: Boolean = false,
    val language: String = DEFAULT_LANGUAGE,
    val scheduledMergeInterval: String = INTERVAL_OFF,
    val scheduledNoteDedupInterval: String = INTERVAL_OFF,
    val debugLoggingEnabled: Boolean = false,
    val debugToastsEnabled: Boolean = false,
    val calendarViewEnabled: Boolean = false,
    val themeDarkMode: String = THEME_SYSTEM,
    val themeColored: Boolean = true,
    val onboardingCompleted: Boolean = false,
    /** Off by default — attaching a photo costs real LLM tokens on top of the free OCR text a scan
     *  already provides. Only takes effect when Vision's own "send photo to AI" setting also
     *  provided a downscaled copy — this is the per-satellite half of that decision, not a
     *  standalone override (mirrors vox-expenses' identical toggle; Notes has no retry mechanism so
     *  there's no separate on-retry variant here). */
    val attachPhotoOnScan: Boolean = false,
    val scanImageRetention: String = RETENTION_ON_FAILURE
) {
    companion object {
        const val TIMEOUT_30M = 30
        const val TIMEOUT_1H = 60
        const val TIMEOUT_1D = 1440
        const val TIMEOUT_UNLIMITED = -1
        const val DEFAULT_LANGUAGE = "en"

        const val INTERVAL_OFF = "OFF"
        const val INTERVAL_DAILY = "DAILY"
        const val INTERVAL_WEEKLY = "WEEKLY"
        const val INTERVAL_MONTHLY = "MONTHLY"

        const val THEME_SYSTEM = "SYSTEM"
        const val THEME_LIGHT = "LIGHT"
        const val THEME_DARK = "DARK"

        const val RETENTION_NEVER = "NEVER"
        const val RETENTION_ON_FAILURE = "ON_FAILURE"
        const val RETENTION_ALWAYS = "ALWAYS"
    }
}
