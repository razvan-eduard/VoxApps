package com.voxapps.calendarapp.data.preferences

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
    val onboardingCompleted: Boolean = false,
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
    val notificationsChannelVersion: Int = 1
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

        const val THICKNESS_THIN = 1
        const val THICKNESS_MEDIUM = 2
        const val THICKNESS_THICK = 4

        /** A warm orange — a reasonable default for an as-yet-unimplemented fire/glow effect. */
        const val TODAY_EFFECT_DEFAULT_COLOR = 0xFFFF6D00L

        const val LENGTH_SHORT = "SHORT"
        const val LENGTH_MEDIUM = "MEDIUM"
        const val LENGTH_LONG = "LONG"
    }
}
