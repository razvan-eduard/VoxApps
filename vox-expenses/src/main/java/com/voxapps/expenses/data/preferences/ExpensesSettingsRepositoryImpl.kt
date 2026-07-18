package com.voxapps.expenses.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class ExpensesSettingsRepositoryImpl(appContext: Context) : ExpensesSettingsRepository {

    private val dataStore: DataStore<Preferences> = DataStoreProvider.get(appContext)

    private object Keys {
        val IS_BIOMETRIC_REQUIRED = booleanPreferencesKey("is_biometric_required")
        val SESSION_TIMEOUT_MINUTES = intPreferencesKey("session_timeout_minutes")
        val LANGUAGE = stringPreferencesKey("language")
        val DEFAULT_CURRENCY = stringPreferencesKey("default_currency")
        val DEFAULT_VOICE_CATEGORY_ID = longPreferencesKey("default_voice_category_id")
        val VOICE_SAVE_TOAST_ENABLED = booleanPreferencesKey("voice_save_toast_enabled")
        val AUTO_CREATE_VOICE_CATEGORY = booleanPreferencesKey("auto_create_voice_category")
        val SCHEDULED_MERGE_INTERVAL = stringPreferencesKey("scheduled_merge_interval")
        val SCHEDULED_EXPENSE_DEDUP_INTERVAL = stringPreferencesKey("scheduled_expense_dedup_interval")
        val HOME_CURRENCY = stringPreferencesKey("home_currency")
        val PAYMENT_SOURCE_PACKAGES = stringSetPreferencesKey("payment_source_packages")
        val BANKING_SOURCE_PACKAGES = stringSetPreferencesKey("banking_source_packages")
        val AUTO_ACCEPT_NOTIFICATION_EXPENSES = booleanPreferencesKey("auto_accept_notification_expenses")
        val DEBUG_LOGGING_ENABLED = booleanPreferencesKey("debug_logging_enabled")
        val VAT_DISPLAY_ENABLED = booleanPreferencesKey("vat_display_enabled")
        val DECIMAL_SEPARATOR = stringPreferencesKey("decimal_separator")
        val CALENDAR_VIEW_ENABLED = booleanPreferencesKey("calendar_view_enabled")
        val DEBUG_TOASTS_ENABLED = booleanPreferencesKey("debug_toasts_enabled")
        val APP_CACHE_JSON = stringPreferencesKey("app_cache_json")
        val THEME_DARK_MODE = stringPreferencesKey("theme_dark_mode")
        val THEME_COLORED = booleanPreferencesKey("theme_colored")
        val ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
        val ATTACH_PHOTO_ON_SCAN = booleanPreferencesKey("attach_photo_on_scan")
        val ATTACH_PHOTO_ON_RETRY = booleanPreferencesKey("attach_photo_on_retry")
    }

    override val settingsFlow: Flow<ExpensesSettings> = dataStore.data.map { prefs ->
        ExpensesSettings(
            isBiometricRequired = prefs[Keys.IS_BIOMETRIC_REQUIRED] ?: false,
            sessionTimeoutMinutes = prefs[Keys.SESSION_TIMEOUT_MINUTES] ?: ExpensesSettings.TIMEOUT_30M,
            language = prefs[Keys.LANGUAGE] ?: defaultDeviceLanguage(),
            defaultCurrency = prefs[Keys.DEFAULT_CURRENCY] ?: ExpensesSettings.DEFAULT_CURRENCY,
            defaultVoiceCategoryId = prefs[Keys.DEFAULT_VOICE_CATEGORY_ID],
            voiceSaveToastEnabled = prefs[Keys.VOICE_SAVE_TOAST_ENABLED] ?: false,
            autoCreateVoiceCategory = prefs[Keys.AUTO_CREATE_VOICE_CATEGORY] ?: false,
            scheduledMergeInterval = prefs[Keys.SCHEDULED_MERGE_INTERVAL] ?: ExpensesSettings.INTERVAL_OFF,
            scheduledExpenseDedupInterval = prefs[Keys.SCHEDULED_EXPENSE_DEDUP_INTERVAL] ?: ExpensesSettings.INTERVAL_OFF,
            homeCurrency = prefs[Keys.HOME_CURRENCY] ?: ExpensesSettings.DEFAULT_CURRENCY,
            paymentSourcePackages = prefs[Keys.PAYMENT_SOURCE_PACKAGES] ?: emptySet(),
            bankingSourcePackages = prefs[Keys.BANKING_SOURCE_PACKAGES] ?: emptySet(),
            autoAcceptNotificationExpenses = prefs[Keys.AUTO_ACCEPT_NOTIFICATION_EXPENSES] ?: false,
            debugLoggingEnabled = prefs[Keys.DEBUG_LOGGING_ENABLED] ?: false,
            vatDisplayEnabled = prefs[Keys.VAT_DISPLAY_ENABLED] ?: false,
            decimalSeparator = prefs[Keys.DECIMAL_SEPARATOR] ?: ExpensesSettings.DECIMAL_PERIOD,
            calendarViewEnabled = prefs[Keys.CALENDAR_VIEW_ENABLED] ?: false,
            debugToastsEnabled = prefs[Keys.DEBUG_TOASTS_ENABLED] ?: false,
            appCacheJson = prefs[Keys.APP_CACHE_JSON],
            themeDarkMode = prefs[Keys.THEME_DARK_MODE] ?: ExpensesSettings.THEME_SYSTEM,
            themeColored = prefs[Keys.THEME_COLORED] ?: true,
            onboardingCompleted = prefs[Keys.ONBOARDING_COMPLETED] ?: false,
            attachPhotoOnScan = prefs[Keys.ATTACH_PHOTO_ON_SCAN] ?: false,
            attachPhotoOnRetry = prefs[Keys.ATTACH_PHOTO_ON_RETRY] ?: false
        )
    }

    @Volatile private var cachedSnapshot: ExpensesSettings? = null

    init {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            settingsFlow.collect { cachedSnapshot = it }
        }
    }

    override fun getSnapshot(): ExpensesSettings =
        cachedSnapshot ?: runBlocking { settingsFlow.first() }.also { cachedSnapshot = it }

    override suspend fun setBiometricRequired(required: Boolean) {
        dataStore.edit { it[Keys.IS_BIOMETRIC_REQUIRED] = required }
    }

    override suspend fun setSessionTimeoutMinutes(minutes: Int) {
        dataStore.edit { it[Keys.SESSION_TIMEOUT_MINUTES] = minutes }
    }

    override suspend fun setLanguage(code: String) {
        dataStore.edit { it[Keys.LANGUAGE] = code }
    }

    override suspend fun setDefaultCurrency(code: String) {
        dataStore.edit { it[Keys.DEFAULT_CURRENCY] = code }
    }

    override suspend fun setDefaultVoiceCategoryId(id: Long?) {
        dataStore.edit {
            if (id == null) it.remove(Keys.DEFAULT_VOICE_CATEGORY_ID) else it[Keys.DEFAULT_VOICE_CATEGORY_ID] = id
        }
    }

    override suspend fun setVoiceSaveToastEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.VOICE_SAVE_TOAST_ENABLED] = enabled }
    }

    override suspend fun setAutoCreateVoiceCategory(enabled: Boolean) {
        dataStore.edit { it[Keys.AUTO_CREATE_VOICE_CATEGORY] = enabled }
    }

    override suspend fun setScheduledMergeInterval(interval: String) {
        dataStore.edit { it[Keys.SCHEDULED_MERGE_INTERVAL] = interval }
    }

    override suspend fun setScheduledExpenseDedupInterval(interval: String) {
        dataStore.edit { it[Keys.SCHEDULED_EXPENSE_DEDUP_INTERVAL] = interval }
    }

    override suspend fun setHomeCurrency(code: String) {
        dataStore.edit { it[Keys.HOME_CURRENCY] = code }
    }

    override suspend fun setPaymentSourcePackages(packages: Set<String>) {
        dataStore.edit {
            it[Keys.PAYMENT_SOURCE_PACKAGES] = packages
            // A starred (banking) app can't stay starred once it's no longer even allowlisted.
            val currentBanking = it[Keys.BANKING_SOURCE_PACKAGES] ?: emptySet()
            it[Keys.BANKING_SOURCE_PACKAGES] = currentBanking.intersect(packages)
        }
    }

    override suspend fun setBankingSourcePackages(packages: Set<String>) {
        dataStore.edit { it[Keys.BANKING_SOURCE_PACKAGES] = packages }
    }

    override suspend fun setAutoAcceptNotificationExpenses(enabled: Boolean) {
        dataStore.edit { it[Keys.AUTO_ACCEPT_NOTIFICATION_EXPENSES] = enabled }
    }

    override suspend fun setDebugLoggingEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.DEBUG_LOGGING_ENABLED] = enabled }
    }

    override suspend fun setVatDisplayEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.VAT_DISPLAY_ENABLED] = enabled }
    }

    override suspend fun setDecimalSeparator(separator: String) {
        dataStore.edit { it[Keys.DECIMAL_SEPARATOR] = separator }
    }

    override suspend fun setCalendarViewEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.CALENDAR_VIEW_ENABLED] = enabled }
    }

    override suspend fun setDebugToastsEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.DEBUG_TOASTS_ENABLED] = enabled }
    }

    override suspend fun setAppCache(json: String) {
        dataStore.edit { it[Keys.APP_CACHE_JSON] = json }
    }

    override suspend fun clearAppCache() {
        dataStore.edit { it.remove(Keys.APP_CACHE_JSON) }
    }

    override suspend fun setThemeDarkMode(mode: String) {
        dataStore.edit { it[Keys.THEME_DARK_MODE] = mode }
    }

    override suspend fun setThemeColored(colored: Boolean) {
        dataStore.edit { it[Keys.THEME_COLORED] = colored }
    }

    override suspend fun setOnboardingCompleted(completed: Boolean) {
        dataStore.edit { it[Keys.ONBOARDING_COMPLETED] = completed }
    }

    override suspend fun setAttachPhotoOnScan(enabled: Boolean) {
        dataStore.edit { it[Keys.ATTACH_PHOTO_ON_SCAN] = enabled }
    }

    override suspend fun setAttachPhotoOnRetry(enabled: Boolean) {
        dataStore.edit { it[Keys.ATTACH_PHOTO_ON_RETRY] = enabled }
    }

    override suspend fun restoreSettings(settings: ExpensesSettings) {
        dataStore.edit { prefs ->
            prefs[Keys.IS_BIOMETRIC_REQUIRED] = settings.isBiometricRequired
            prefs[Keys.SESSION_TIMEOUT_MINUTES] = settings.sessionTimeoutMinutes
            prefs[Keys.LANGUAGE] = settings.language
            prefs[Keys.DEFAULT_CURRENCY] = settings.defaultCurrency
            if (settings.defaultVoiceCategoryId == null) {
                prefs.remove(Keys.DEFAULT_VOICE_CATEGORY_ID)
            } else {
                prefs[Keys.DEFAULT_VOICE_CATEGORY_ID] = settings.defaultVoiceCategoryId
            }
            prefs[Keys.VOICE_SAVE_TOAST_ENABLED] = settings.voiceSaveToastEnabled
            prefs[Keys.AUTO_CREATE_VOICE_CATEGORY] = settings.autoCreateVoiceCategory
            prefs[Keys.SCHEDULED_MERGE_INTERVAL] = settings.scheduledMergeInterval
            prefs[Keys.SCHEDULED_EXPENSE_DEDUP_INTERVAL] = settings.scheduledExpenseDedupInterval
            prefs[Keys.HOME_CURRENCY] = settings.homeCurrency
            prefs[Keys.PAYMENT_SOURCE_PACKAGES] = settings.paymentSourcePackages
            prefs[Keys.BANKING_SOURCE_PACKAGES] = settings.bankingSourcePackages
            prefs[Keys.AUTO_ACCEPT_NOTIFICATION_EXPENSES] = settings.autoAcceptNotificationExpenses
            prefs[Keys.DEBUG_LOGGING_ENABLED] = settings.debugLoggingEnabled
            prefs[Keys.VAT_DISPLAY_ENABLED] = settings.vatDisplayEnabled
            prefs[Keys.DECIMAL_SEPARATOR] = settings.decimalSeparator
            prefs[Keys.CALENDAR_VIEW_ENABLED] = settings.calendarViewEnabled
            prefs[Keys.DEBUG_TOASTS_ENABLED] = settings.debugToastsEnabled
            prefs[Keys.THEME_DARK_MODE] = settings.themeDarkMode
            prefs[Keys.THEME_COLORED] = settings.themeColored
            prefs[Keys.ATTACH_PHOTO_ON_SCAN] = settings.attachPhotoOnScan
            prefs[Keys.ATTACH_PHOTO_ON_RETRY] = settings.attachPhotoOnRetry
            // appCacheJson intentionally untouched — see interface doc comment.
        }
    }

    private fun defaultDeviceLanguage(): String =
        java.util.Locale.getDefault().language.ifBlank { ExpensesSettings.DEFAULT_LANGUAGE }
}
