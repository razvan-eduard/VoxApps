package com.voxapps.notes.data.preferences

import com.voxapps.recordflow.FieldWeight
import com.voxapps.recordflow.FlowSupport
import com.voxapps.recordflow.LlmLevel
import com.voxapps.recordflow.RecordSource

import androidx.compose.runtime.Immutable
import com.voxapps.design.effects.TodayEffect
import com.voxapps.design.effects.TodayEffectStyle

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
    /**
     * How far a model is let into making a note from a scan — see [SCAN_FLOW_SUPPORT].
     *
     * Stored as the rung's name. Defaults to the fullest behaviour, which is what installs had
     * before the setting existed.
     */
    val scanLlmLevel: String = "FULL",
    /** How far a model is let into tidying a spoken note — see [VOICE_FLOW_SUPPORT]. Stored as the
     *  rung's name; defaults to the offline rung, where the transcript is the note untouched. */
    val voiceLlmLevel: String = "NONE",
    /**
     * How much of the notes a device sync volunteers — a [com.voxapps.datahygiene.SyncLevel] name.
     * At MANUAL (the default) nothing leaves on its own, only records explicitly pushed from
     * multi-select; at SHARED the categories ticked per peer in Hub replicate continuously; at ALL
     * every note does. Governs sending only — what a merge accepts is unaffected. A device-local
     * choice, like the theme: deliberately not carried by export/import.
     */
    val syncLevel: String = com.voxapps.datahygiene.SyncLevel.MANUAL.name,
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
    val isGridView: Boolean = false,
    val themeDarkMode: String = THEME_SYSTEM,
    val themeColored: Boolean = true,
    val onboardingCompleted: Boolean = false,
    // --- BACKUP & RESTORE (local, shared :core:backup module's VoxBackupSettingsCard) — mirrors
    // vox-hub's AppBackupConfig shape/names, persisted here for this app's own local backup button,
    // independent of any Hub-triggered IPC export. ---
    val backupIncludeSettings: Boolean = true,
    val backupIncludeData: Boolean = true,
    val backupIncludeAttachments: Boolean = false,
    /** Wire-format string per [com.voxapps.ipc.VoxIpc.IMPORT_MODE_MERGE] etc., parsed via
     *  [com.voxapps.backup.VoxImportMode.fromWireValue]. */
    val backupImportMode: String = "merge",
    /** Off by default — attaching a photo costs real LLM tokens on top of the free OCR text a scan
     *  already provides. Only takes effect when Vision's own "send photo to AI" setting also
     *  provided a downscaled copy — this is the per-satellite half of that decision, not a
     *  standalone override (mirrors vox-expenses' identical toggle; Notes has no retry mechanism so
     *  there's no separate on-retry variant here). */
    val attachPhotoOnScan: Boolean = false,
    val scanImageRetention: String = RETENTION_ON_FAILURE,
    /** Which highlight effect (if any) draws around the in-app "today" card, and its color(s) —
     *  mirrors vox-calendar's identical fields. Not yet implemented, see
     *  `com.voxapps.design.effects.ApplyTodayEffect`. */
    val todayEffect: String = TodayEffect.NONE.name,
    val todayEffectStyle: String = TodayEffectStyle.RING.name,
    val todayEffectColor: Long = TODAY_EFFECT_DEFAULT_COLOR,
    val todayEffectColor2: Long? = null,
    val todayEffectSpeed: Float = 1f,
    val notificationsSystemDefault: Boolean = true,
    val notificationsVibrationEnabled: Boolean = true,
    val notificationsSoundUri: String? = null,
    val notificationsVolume: Int = 100,
    val notificationsLength: String = LENGTH_SHORT,
    val notificationsChannelVersion: Int = 1
) {
    companion object {
        /**
         * What this app can honestly do with a scanned page.
         *
         * Two rungs, and the reason is the same one that makes Notes' voice flow need no extraction
         * pass at all: a note's body *is* its text, so a scan with no model still produces the whole
         * record rather than a fragment of one. What a model adds here is a title and a category —
         * judgements, not content.
         *
         * The rungs between the ends are absent because there is nowhere to put a proposal: this
         * app has no per-field suggestion surface, so an answer can only be written or not asked for.
         */
        val SCAN_FLOW_SUPPORT = FlowSupport(
            source = RecordSource.SCAN,
            supported = setOf(LlmLevel.NONE, LlmLevel.FULL),
            default = LlmLevel.FULL,
            // A note has no fine detail: its text is the record, not an answer about the record.
            weights = setOf(FieldWeight.HEAD)
        )

        /**
         * What this app can do with a spoken note.
         *
         * Two rungs, the same pair the scan offers and for the same reason: a note's body *is* its
         * text, so the offline rung is not a reduced version of anything — the words become the
         * note exactly as they were heard, which is what an untouched install keeps doing. What a
         * model adds at the full rung is the same thing it adds to a scan: a tidied body, a title
         * and a category — judgements, not content. A reply that cannot be used falls back to the
         * raw transcript, so nothing spoken is ever lost.
         */
        val VOICE_FLOW_SUPPORT = FlowSupport(
            source = RecordSource.VOICE,
            supported = setOf(LlmLevel.NONE, LlmLevel.FULL),
            default = LlmLevel.NONE,
            weights = setOf(FieldWeight.HEAD)
        )

        /** The stored rung, or the fullest behaviour where the value is unreadable. */
        fun scanLevelOf(stored: String): LlmLevel =
            LlmLevel.entries.firstOrNull { it.name == stored } ?: LlmLevel.FULL

        /** The stored rung clamped to what voice supports. Falls back to the offline rung — the
         *  default here is also the privacy-preserving reading of an unreadable value. */
        fun voiceLevelOf(stored: String): LlmLevel =
            LlmLevel.entries.firstOrNull { it.name == stored }
                ?.takeIf { it in VOICE_FLOW_SUPPORT.supported }
                ?: LlmLevel.NONE

        /** The stored [syncLevel] as its enum; anything unreadable reads as the level that sends
         *  nothing unasked. */
        fun syncLevelOf(stored: String): com.voxapps.datahygiene.SyncLevel =
            com.voxapps.datahygiene.SyncLevel.fromStored(stored)

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

        const val LENGTH_SHORT = "SHORT"
        const val LENGTH_MEDIUM = "MEDIUM"
        const val LENGTH_LONG = "LONG"

        /** A warm orange — a reasonable default for an as-yet-unimplemented fire/glow effect. */
        const val TODAY_EFFECT_DEFAULT_COLOR = 0xFFFF6D00L
    }
}
