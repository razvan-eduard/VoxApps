package com.voxapps.expenses.data.preferences

import androidx.compose.runtime.Immutable

/**
 * Immutable snapshot of persisted Vox Expenses settings (mirrors vox-notes' NotesSettings). Fields are
 * added incrementally as each stage of the plan lands (voice-creation settings arrive with Stage 2,
 * category-merge/dedup scheduling with Stage 4, exchange-rate settings with Stage 5) rather than
 * speculatively declared up front.
 *
 * - [isBiometricRequired]/[sessionTimeoutMinutes]: same read-gate semantics as vox-notes.
 * - [language]: drives the app's own UI copy (LanguageManager) and the language instruction sent to
 *   Commander's LLM for voice-expense parsing — one setting serves both, mirroring vox-notes.
 * - [defaultCurrency]: the currency assigned to a new expense when the user hasn't picked one.
 * - [defaultVoiceCategoryId]/[voiceSaveToastEnabled]/[autoCreateVoiceCategory]: same semantics as
 *   vox-notes' equivalents, for expenses created via Commander's voice pipeline (Stage 2).
 * - [scheduledMergeInterval]/[scheduledExpenseDedupInterval]: how often Auto-Merge Categories /
 *   expense deduplication run automatically in the background ([INTERVAL_OFF] = manual button only).
 *   Independent settings, mirroring vox-notes' precedent of not coupling the two features' schedules.
 * - [homeCurrency]: the currency reports/totals are converted INTO when expenses carry a mix of
 *   currencies — distinct from [defaultCurrency] (what a *new* expense defaults to), since someone
 *   might spend mostly in a foreign currency but still want to see totals in their home one. The
 *   actual exchange-rate API key is a real secret and deliberately NOT part of this DataStore-backed
 *   settings snapshot — see [com.voxapps.expenses.data.ExchangeRateApiKeyStore].
 * - [paymentSourcePackages]: opt-in allowlist of installed app package names whose notifications
 *   [com.voxapps.expenses.receiver.PaymentNotificationListenerService] is allowed to inspect —
 *   defaults to empty (nothing processed until the user explicitly picks apps), since that service
 *   can otherwise see every notification on the device.
 * - [bankingSourcePackages]: the subset of [paymentSourcePackages] the user has starred as "this is
 *   definitely a bank app" — when a captured notification comes from one of these,
 *   [com.voxapps.expenses.receiver.PaymentNotificationListenerService] tells the LLM that app's
 *   name authoritatively rather than leaving `bank` to be guessed from raw notification text.
 * - [autoAcceptNotificationExpenses]: off by default — when on, a notification-derived expense that
 *   parses successfully is inserted straight away (same as manual/voice entry, editable afterward)
 *   instead of sitting in the pending-review queue for an explicit Approve tap. See
 *   [com.voxapps.expenses.receiver.LlmResultReceiver]'s `NOTIFICATION_EXPENSE_PARSE` handling.
 * - [debugLoggingEnabled]: gates `com.voxapps.logging.Logger` output — off by default.
 * - [vatDisplayEnabled]: shows the optional per-line-item net/VAT/gross breakdown (see
 *   `ExpenseLineItem.netAmount`/`vatAmount`/`grossAmount`) on the edit screen — off by default since
 *   most receipts don't carry this breakdown and it would just be empty, unused fields for most users.
 * - [decimalSeparator]: which character the amount/quantity/price text fields on the edit screen use
 *   and expect, app-wide — deliberately NOT derived from the device's default `Locale` (see
 *   `ExpenseEditScreen.kt`'s `formatDecimal`/`parseDecimalOrNull`): a device set to a comma-decimal
 *   locale (e.g. Romanian) used to silently break `toDoubleOrNull()` round-tripping on any pre-filled
 *   numeric field (Save staying permanently disabled), since Kotlin's numeric parsing is always
 *   period-only regardless of locale. Defaults to period, matching how amounts are stored internally.
 * - [calendarViewEnabled]: swaps the main screen's chronological list for a month-paged, per-day
 *   calendar view (see `:core:calendar`). Off by default — explicit opt-in, same rationale as
 *   vox-notes' equivalent setting.
 * - [appCacheJson]: persisted JSON snapshot of [com.voxapps.expenses.domain.apps.LauncherAppsCache],
 *   so the launcher-app scan backing the notification-source picker only runs once ever (first
 *   launch), not on every app start — mirrors vox-commander's `AppRegistry`/`appCacheJson` pattern.
 * - [themeDarkMode]/[themeColored]: same theme controls as vox-commander's AppSettings — "SYSTEM"/
 *   "LIGHT"/"DARK" and Material You dynamic color, fed into the shared `:core:design` VoxTheme.
 * - [onboardingCompleted]: whether the first-launch welcome + permissions flow has been shown.
 *   Device-local UI state, not portable user data — deliberately excluded from Hub export/import,
 *   same rationale as [appCacheJson].
 */
