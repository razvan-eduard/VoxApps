package com.voxapps.calendarapp.data.preferences

import com.voxapps.design.notifications.VoxNotificationPrefs
import com.voxapps.recordflow.FieldWeight
import com.voxapps.recordflow.FlowSupport
import com.voxapps.recordflow.LlmLevel
import com.voxapps.recordflow.RecordSource

import androidx.compose.runtime.Immutable
import com.voxapps.design.color.VoxColorPalette
import com.voxapps.design.effects.TodayEffect
import com.voxapps.design.effects.TodayEffectStyle

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
 * - [showEventDetailsInWidget]: whether the home-screen widget shows each entry's description
 *   under its title (up to 2 lines) or just the title row. On by default to match prior behavior.
 * - [widgetBorderEnabled]/[widgetBorderThicknessDp]/[widgetBorderColorArgb]: whether the
 *   home-screen widget's day-cards draw an outline border, and its thickness/color if so. Border
 *   on by default (matches prior hardcoded behavior); color defaults to the first shared preset
 *   in [VoxColorPalette] rather than a hardcoded hex so it stays in sync with that palette.
 * - [todayEffect]/[todayEffectColor]/[todayEffectColor2]: which highlight effect (if any) draws
 *   around the in-app "today" card, and its color(s) — [todayEffectColor2] is only set when the
 *   user turns on the gradient option, `null` otherwise. The effect itself is not yet implemented
 *   (see `com.voxapps.design.effects.ApplyTodayEffect`); these fields exist so the settings UI and
 *   call-site wiring are ready ahead of it.
 * - [todoBleedToCalendar]: whether a to-do item's due date/time makes it show up on the standard
 *   Month/Week/Day/Year grid. The underlying `CalendarEntry` row is always created/kept (so its
 *   reminder keeps firing regardless) — this setting only filters it out of grid rendering when off.
 * - [animationsEnabled]: gates decorative transition animations (e.g. the Calendar-to-To-do-lists
 *   screen flip) — off disables them for users who find them distracting or slow.
 */
