package com.voxapps.expenses.data.preferences

import androidx.compose.runtime.Immutable
import com.voxapps.design.color.VoxColorPalette
import com.voxapps.design.effects.TodayEffect
import com.voxapps.design.effects.TodayEffectStyle

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
    val isGridView: Boolean = false,
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
    /** Off by default. When on, attaching the FIRST photo to an already-saved expense that
     *  currently has none at all (no original receipt scan, no manual attachment — see
     *  ExpenseAttachmentsSection's eligibility check) automatically triggers the same line-items
     *  rescan the manual chip does, no tap needed. Once the expense has at least one attachment,
     *  adding more never auto-triggers, even with this on — only the transition from zero to one
     *  does. Deleting attachments back down to zero makes it eligible again. */
    val autoRescanOnFirstAttachment: Boolean = false,
    /** Off by default — new, forced-navigation behavior a user must opt into. When on, as soon as a
     *  scanned receipt's LLM cleanup successfully creates its expense (not a voice-created one — see
     *  [com.voxapps.expenses.receiver.LlmResultReceiver]'s scan-specific branch), Expenses navigates
     *  straight to that expense's edit screen for review instead of leaving the user on the list. */
    val autoOpenScannedExpense: Boolean = false,
    /** On by default (matches the behavior before this toggle existed) — one switch governing every
     *  place an expense's location field can get auto-filled from GPS (scan, voice, and manual
     *  entry — see [com.voxapps.expenses.domain.location.resolveCurrentCityName]'s callers).
     *  Independent of the OS location permission itself: granting that in onboarding only makes
     *  the feature *possible*, this is the separate "and do I actually want it" control. */
    val locationPrefillEnabled: Boolean = true,
    /** "Home town" fallback, cache TTL, and "always use this location" — shared :core:location
     *  module, same fields as vox-commander's AppSettings equivalents. */
    val locationHomeTownLat: Double? = null,
    val locationHomeTownLon: Double? = null,
    /** [com.voxapps.location.LocationCacheTtl] enum name, e.g. "ONE_DAY". */
    val locationCacheTtl: String = "ONE_DAY",
    val locationAlwaysUseHomeTown: Boolean = false,
    /** Which engine(s) [com.voxapps.expenses.state.ExpensesStateManager.requestDuplicateCheck] (the
     *  manual "Check for duplicates now" button and its schedule) use: [MODE_LOCAL] (deterministic,
     *  instant, no Commander dependency), [MODE_LOCAL_AND_AI] (local pre-filters same-amount
     *  candidate clusters, the AI judges only those — see
     *  [com.voxapps.expenses.data.ExpensesRepository.duplicateCandidateClusters]), or [MODE_AI]
     *  (today's original behavior — the AI reasons over the entire expense list unfiltered).
     *  Defaults to [MODE_LOCAL] so real protection is active out of the box. */
    val duplicateCheckModeManual: String = MODE_LOCAL,
    /** Same engine choices as [duplicateCheckModeManual], plus [MODE_OFF] (unique to this setting —
     *  disables insert-time duplicate checking entirely; the manual check/schedule are unaffected),
     *  governing what runs automatically every time a new expense is inserted (voice/scan/manual/
     *  notification-capture). [MODE_LOCAL]/[MODE_LOCAL_AND_AI] both run the deterministic rule engine
     *  synchronously — silently merging on a match, or staging it for review instead if
     *  [automaticProtectionReviewOnly] is on; [MODE_LOCAL_AND_AI]/[MODE_AI] additionally fire an async,
     *  scoped AI check (only the new row's own same-amount cluster, never the whole list) whose result
     *  — if any — lands in the review list, unless [autoAcceptDuplicateMerges] is on. */
    val duplicateCheckModeAutomatic: String = MODE_LOCAL,
    /** Off by default (today's original silent-merge behavior). When on, an insert-time rule-engine
     *  match ([duplicateCheckModeAutomatic] LOCAL/LOCAL_AND_AI) is staged in the review list — exactly
     *  like the manual "Check for duplicates now" button — instead of merging immediately. Has no
     *  effect when [duplicateCheckModeAutomatic] is OFF or AI (the latter has no local rule-engine
     *  pass to begin with). */
    val automaticProtectionReviewOnly: Boolean = false,
    /** Off by default (same cautious posture as every other "let AI act without review" toggle in
     *  this app). When on, a duplicate the *scoped, insert-time* AI check confirms (see
     *  [duplicateCheckModeAutomatic]) merges immediately instead of sitting in the review list —
     *  the narrow same-amount-cluster scope of that specific check makes it meaningfully
     *  higher-confidence than the AI reasoning over the whole expense list, which is why this is
     *  worth offering as its own toggle rather than a blanket "trust the AI" switch. Never applies to
     *  the manual "Check for duplicates now" button or the scheduled job — those always stage for
     *  review regardless of this setting. */
    val autoAcceptDuplicateMerges: Boolean = false,
    /** Minutes, not millis — converted at the point of use. Applies wherever a rule references
     *  [com.voxapps.expenses.data.ExpenseRuleFields.ID_DATE_TIME] — see that class's doc comment. */
    val nearDuplicateTimeWindowMinutes: Int = NEAR_DUP_DEFAULT_WINDOW_MINUTES,
    /** How the user's [com.voxapps.expenses.data.DuplicateRuleEntity] rules combine — [MODE_LOCAL]'s
     *  duplicate check is a match if ANY enabled rule matches ([RULE_SET_OR], the default — matches
     *  the seeded default rules' own intent, "same amount+title" OR "same amount+vendor") or only if
     *  EVERY enabled rule matches ([RULE_SET_AND], for a stricter "all these signals together" check). */
    val duplicateRuleSetGlobalCombinator: String = RULE_SET_OR,
    /** Off by default — new, override-the-LLM behavior a user must opt into, same posture as
     *  [autoAcceptDuplicateMerges]. When on, [com.voxapps.expenses.data.ExpensesRepository.addParsedExpense]
     *  checks [com.voxapps.expenses.data.MerchantCategoryMemoryDao] BEFORE running category
     *  resolution at all — see that function's doc comment for the exact precedence. */
    val merchantCategoryMemoryEnabled: Boolean = false,
    /** How many consecutive manual assignments to the same category (for one vendor) before that
     *  category auto-applies to future captures for that vendor. Defaults to 3 — 1 is aggressive (a
     *  single correction could be a one-off exception, e.g. a gift bought at a usually-groceries
     *  store, not a real pattern), while 3 requires a genuinely consistent pattern before the app
     *  starts overriding the LLM/default outright. */
    val merchantCategoryMemoryThreshold: Int = MERCHANT_MEMORY_DEFAULT_THRESHOLD,
    /** Whether the home-screen widget's day-cards draw an outline border, and its
     *  thickness/color if so — mirrors vox-calendar's identical field. Border on by default
     *  (matches prior hardcoded behavior); color defaults to the first shared preset in
     *  [VoxColorPalette] rather than a hardcoded hex so it stays in sync with that palette. */
    val widgetBorderEnabled: Boolean = true,
    val widgetBorderThicknessDp: Int = THICKNESS_MEDIUM,
    val widgetBorderColorArgb: Long = VoxColorPalette.presets.first(),
    /** Which highlight effect (if any) draws around the in-app "today" card, and its color(s) —
     *  mirrors vox-calendar's identical fields. Not yet implemented, see
     *  `com.voxapps.design.effects.ApplyTodayEffect`. */
    val todayEffect: String = TodayEffect.NONE.name,
    val todayEffectStyle: String = TodayEffectStyle.RING.name,
    val todayEffectColor: Long = TODAY_EFFECT_DEFAULT_COLOR,
    val todayEffectColor2: Long? = null,
    val todayEffectSpeed: Float = 1f,
    val todayEffectShowInWidget: Boolean = true,
    val batchCleanupManualReview: Boolean = true,
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
        const val DEFAULT_CURRENCY = "RON"

        const val INTERVAL_OFF = "OFF"
        const val INTERVAL_HOURLY = "HOURLY"
        const val INTERVAL_DAILY = "DAILY"
        const val INTERVAL_WEEKLY = "WEEKLY"
        const val INTERVAL_MONTHLY = "MONTHLY"

        const val DECIMAL_PERIOD = "period"
        const val DECIMAL_COMMA = "comma"

        const val THEME_SYSTEM = "SYSTEM"
        const val THEME_LIGHT = "LIGHT"
        const val THEME_DARK = "DARK"

        const val MODE_LOCAL = "LOCAL"
        const val MODE_LOCAL_AND_AI = "LOCAL_AND_AI"
        const val MODE_AI = "AI"
        /** [duplicateCheckModeAutomatic] only — not a valid value for [duplicateCheckModeManual]. */
        const val MODE_OFF = "OFF"

        const val NEAR_DUP_WINDOW_1M = 1
        const val NEAR_DUP_WINDOW_2M = 2
        const val NEAR_DUP_WINDOW_5M = 5
        const val NEAR_DUP_WINDOW_10M = 10
        const val NEAR_DUP_WINDOW_15M = 15
        const val NEAR_DUP_DEFAULT_WINDOW_MINUTES = NEAR_DUP_WINDOW_2M

        const val RULE_SET_OR = "OR"
        const val RULE_SET_AND = "AND"

        const val MERCHANT_MEMORY_THRESHOLD_1 = 1
        const val MERCHANT_MEMORY_THRESHOLD_3 = 3
        const val MERCHANT_MEMORY_THRESHOLD_5 = 5
        const val MERCHANT_MEMORY_THRESHOLD_10 = 10
        const val MERCHANT_MEMORY_DEFAULT_THRESHOLD = MERCHANT_MEMORY_THRESHOLD_3

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
