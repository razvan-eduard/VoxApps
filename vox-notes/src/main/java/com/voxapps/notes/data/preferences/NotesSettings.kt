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
 */
@Immutable
data class NotesSettings(
    val isBiometricRequired: Boolean = false,
    val sessionTimeoutMinutes: Int = TIMEOUT_30M,
    val defaultVoiceCategoryId: Long? = null,
    val voiceSaveToastEnabled: Boolean = false
) {
    companion object {
        const val TIMEOUT_30M = 30
        const val TIMEOUT_1H = 60
        const val TIMEOUT_1D = 1440
        const val TIMEOUT_UNLIMITED = -1
    }
}
