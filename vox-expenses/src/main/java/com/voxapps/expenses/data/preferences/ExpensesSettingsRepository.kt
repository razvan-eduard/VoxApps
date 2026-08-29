package com.voxapps.expenses.data.preferences

import kotlinx.coroutines.flow.Flow

/**
 * Single source of truth for persisted Vox Expenses settings. DataStore-backed; ExpensesStateManager
 * observes [settingsFlow] and combines it with runtime state (mirrors vox-notes).
 */
interface ExpensesSettingsRepository {
    val settingsFlow: Flow<ExpensesSettings>

    /** Warm-cache synchronous read for non-coroutine consumers (e.g. a future IPC receiver). */
    fun getSnapshot(): ExpensesSettings

    suspend fun setBiometricRequired(required: Boolean)
    suspend fun setSessionTimeoutMinutes(minutes: Int)
    suspend fun setLanguage(code: String)
    suspend fun setDefaultCurrency(code: String)
    suspend fun setDefaultVoiceCategoryId(id: Long?)
    suspend fun setVoiceSaveToastEnabled(enabled: Boolean)
    suspend fun setAutoCreateVoiceCategory(enabled: Boolean)
    suspend fun setScheduledMergeInterval(interval: String)
    suspend fun setScheduledExpenseDedupInterval(interval: String)
    suspend fun setHomeCurrency(code: String)
    suspend fun setSchemaRepoBaseUrl(url: String)
    suspend fun setUseRemoteSchemas(enabled: Boolean)
    suspend fun setExchangeRateServiceId(id: String)
    suspend fun setPaymentSourcePackages(packages: Set<String>)
    suspend fun setBankingSourcePackages(packages: Set<String>)
    suspend fun setAutoAcceptNotificationExpenses(enabled: Boolean)
    suspend fun setDebugLoggingEnabled(enabled: Boolean)
    /** See [ExpensesSettings.vatDisplay]; unknown values are ignored rather than stored. */
    suspend fun setVatDisplay(mode: String)
    suspend fun setDecimalSeparator(separator: String)
    suspend fun setCalendarViewEnabled(enabled: Boolean)
    suspend fun setIsGridView(enabled: Boolean)
    suspend fun setDebugToastsEnabled(enabled: Boolean)
    suspend fun setAppCache(json: String)
    suspend fun clearAppCache()
    suspend fun setThemeDarkMode(mode: String)
    suspend fun setThemeColored(colored: Boolean)
    suspend fun setOnboardingCompleted(completed: Boolean)
    suspend fun setAttachPhotoOnScan(enabled: Boolean)
    /** See [ExpensesSettings.scanModelUse]; unknown values are ignored rather than stored. */
    suspend fun setScanModelUse(mode: String)
    /** See [ExpensesSettings.notificationModelUse]; unknown values are ignored rather than stored. */
    suspend fun setNotificationModelUse(mode: String)
    /** See [ExpensesSettings.notificationAssumedDirection]; unknown values are ignored. */
    suspend fun setNotificationAssumedDirection(mode: String)
    /** See [ExpensesSettings.captureAmountlessPayments]. */
    suspend fun setCaptureAmountlessPayments(enabled: Boolean)
    suspend fun setGuardNotificationEnabled(enabled: Boolean)
    suspend fun setPermanentNotificationEnabled(enabled: Boolean)
    suspend fun setNotifShowToday(enabled: Boolean)
    suspend fun setNotifShowWeek(enabled: Boolean)
    suspend fun setNotifShowMonth(enabled: Boolean)
    suspend fun setNotifShowTodayCount(enabled: Boolean)
    suspend fun setNotifShowTodayIncome(enabled: Boolean)
    suspend fun setNotifShowReviewCount(enabled: Boolean)
    suspend fun setAutoCreateAccountsFromScans(enabled: Boolean)
    suspend fun setAutoCreateAccountsFromNotifications(enabled: Boolean)
    suspend fun setLearnNamesFromNotifications(enabled: Boolean)
    suspend fun setLearnNamesFromScans(enabled: Boolean)
    suspend fun setLearnVendorsFromCaptures(enabled: Boolean)
    suspend fun setDefaultAccountCurrency(code: String)
    /** Adds or removes a term of this device's own; see [ExpensesSettings.customBanks]. */
    suspend fun setWidgetBudgetMode(mode: String)

