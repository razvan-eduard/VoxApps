package com.voxapps.expenses.data.preferences

import com.voxapps.design.notifications.VoxNotificationPrefs
import com.voxapps.recordflow.FieldWeight
import com.voxapps.recordflow.FlowSupport
import com.voxapps.recordflow.LlmLevel
import com.voxapps.recordflow.RecordSource

import androidx.compose.runtime.Immutable
import com.voxapps.expenses.data.TransactionDirection
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
    /** Which repository serves this app's schemas. Its own setting rather than Commander's: an
     *  install may follow a fork for one app and not the other. */
    val schemaRepoBaseUrl: String = com.voxapps.services.SchemaRepo.DEFAULT_BASE_URL,
    /** Whether that repository is asked at startup, or only when the user presses check. */
    val useRemoteSchemas: Boolean = true,
    /** Which declared currency service supplies rates. Empty means the first one declared. */
    val exchangeRateServiceId: String = "",

    val isBiometricRequired: Boolean = false,
    val sessionTimeoutMinutes: Int = TIMEOUT_30M,
    val language: String = DEFAULT_LANGUAGE,
    val defaultCurrency: String = DEFAULT_CURRENCY,
    val defaultVoiceCategoryId: Long? = null,
    val voiceSaveToastEnabled: Boolean = false,
    val autoCreateVoiceCategory: Boolean = false,
    /**
     * How much of a scan the model is asked to do — see [SCAN_MODEL_FULL] and its siblings.
     *
     * Defaults to asking for everything, which is what installs already do. The point of the lower
     * settings is that the deterministic reader now extracts line items, totals and a vendor on its
     * own, so a person who would rather not send a receipt anywhere can have the scan work without
     * a model at all rather than losing the feature.
     */
    val scanModelUse: String = SCAN_MODEL_FULL,
    /**
     * Whether a captured notification is sent to a model at all — see [NOTIFICATION_MODEL_FULL] and
     * [NOTIFICATION_MODEL_NONE].
     *
     * Separate from [scanModelUse] because the two channels differ in what the device can work out
     * alone. A scanned page carries arithmetic that proves a reading; a payment notification carries
     * one sentence, so the deterministic half can recover the amount but not what the sentence
     * means. The lower setting is therefore not "read it locally instead" but "capture it locally
     * and let a person confirm it", which is what the pending-review queue already exists for.
     */
    val notificationModelUse: String = NOTIFICATION_MODEL_FULL,
    /**
     * How much of a spoken expense the model is asked to do — a [LlmLevel] name from
     * [VOICE_FLOW_SUPPORT]. At the offline rung the sentence stays on the device and is kept as a
     * stub expense awaiting review; at the full rung it is sent for interpretation.
     */
    val voiceModelUse: String = VOICE_FLOW_SUPPORT.default.name,
    /**
     * Which way to assume the money went when no model is asked and no template has been taught —
     * see [ASSUME_NOTHING] and its siblings.
     *
     * Off by default, because assuming is exactly what the offline path was built not to do. What
     * makes it offerable at all is that the assumption is not final: the record is written linked to
     * the message shape that produced it, so correcting its direction once is the confirmation that
     * shape never had, and the second correction settles it for good. An assumption a person can
     * overturn by editing the thing in front of them is a different proposition from one buried in a
     * setting.
     */
    val notificationAssumedDirection: String = ASSUME_NOTHING,
    /**
     * Whether a payment message that never says how much is captured at all.
     *
     * Off by default, and the default is the interesting part: an amount is what tells a payment
     * from an advertisement, so without one every promotional message from a watched app becomes a
     * thing to review. On, the capture is kept and waits for the one figure it lacks — which is
     * worth it for the senders that announce a payment and leave the sum out ("Plata acceptata"),
     * and not worth it otherwise.
     */
    val captureAmountlessPayments: Boolean = false,
    /** Enables shade recovery: a redacted capture keeps its source and waits as a stub the standing
     *  notification can rescan. The rescan surface itself is the permanent notification below. */
    val guardNotificationEnabled: Boolean = false,
    /** The standing (foreground-service) notification: keeps the app and its listener alive, and
     *  shows a small dashboard. The presence is why it exists; the dashboard is what it shows since
     *  it must show something. Off by default. */
    val permanentNotificationEnabled: Boolean = false,
    /** What the standing notification shows, each independently. */
    val notifShowToday: Boolean = true,
    val notifShowWeek: Boolean = false,
    val notifShowMonth: Boolean = false,
    val notifShowTodayCount: Boolean = false,
    val notifShowTodayIncome: Boolean = false,
    val notifShowReviewCount: Boolean = true,
    /**
     * Whether a card or account read out of a capture becomes a record by itself.
     *
     * Two switches rather than one, because the two sources are trusted differently: a scanned
     * document is something a person deliberately photographed, while a notification arrives on its
     * own from any app that is allowed to send one. Both readings are equally certain — the formats
     * either match or do not — so what these decide is not confidence, it is whether the app is
     * allowed to add to a list without being asked. See [com.voxapps.textmatch.extract.AccountIdentifiers].
     */
    val autoCreateAccountsFromScans: Boolean = false,
    val autoCreateAccountsFromNotifications: Boolean = false,

    /**
     * Whether a shop or a bank a capture read joins the lists this device reads by.
     *
     * The counterpart of creating a card from a capture, per route for the same reason: a scan is
     * something you deliberately photographed, a notification arrives on its own. A name learned
     * once from a wallet's own title is then recognised inside a bank's sentence, where nothing
     * else would find it.
     *
     * On by default, unlike the accounts: the shop list starts empty and cannot fill itself any
     * other way, and every entry is visible, switchable and removable.
     */
    val learnNamesFromNotifications: Boolean = true,
    val learnNamesFromScans: Boolean = true,

    /**
     * Whether a shop a capture read joins the list of shops this device knows.
     *
     * The counterpart of creating a card from a capture, and it earns its keep the same way: a name
     * read once from a wallet's own title is recognised the next time it arrives inside a bank's
     * sentence, where nothing else would find it. On by default because the list starts empty and
     * cannot fill itself any other way — every entry is visible, editable and removable.
     */
    val learnVendorsFromCaptures: Boolean = true,
    /**
     * The currency a new account is given.
     *
     * An account holds one currency and most people hold one, so asking every time would be asking
     * a question whose answer never changes. Empty means fall back to [defaultCurrency].
     */
    val defaultAccountCurrency: String = "",
    /**
     * How many times a payment must be seen before it is proposed as recurring — and, symmetrically,
     * how many due dates may pass unpaid before dropping it is proposed.
     *
     * One number for both directions on purpose: the evidence it takes to stop believing something
     * should be the evidence it took to start. Zero switches the whole proposal off.
     */
    /**
     * Terms this device adds to the shipped vocabularies, and the ones it switches off.
     *
     * Kept apart from the file rather than folded into it: the file is replaced wholesale by the
     * next fetch, and a signed copy cannot be edited in place at all. The switched-off sets hold
     * normalized keys so a term stays off across a replacement — see [FieldVocabularies.merge].
     */
    val customVendors: Set<String> = emptySet(),
    val disabledVendors: Set<String> = emptySet(),
    val customLegalForms: Set<String> = emptySet(),
    val customBanks: Set<String> = emptySet(),
    val disabledLegalForms: Set<String> = emptySet(),
    val disabledBanks: Set<String> = emptySet(),
    /** Words this device added to, or switched off in, the stop list — see FieldVocabularies. */
    /**
     * Whether the widget names what is left to spend, and of what.
     *
     * Off by default and off as a real answer, not merely an empty selection: a home screen is read
     * over shoulders and on a lock screen, and a person who wants their balance private wants it
     * absent rather than small.
     */
    val widgetBudgetMode: String = WIDGET_BUDGET_OFF,
    /** Which accounts the header counts, when it counts some. Empty with [WIDGET_BUDGET_SELECTED]
     *  is a header with nothing to say, and says nothing. */
    val widgetBudgetAccountIds: Set<Long> = emptySet(),
    val customStopWords: Set<String> = emptySet(),
    val disabledStopWords: Set<String> = emptySet(),

    /**
     * Whether a captured notification is cleared from the shade once the app has kept it.
     *
     * Off by default, and deliberately: this removes a message the app did not write and cannot put
     * back. Only a capture that actually landed somewhere — a filed expense or a review entry —
     * qualifies; a message read and discarded stays where its sender put it.
     */
    val dismissNotificationOnCapture: Boolean = false,

    val recurringProposalThreshold: Int = 2,

    /** Whether a confirmed arrangement earns a notification a couple of days before it falls due.
     *  Separate from the threshold above: noticing a pattern and being told about it are different
     *  things to want, and one is far more intrusive than the other. */
    val recurringRemindersEnabled: Boolean = true,
    val scheduledMergeInterval: String = INTERVAL_OFF,
    val scheduledExpenseDedupInterval: String = INTERVAL_OFF,
    val homeCurrency: String = DEFAULT_CURRENCY,
    val paymentSourcePackages: Set<String> = emptySet(),
    val bankingSourcePackages: Set<String> = emptySet(),
    val autoAcceptNotificationExpenses: Boolean = false,
    val debugLoggingEnabled: Boolean = false,
    /**
     * Whether the net/VAT/gross breakdown is shown — see [VAT_OFF], [VAT_AUTO], [VAT_ON].
     *
     * Three settings rather than two because the honest answer depends on the document. Always
     * showing the columns leaves most receipts with three empty fields; never showing them hides a
     * breakdown the scan actually read. [VAT_AUTO] shows them exactly when the document carried
     * them, and is what a scan-first install wants.
     */
    val vatDisplay: String = VAT_AUTO,
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
    // --- BACKUP & RESTORE (local, shared :core:backup module's VoxBackupSettingsCard) — mirrors
    // vox-hub's AppBackupConfig shape/names, persisted here for this app's own local backup button,
    // independent of any Hub-triggered IPC export. ---
    val backupIncludeSettings: Boolean = true,
    val backupIncludeData: Boolean = true,
    val backupIncludeApiKeys: Boolean = false,
    val backupIncludeAttachments: Boolean = false,
    /** Wire-format string per [com.voxapps.ipc.VoxIpc.IMPORT_MODE_MERGE] etc., parsed via
     *  [com.voxapps.backup.VoxImportMode.fromWireValue]. */
    val backupImportMode: String = "merge",
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

    /**
     * How long the archive keeps a record before deleting it, in days.
     *
     * [ARCHIVE_KEEP_FOREVER] by default, and deliberately: archiving is what somebody does *instead*
     * of deleting, and a fresh install that quietly started destroying those records after a month
     * would be answering a question nobody asked it.
     */
    val archiveRetentionDays: Int = ARCHIVE_KEEP_FOREVER,
    /** How the user's [com.voxapps.expenses.data.DuplicateRuleEntity] rules combine — [MODE_LOCAL]'s
     *  duplicate check is a match if ANY enabled rule matches ([RULE_SET_OR], the default — matches
     *  the seeded default rules' own intent, "same amount+title" OR "same amount+vendor") or only if
     *  EVERY enabled rule matches ([RULE_SET_AND], for a stricter "all these signals together" check). */
    val duplicateRuleSetGlobalCombinator: String = RULE_SET_OR,
    /** Off by default — whether repeated field edits draft DISABLED re-map rule proposals (see
     *  [com.voxapps.expenses.data.RemapPatternSighting]). Never affects rule application: enabled
     *  rules always apply. Stored under the absorbed merchant-memory's key, so an existing
     *  install keeps its choice. */
    val remapProposalsEnabled: Boolean = false,
    /** In how many distinct records the same field must be renamed X→Y before a rule proposal is
     *  drafted — the learning-speed selector, same scale as [fieldCorrectionThreshold]. */
    val remapLearningSpeed: Int = CORRECTION_SPEED_MEDIUM,
    /** Off by default — same opt-in posture as [remapLearningEnabled]. When on, manual
     *  edit-saves teach word-level spelling corrections (see :core:fieldmemory) that apply to
     *  future captured records' text fields. */
    val fieldCorrectionMemoryEnabled: Boolean = false,
    /** Consecutive identical corrections before one becomes active — the learning-speed selector:
     *  [CORRECTION_SPEED_INSTANT]/[CORRECTION_SPEED_FAST]/[CORRECTION_SPEED_MEDIUM]/
     *  [CORRECTION_SPEED_SLOW]. */
    val fieldCorrectionThreshold: Int = CORRECTION_SPEED_MEDIUM,
    /** [CORRECTION_APPLY_SUGGEST] surfaces exact-tier corrections as tappable suggestions on the
     *  created record; [CORRECTION_APPLY_AUTO] rewrites them silently at creation. Fuzzy-tier
     *  resemblance hits are always suggestions, in both modes. */
    val fieldCorrectionApplyMode: String = CORRECTION_APPLY_SUGGEST,
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

        /**
         * [defaultAccountCurrency]'s third answer: a card or account created from a capture takes
         * the currency that capture stated, falling back to [defaultCurrency] when it stated none.
         *
         * A sentinel rather than a second setting, because it belongs in the same list as the
         * currencies it stands beside — and no ISO code can collide with it, three letters being
         * the whole of what one may be.
         */
        const val ACCOUNT_CURRENCY_FROM_CAPTURE = "@capture"

        /** The widget says nothing about money left. */
        const val WIDGET_BUDGET_OFF = "OFF"

        /** One figure: every budget, converted into the home currency. */
        const val WIDGET_BUDGET_TOTAL = "TOTAL"

        /** Only the accounts named in [widgetBudgetAccountIds]. */
        const val WIDGET_BUDGET_SELECTED = "SELECTED"

        const val INTERVAL_OFF = "OFF"

        /** Never shown. A scan that finds a breakdown says so once, rather than silently dropping it. */
        const val VAT_OFF = "OFF"

        /** Shown for the records that carry one, hidden for the records that do not. */
        const val VAT_AUTO = "AUTO"

        /** Always shown, empty or not, for someone who enters the breakdown by hand. */
        const val VAT_ON = "ON"

        val VAT_CHOICES = listOf(VAT_OFF, VAT_AUTO, VAT_ON)

        /** The model reads everything: it may correct recognition, infer a category, name the vendor. */
        const val SCAN_MODEL_FULL = "FULL"

        /** Items and totals come from the deterministic reader; the model is asked only for the
         *  fields no arithmetic can confirm — vendor, title, category — and its answer is applied. */
        const val SCAN_MODEL_HEADER_FOOTER_AUTO = "HEADER_FOOTER_AUTO"

        /** The same, surfaced as suggestions to accept rather than applied. */
        const val SCAN_MODEL_HEADER_FOOTER_SUGGEST = "HEADER_FOOTER_SUGGEST"

        /** Nothing leaves the device: the scan is read entirely by the deterministic path. */
        const val SCAN_MODEL_NONE = "NONE"

        val SCAN_MODEL_CHOICES = listOf(
            SCAN_MODEL_FULL, SCAN_MODEL_HEADER_FOOTER_AUTO, SCAN_MODEL_HEADER_FOOTER_SUGGEST, SCAN_MODEL_NONE
        )

        /**
         * What this app can honestly do with a scanned page.
         *
         * Every rung: the deterministic reader can establish a page's figures alone, and the edit
         * screen can hold a proposal for anything it could not. Line items are the fine detail — a
         * row that reads 51,38 for 51,33 is wrong in a way nothing downstream catches — so the rungs
         * that leave them to be approved are the point of the scale here.
         */
        val SCAN_FLOW_SUPPORT = FlowSupport(
            source = RecordSource.SCAN,
            // Every rung, and each is honoured on both halves of the round trip: the level governs
            // the reply as well as the reading, so "send everything, write nothing" writes nothing
            // and offers the answer on the record instead. A declaration this app could not keep
            // would be worse than a shorter scale.
            supported = LlmLevel.entries.toSet(),
            default = LlmLevel.FULL,
            suggestsAnswers = true
        )

        /**
         * The four settings this app carried before the scale was written down, read as the rungs
         * they always were. Each is exact: the pair of questions was already being answered, just
         * not separately.
         */
        fun scanLevelOf(stored: String): LlmLevel = when (stored) {
            SCAN_MODEL_NONE -> LlmLevel.NONE
            SCAN_MODEL_HEADER_FOOTER_SUGGEST -> LlmLevel.ASSIST_SUGGEST
            SCAN_MODEL_HEADER_FOOTER_AUTO -> LlmLevel.ASSIST_AUTO
            SCAN_MODEL_FULL -> LlmLevel.FULL
            // Already a rung, or a value from a newer build: an unreadable one reads as the fullest
            // behaviour, which is what installs had before any of this existed.
            else -> LlmLevel.entries.firstOrNull { it.name == stored } ?: LlmLevel.FULL
        }

        /** The notification's text is sent for interpretation, as it always has been. */
        const val NOTIFICATION_MODEL_FULL = "FULL"

        /** Nothing leaves the device: the amount is taken by the deterministic pre-parse, the
         *  meaning of the sentence by the template memory where a human already taught it, and
         *  anything still unproven waits in review rather than being guessed at. */
        const val NOTIFICATION_MODEL_NONE = "NONE"

        val NOTIFICATION_MODEL_CHOICES = listOf(NOTIFICATION_MODEL_FULL, NOTIFICATION_MODEL_NONE)

        /** Nothing is assumed: an unconfirmed shape waits for a person. The original behaviour. */
        const val ASSUME_NOTHING = "NOTHING"

        /** Money left the account unless a template says otherwise — the common case by far. */
        const val ASSUME_OUTGOING = "OUTGOING"

        /** Money arrived. For an account that mostly receives. */
        const val ASSUME_INCOMING = "INCOMING"

        val ASSUMED_DIRECTION_CHOICES = listOf(ASSUME_NOTHING, ASSUME_OUTGOING, ASSUME_INCOMING)

        /** Never propose; 2 is the smallest evidence worth calling a pattern. */
        val RECURRING_THRESHOLD_CHOICES = listOf(0, 2, 3, 4)

        /**
         * The stored setting as a direction, or null for "assume nothing".
         *
         * A taught template always outranks this: the assumption answers only where nobody has said
         * anything yet.
         */
        fun assumedDirectionOf(stored: String): TransactionDirection? = when (stored) {
            ASSUME_OUTGOING -> TransactionDirection.OUTGOING
            ASSUME_INCOMING -> TransactionDirection.INCOMING
            else -> null
        }

        /**
         * What this app can honestly do with a captured notification.
         *
         * Two rungs. The middle ones would need somewhere to hold a proposal *for a record that does
         * not exist yet* — and the queue this channel already has is that place, but it holds whole
         * entries awaiting approval rather than fields awaiting acceptance, which is a different
         * thing. So the choice here stays "send the sentence, or work from the figures alone".
         *
         * One coarse half only: a notification carries no list of rows.
         */
        val NOTIFICATION_FLOW_SUPPORT = FlowSupport(
            source = RecordSource.NOTIFICATION,
            supported = setOf(LlmLevel.NONE, LlmLevel.FULL),
            default = LlmLevel.FULL,
            weights = setOf(FieldWeight.HEAD)
        )

        /**
         * What this app can do with a spoken expense.
         *
         * Two rungs. At [LlmLevel.FULL] the sentence is sent for interpretation. At [LlmLevel.NONE]
         * nothing leaves the device: the sentence is kept as a stub expense awaiting review, never
         * written as a finished record, because no rule can settle it alone. The one candidate is
         * the amount, and a single currency-marked figure does not mean the total: in "three loaves
         * at ten each" the marked figure is the per-unit price. Telling those apart is the
         * distributive/cumulative distinction — see
         * [com.voxapps.expenses.domain.llm.DistributiveCumulativeRule] — and it is carried by
         * language, not by arithmetic. So the offline rung only queues what was said; a person
         * finishes it.
         */
        val VOICE_FLOW_SUPPORT = FlowSupport(
            source = RecordSource.VOICE,
            supported = setOf(LlmLevel.NONE, LlmLevel.FULL),
            default = LlmLevel.FULL,
            weights = setOf(FieldWeight.HEAD)
        )

        /** The stored setting as a rung; an unreadable value reads as the fullest behaviour. */
        fun voiceLevelOf(stored: String): LlmLevel =
            LlmLevel.entries.firstOrNull { it.name == stored }
                ?.takeIf { it in VOICE_FLOW_SUPPORT.supported }
                ?: LlmLevel.FULL

        /** The stored setting as a rung. The two values predate the scale and map exactly. */
        fun notificationLevelOf(stored: String): LlmLevel = when (stored) {
            NOTIFICATION_MODEL_NONE -> LlmLevel.NONE
            NOTIFICATION_MODEL_FULL -> LlmLevel.FULL
            else -> LlmLevel.entries.firstOrNull { it.name == stored } ?: LlmLevel.FULL
        }
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

        /** The archive deletes nothing on its own — see [archiveRetentionDays]. */
        const val ARCHIVE_KEEP_FOREVER = 0

        /** What the archive can be told to keep things for. Days throughout, so one number means
         *  one thing wherever it is read. */
        val ARCHIVE_RETENTION_CHOICES = listOf(ARCHIVE_KEEP_FOREVER, 30, 90, 180, 365)

        const val RULE_SET_OR = "OR"
        const val RULE_SET_AND = "AND"

        const val CORRECTION_SPEED_INSTANT = 1
        const val CORRECTION_SPEED_FAST = 2
        const val CORRECTION_SPEED_MEDIUM = 3
        const val CORRECTION_SPEED_SLOW = 5

        const val CORRECTION_APPLY_SUGGEST = "SUGGEST"
        const val CORRECTION_APPLY_AUTO = "AUTO"

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