@Immutable
data class ExpensesSettings(
    val isBiometricRequired: Boolean = false,
    val sessionTimeoutMinutes: Int = TIMEOUT_30M,
    val language: String = DEFAULT_LANGUAGE,
    val defaultCurrency: String = DEFAULT_CURRENCY,
    val defaultVoiceCategoryId: Long? = null,
    val voiceSaveToastEnabled: Boolean = false,
    val autoCreateVoiceCategory: Boolean = false,
    val scheduledMergeInterval: String = INTERVAL_OFF,
    val scheduledExpenseDedupInterval: String = INTERVAL_OFF,
    val homeCurrency: String = DEFAULT_CURRENCY,
    val paymentSourcePackages: Set<String> = emptySet(),
    val bankingSourcePackages: Set<String> = emptySet(),
    val autoAcceptNotificationExpenses: Boolean = false,
    val debugLoggingEnabled: Boolean = false,
    val vatDisplayEnabled: Boolean = false,
    val decimalSeparator: String = DECIMAL_PERIOD,
    val calendarViewEnabled: Boolean = false,
    val debugToastsEnabled: Boolean = false,
    val appCacheJson: String? = null,
    val themeDarkMode: String = THEME_SYSTEM,
    val themeColored: Boolean = true,
    val onboardingCompleted: Boolean = false,
    /** Off by default — attaching a photo costs real LLM tokens on top of the free OCR text a scan
     *  already provides. Only takes effect when Vision's own "send photo to AI" setting also
     *  provided a downscaled copy — this is the per-satellite half of that decision, not a
     *  standalone override. See [attachPhotoOnRetry] for the separate retry-specific toggle. */
    val attachPhotoOnScan: Boolean = false,
    /** Separate from [attachPhotoOnScan] since retry (re-sending already-staged OCR text after a
     *  failed parse) is a distinct, less frequent code path — a user might want the photo attached
     *  on a fresh scan but not want it re-sent every retry, or vice versa. */
    val attachPhotoOnRetry: Boolean = false,
    /** On by default (matches the behavior before this toggle existed) — one switch governing every
     *  place an expense's location field can get auto-filled from GPS (scan, voice, and manual
     *  entry — see [com.voxapps.expenses.domain.location.ExpensesLocationHelper]'s callers).
     *  Independent of the OS location permission itself: granting that in onboarding only makes
     *  the feature *possible*, this is the separate "and do I actually want it" control. */
    val locationPrefillEnabled: Boolean = true
) {
    companion object {
        const val TIMEOUT_30M = 30
        const val TIMEOUT_1H = 60
        const val TIMEOUT_1D = 1440
        const val TIMEOUT_UNLIMITED = -1
        const val DEFAULT_LANGUAGE = "en"
        const val DEFAULT_CURRENCY = "RON"

        const val INTERVAL_OFF = "OFF"
        const val INTERVAL_DAILY = "DAILY"
        const val INTERVAL_WEEKLY = "WEEKLY"
        const val INTERVAL_MONTHLY = "MONTHLY"

        const val DECIMAL_PERIOD = "period"
        const val DECIMAL_COMMA = "comma"

        const val THEME_SYSTEM = "SYSTEM"
        const val THEME_LIGHT = "LIGHT"
        const val THEME_DARK = "DARK"
    }
}