@Immutable
data class CalendarSettings(
    /** How far a model is let into making an entry from a scan — see [SCAN_FLOW_SUPPORT]. Stored as
     *  the rung's name; defaults to the fullest behaviour, which is what installs had before. */
    val scanLlmLevel: String = "FULL",
    /** How far a model is let into making an entry from a spoken sentence — see
     *  [VOICE_FLOW_SUPPORT]. Stored as the rung's name; defaults to the fullest behaviour. */
    val voiceLlmLevel: String = "FULL",
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
    val isGridView: Boolean = false,
    val showEventDetailsInWidget: Boolean = true,
    val widgetBorderEnabled: Boolean = true,
    val widgetBorderThicknessDp: Int = THICKNESS_MEDIUM,
    val widgetBorderColorArgb: Long = VoxColorPalette.presets.first(),
    val todayEffect: String = TodayEffect.NONE.name,
    val todayEffectStyle: String = TodayEffectStyle.RING.name,
    val todayEffectColor: Long = TODAY_EFFECT_DEFAULT_COLOR,
    val todayEffectColor2: Long? = null,
    val todayEffectSpeed: Float = 1f,
    val todayEffectShowInWidget: Boolean = true,
    val notificationsSystemDefault: Boolean = true,
    val notificationsVibrationEnabled: Boolean = true,
    val notificationsSoundUri: String? = null,
    val notificationsVolume: Int = 100,
    val notificationsLength: String = LENGTH_SHORT,
    val notificationsChannelVersion: Int = 1,
    val todoBleedToCalendar: Boolean = true,
    val animationsEnabled: Boolean = true,
    /** Off by default — when on, manual edit-saves teach word-level spelling corrections (see
     *  :core:fieldmemory) applied to future LLM-captured entries. Calendar has no suggestion
     *  surface, so corrections here apply directly (exact tier only). */
    val fieldCorrectionMemoryEnabled: Boolean = false,
    /** Consecutive identical corrections before one becomes active: [CORRECTION_SPEED_INSTANT]/
     *  [CORRECTION_SPEED_FAST]/[CORRECTION_SPEED_MEDIUM]/[CORRECTION_SPEED_SLOW]. */
    val fieldCorrectionThreshold: Int = CORRECTION_SPEED_MEDIUM
) {
    companion object {
        /**
         * What this app can honestly do with a scanned page.
         *
         * The offline rung is real but narrow: an entry needs a moment in time, and the only one a
         * device can establish without interpreting anything is a date written as digits. Where the
         * page carries one, the scan becomes an entry; where it does not, nothing is created, because
         * an entry filed on a guessed day is worse than a scan that visibly did nothing.
         *
         * The rungs in between are absent for want of anywhere to put a proposal.
         */
        val SCAN_FLOW_SUPPORT = FlowSupport(
            source = RecordSource.SCAN,
            supported = setOf(LlmLevel.NONE, LlmLevel.FULL),
            default = LlmLevel.FULL,
            // An entry is one coarse thing — a title, a day, an hour. It carries no list of rows
            // whose individual correctness nobody could check.
            weights = setOf(FieldWeight.HEAD)
        )

        /**
         * What this app can do with a spoken entry.
         *
         * Two rungs. At [LlmLevel.FULL] the sentence is sent for interpretation. At [LlmLevel.NONE]
         * nothing leaves the device — and nothing is settled on it either: an entry needs a moment
         * in time, and "next Tuesday at nine" is exactly the kind of phrase
         * [com.voxapps.textmatch.extract.DateTimeExtractor] declines to read — it settles digits,
         * not language. So the offline rung files the sentence as a dateless to-do in the review
         * list — see [com.voxapps.calendarapp.domain.llm.CalendarVoiceFlow.queueForReview] — rather
         * than an entry on a guessed day.
         *
         * The rungs in between are absent for want of anywhere to put a proposal.
         */
        val VOICE_FLOW_SUPPORT = FlowSupport(
            source = RecordSource.VOICE,
            supported = setOf(LlmLevel.NONE, LlmLevel.FULL),
            default = LlmLevel.FULL,
            weights = setOf(FieldWeight.HEAD)
        )

        /** The stored rung, or the fullest behaviour where the value is unreadable. */
        fun scanLevelOf(stored: String): LlmLevel =
            LlmLevel.entries.firstOrNull { it.name == stored } ?: LlmLevel.FULL

        /** The stored rung clamped to what voice supports, or the fullest behaviour where the
         *  value is unreadable. */
        fun voiceLevelOf(stored: String): LlmLevel =
            LlmLevel.entries.firstOrNull { it.name == stored }
                ?.takeIf { it in VOICE_FLOW_SUPPORT.supported }
                ?: LlmLevel.FULL

        const val TIMEOUT_30M = 30
        const val TIMEOUT_1H = 60
        const val TIMEOUT_1D = 1440
        const val TIMEOUT_UNLIMITED = -1
        const val DEFAULT_LANGUAGE = "en"

        const val THEME_SYSTEM = "SYSTEM"
        const val THEME_LIGHT = "LIGHT"
        const val THEME_DARK = "DARK"

        const val THICKNESS_THIN = 1
        const val THICKNESS_MEDIUM = 2
        const val THICKNESS_THICK = 4

        /** A warm orange — a reasonable default for an as-yet-unimplemented fire/glow effect. */
        const val TODAY_EFFECT_DEFAULT_COLOR = 0xFFFF6D00L

        const val LENGTH_SHORT = "SHORT"
        const val LENGTH_MEDIUM = "MEDIUM"
        const val LENGTH_LONG = "LONG"

        const val CORRECTION_SPEED_INSTANT = 1
        const val CORRECTION_SPEED_FAST = 2
        const val CORRECTION_SPEED_MEDIUM = 3
        const val CORRECTION_SPEED_SLOW = 5
    }
}

/** How this app was told to sound its alerts, in the shape [VoxNotifier] takes. */
fun CalendarSettings.notificationPrefs() = VoxNotificationPrefs(
    systemDefault = notificationsSystemDefault,
    channelVersion = notificationsChannelVersion,
    soundUri = notificationsSoundUri,
    volume = notificationsVolume,
    length = notificationsLength,
    vibrationEnabled = notificationsVibrationEnabled
)