    suspend fun setWidgetBudgetAccountIds(ids: Set<Long>)

    suspend fun setCustomVocabulary(vocabulary: String, terms: Set<String>)
    /** Switches shipped terms off or back on; see [ExpensesSettings.disabledBanks]. */
    suspend fun setDisabledVocabulary(vocabulary: String, keys: Set<String>)
    /** See [ExpensesSettings.dismissNotificationOnCapture]. */
    suspend fun setDismissNotificationOnCapture(enabled: Boolean)
    /** See [ExpensesSettings.recurringProposalThreshold]; 0 turns proposals off. */
    suspend fun setRecurringProposalThreshold(times: Int)
    /** See [ExpensesSettings.recurringRemindersEnabled]. */
    suspend fun setRecurringRemindersEnabled(enabled: Boolean)
    suspend fun setAttachPhotoOnRetry(enabled: Boolean)
    suspend fun setAutoRescanOnFirstAttachment(enabled: Boolean)
    suspend fun setAutoOpenScannedExpense(enabled: Boolean)
    suspend fun setLocationPrefillEnabled(enabled: Boolean)
    suspend fun setLocationHomeTown(lat: Double?, lon: Double?)
    suspend fun setLocationCacheTtl(ttl: String)
    suspend fun setLocationAlwaysUseHomeTown(enabled: Boolean)
    suspend fun setBackupIncludeSettings(enabled: Boolean)
    suspend fun setBackupIncludeData(enabled: Boolean)
    suspend fun setBackupIncludeApiKeys(enabled: Boolean)
    suspend fun setBackupIncludeAttachments(enabled: Boolean)
    suspend fun setBackupImportMode(mode: String)
    suspend fun setDuplicateCheckModeManual(mode: String)
    suspend fun setDuplicateCheckModeAutomatic(mode: String)
    suspend fun setAutoAcceptDuplicateMerges(enabled: Boolean)
    suspend fun setAutomaticProtectionReviewOnly(enabled: Boolean)
    suspend fun setNearDuplicateTimeWindowMinutes(minutes: Int)
    suspend fun setArchiveRetentionDays(days: Int)
    suspend fun setDuplicateRuleSetGlobalCombinator(combinator: String)
    suspend fun setRemapProposalsEnabled(enabled: Boolean)
    suspend fun setRemapLearningSpeed(count: Int)
    suspend fun setFieldCorrectionMemoryEnabled(enabled: Boolean)
    suspend fun setFieldCorrectionThreshold(count: Int)
    suspend fun setFieldCorrectionApplyMode(mode: String)
    suspend fun setWidgetBorderEnabled(enabled: Boolean)
    suspend fun setWidgetBorderThicknessDp(thicknessDp: Int)
    suspend fun setWidgetBorderColorArgb(colorArgb: Long)
    suspend fun setTodayEffect(effect: String)
    suspend fun setTodayEffectStyle(style: String)
    suspend fun setTodayEffectColor(colorArgb: Long)
    suspend fun setTodayEffectColor2(colorArgb: Long?)
    suspend fun setTodayEffectSpeed(speed: Float)
    suspend fun setTodayEffectShowInWidget(enabled: Boolean)
    suspend fun setBatchCleanupManualReview(enabled: Boolean)
    suspend fun setNotificationsSystemDefault(enabled: Boolean)
    suspend fun setNotificationsVibrationEnabled(enabled: Boolean)
    suspend fun setNotificationsSoundUri(uri: String?)
    suspend fun setNotificationsVolume(volume: Int)
    suspend fun setNotificationsLength(length: String)
    suspend fun setNotificationsChannelVersion(version: Int)

    /**
     * Bulk overwrite, e.g. from a Vox Hub import — writes every portable field in one DataStore edit.
     * Deliberately never touches [ExpensesSettings.appCacheJson] (an internal cache, not user data;
     * see its own doc comment) regardless of what [settings] carries for that field.
     */
    suspend fun restoreSettings(settings: ExpensesSettings)
}
