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
    suspend fun setPaymentSourcePackages(packages: Set<String>)
    suspend fun setBankingSourcePackages(packages: Set<String>)
    suspend fun setAutoAcceptNotificationExpenses(enabled: Boolean)
    suspend fun setDebugLoggingEnabled(enabled: Boolean)
    suspend fun setVatDisplayEnabled(enabled: Boolean)
    suspend fun setDecimalSeparator(separator: String)
    suspend fun setCalendarViewEnabled(enabled: Boolean)
    suspend fun setDebugToastsEnabled(enabled: Boolean)
    suspend fun setAppCache(json: String)
    suspend fun clearAppCache()
    suspend fun setThemeDarkMode(mode: String)
    suspend fun setThemeColored(colored: Boolean)
    suspend fun setOnboardingCompleted(completed: Boolean)
    suspend fun setAttachPhotoOnScan(enabled: Boolean)
    suspend fun setAttachPhotoOnRetry(enabled: Boolean)
    suspend fun setLocationPrefillEnabled(enabled: Boolean)
    suspend fun setNearDuplicateDetectionEnabled(enabled: Boolean)
    suspend fun setNearDuplicateFuzzyMatchEnabled(enabled: Boolean)
    suspend fun setNearDuplicateTimeWindowMinutes(minutes: Int)
    suspend fun setMerchantCategoryMemoryEnabled(enabled: Boolean)
    suspend fun setMerchantCategoryMemoryThreshold(count: Int)

    /**
     * Bulk overwrite, e.g. from a Vox Hub import — writes every portable field in one DataStore edit.
     * Deliberately never touches [ExpensesSettings.appCacheJson] (an internal cache, not user data;
     * see its own doc comment) regardless of what [settings] carries for that field.
     */
    suspend fun restoreSettings(settings: ExpensesSettings)
}
