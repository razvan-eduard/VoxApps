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
    val scheduledNoteDedupInterval: String = INTERVAL_OFF
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
    }
}