/**
 * The currency a card or account created from a capture starts in.
 *
 * Three answers, one list: a currency named outright, the app's own, or whatever the capture that
 * created it stated. The last falls back to the app's when the message named none — an account has
 * to be in some currency, and inventing one from silence is not better than following the default.
 */
fun ExpensesSettings.accountCurrencyFor(captured: String?): String = when (defaultAccountCurrency) {
    "" -> defaultCurrency
    ExpensesSettings.ACCOUNT_CURRENCY_FROM_CAPTURE ->
        captured?.takeIf { it.isNotBlank() } ?: defaultCurrency
    else -> defaultAccountCurrency
}

/**
 * The currencies this device already deals in — what settles a spelling that names several.
 *
 * "lei" is RON here and MDL across the border, and neither reading is more correct than the other;
 * what makes it RON is that this app was told RON. [accountCurrencies] carries what the stored cards
 * and accounts add to that.
 */
fun ExpensesSettings.knownCurrencies(accountCurrencies: Collection<String> = emptyList()): Set<String> =
    (listOf(defaultCurrency, homeCurrency, defaultAccountCurrency) + accountCurrencies)
        .filter { it.isNotBlank() && it != ExpensesSettings.ACCOUNT_CURRENCY_FROM_CAPTURE }
        .map { it.uppercase() }
        .toSet()

/** How this app was told to sound its alerts, in the shape [VoxNotifier] takes. */
fun ExpensesSettings.notificationPrefs() = VoxNotificationPrefs(
    systemDefault = notificationsSystemDefault,
    channelVersion = notificationsChannelVersion,
    soundUri = notificationsSoundUri,
    volume = notificationsVolume,
    length = notificationsLength,
    vibrationEnabled = notificationsVibrationEnabled
)
