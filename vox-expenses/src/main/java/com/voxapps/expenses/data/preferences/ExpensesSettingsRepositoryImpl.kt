package com.voxapps.expenses.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.voxapps.design.color.VoxColorPalette
import com.voxapps.design.effects.TodayEffect
import com.voxapps.design.effects.TodayEffectStyle
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
        val IS_GRID_VIEW = booleanPreferencesKey("is_grid_view")
        val DEBUG_TOASTS_ENABLED = booleanPreferencesKey("debug_toasts_enabled")
        val APP_CACHE_JSON = stringPreferencesKey("app_cache_json")
        val THEME_DARK_MODE = stringPreferencesKey("theme_dark_mode")
        val THEME_COLORED = booleanPreferencesKey("theme_colored")
        val ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
        val ATTACH_PHOTO_ON_SCAN = booleanPreferencesKey("attach_photo_on_scan")
        val ATTACH_PHOTO_ON_RETRY = booleanPreferencesKey("attach_photo_on_retry")
        val AUTO_OPEN_SCANNED_EXPENSE = booleanPreferencesKey("auto_open_scanned_expense")
        val LOCATION_PREFILL_ENABLED = booleanPreferencesKey("location_prefill_enabled")
        val DUPLICATE_CHECK_MODE_MANUAL = stringPreferencesKey("duplicate_check_mode_manual")
        val DUPLICATE_CHECK_MODE_AUTOMATIC = stringPreferencesKey("duplicate_check_mode_automatic")
        val AUTO_ACCEPT_DUPLICATE_MERGES = booleanPreferencesKey("auto_accept_duplicate_merges")
        val AUTOMATIC_PROTECTION_REVIEW_ONLY = booleanPreferencesKey("automatic_protection_review_only")
        val NEAR_DUPLICATE_TIME_WINDOW_MINUTES = intPreferencesKey("near_duplicate_time_window_minutes")
        val DUPLICATE_RULE_SET_GLOBAL_COMBINATOR = stringPreferencesKey("duplicate_rule_set_global_combinator")
        val MERCHANT_CATEGORY_MEMORY_ENABLED = booleanPreferencesKey("merchant_category_memory_enabled")
        val MERCHANT_CATEGORY_MEMORY_THRESHOLD = intPreferencesKey("merchant_category_memory_threshold")
        val WIDGET_BORDER_ENABLED = booleanPreferencesKey("widget_border_enabled")
        val WIDGET_BORDER_THICKNESS_DP = intPreferencesKey("widget_border_thickness_dp")
        val WIDGET_BORDER_COLOR_ARGB = longPreferencesKey("widget_border_color_argb")
        val TODAY_EFFECT = stringPreferencesKey("today_effect")
        val TODAY_EFFECT_STYLE = stringPreferencesKey("today_effect_style")
        val TODAY_EFFECT_COLOR = longPreferencesKey("today_effect_color")
        val TODAY_EFFECT_COLOR_2 = longPreferencesKey("today_effect_color_2")
        val TODAY_EFFECT_SPEED = floatPreferencesKey("today_effect_speed")
        val TODAY_EFFECT_SHOW_IN_WIDGET = booleanPreferencesKey("today_effect_show_in_widget")
        val BATCH_CLEANUP_MANUAL_REVIEW = booleanPreferencesKey("batch_cleanup_manual_review")
        val NOTIFICATIONS_SYSTEM_DEFAULT = booleanPreferencesKey("notifications_system_default")
        val NOTIFICATIONS_VIBRATION_ENABLED = booleanPreferencesKey("notifications_vibration_enabled")
        val NOTIFICATIONS_SOUND_URI = stringPreferencesKey("notifications_sound_uri")
        val NOTIFICATIONS_VOLUME = intPreferencesKey("notifications_volume")
        val NOTIFICATIONS_LENGTH = stringPreferencesKey("notifications_length")
        val NOTIFICATIONS_CHANNEL_VERSION = intPreferencesKey("notifications_channel_version")
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
            isGridView = prefs[Keys.IS_GRID_VIEW] ?: false,
            debugToastsEnabled = prefs[Keys.DEBUG_TOASTS_ENABLED] ?: false,
            appCacheJson = prefs[Keys.APP_CACHE_JSON],
            themeDarkMode = prefs[Keys.THEME_DARK_MODE] ?: ExpensesSettings.THEME_SYSTEM,
            themeColored = prefs[Keys.THEME_COLORED] ?: true,
            onboardingCompleted = prefs[Keys.ONBOARDING_COMPLETED] ?: false,
            attachPhotoOnScan = prefs[Keys.ATTACH_PHOTO_ON_SCAN] ?: false,
            attachPhotoOnRetry = prefs[Keys.ATTACH_PHOTO_ON_RETRY] ?: false,
            autoOpenScannedExpense = prefs[Keys.AUTO_OPEN_SCANNED_EXPENSE] ?: false,
            locationPrefillEnabled = prefs[Keys.LOCATION_PREFILL_ENABLED] ?: true,
            duplicateCheckModeManual = prefs[Keys.DUPLICATE_CHECK_MODE_MANUAL] ?: ExpensesSettings.MODE_LOCAL,
            duplicateCheckModeAutomatic = prefs[Keys.DUPLICATE_CHECK_MODE_AUTOMATIC] ?: ExpensesSettings.MODE_LOCAL,
            autoAcceptDuplicateMerges = prefs[Keys.AUTO_ACCEPT_DUPLICATE_MERGES] ?: false,
            automaticProtectionReviewOnly = prefs[Keys.AUTOMATIC_PROTECTION_REVIEW_ONLY] ?: false,
            nearDuplicateTimeWindowMinutes = prefs[Keys.NEAR_DUPLICATE_TIME_WINDOW_MINUTES]
                ?: ExpensesSettings.NEAR_DUP_DEFAULT_WINDOW_MINUTES,
            duplicateRuleSetGlobalCombinator = prefs[Keys.DUPLICATE_RULE_SET_GLOBAL_COMBINATOR]
                ?: ExpensesSettings.RULE_SET_OR,
            merchantCategoryMemoryEnabled = prefs[Keys.MERCHANT_CATEGORY_MEMORY_ENABLED] ?: false,
            merchantCategoryMemoryThreshold = prefs[Keys.MERCHANT_CATEGORY_MEMORY_THRESHOLD]
                ?: ExpensesSettings.MERCHANT_MEMORY_DEFAULT_THRESHOLD,
            widgetBorderEnabled = prefs[Keys.WIDGET_BORDER_ENABLED] ?: true,
            widgetBorderThicknessDp = prefs[Keys.WIDGET_BORDER_THICKNESS_DP] ?: ExpensesSettings.THICKNESS_MEDIUM,
            widgetBorderColorArgb = prefs[Keys.WIDGET_BORDER_COLOR_ARGB] ?: VoxColorPalette.presets.first(),
            todayEffect = prefs[Keys.TODAY_EFFECT] ?: TodayEffect.NONE.name,
            todayEffectStyle = prefs[Keys.TODAY_EFFECT_STYLE] ?: TodayEffectStyle.RING.name,
            todayEffectColor = prefs[Keys.TODAY_EFFECT_COLOR] ?: ExpensesSettings.TODAY_EFFECT_DEFAULT_COLOR,
            todayEffectColor2 = prefs[Keys.TODAY_EFFECT_COLOR_2],
            todayEffectSpeed = prefs[Keys.TODAY_EFFECT_SPEED] ?: 1f,
            todayEffectShowInWidget = prefs[Keys.TODAY_EFFECT_SHOW_IN_WIDGET] ?: true,
            batchCleanupManualReview = prefs[Keys.BATCH_CLEANUP_MANUAL_REVIEW] ?: true,
            notificationsSystemDefault = prefs[Keys.NOTIFICATIONS_SYSTEM_DEFAULT] ?: true,
            notificationsVibrationEnabled = prefs[Keys.NOTIFICATIONS_VIBRATION_ENABLED] ?: true,
            notificationsSoundUri = prefs[Keys.NOTIFICATIONS_SOUND_URI],
            notificationsVolume = prefs[Keys.NOTIFICATIONS_VOLUME] ?: 100,
            notificationsLength = prefs[Keys.NOTIFICATIONS_LENGTH] ?: ExpensesSettings.LENGTH_SHORT,
            notificationsChannelVersion = prefs[Keys.NOTIFICATIONS_CHANNEL_VERSION] ?: 1
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

    override suspend fun setIsGridView(enabled: Boolean) {
        dataStore.edit { it[Keys.IS_GRID_VIEW] = enabled }
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

    override suspend fun setAutoOpenScannedExpense(enabled: Boolean) {
        dataStore.edit { it[Keys.AUTO_OPEN_SCANNED_EXPENSE] = enabled }
    }

    override suspend fun setLocationPrefillEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.LOCATION_PREFILL_ENABLED] = enabled }
    }

    override suspend fun setDuplicateCheckModeManual(mode: String) {
        dataStore.edit { it[Keys.DUPLICATE_CHECK_MODE_MANUAL] = mode }
    }

    override suspend fun setDuplicateCheckModeAutomatic(mode: String) {
        dataStore.edit { it[Keys.DUPLICATE_CHECK_MODE_AUTOMATIC] = mode }
    }

    override suspend fun setAutoAcceptDuplicateMerges(enabled: Boolean) {
        dataStore.edit { it[Keys.AUTO_ACCEPT_DUPLICATE_MERGES] = enabled }
    }

    override suspend fun setAutomaticProtectionReviewOnly(enabled: Boolean) {
        dataStore.edit { it[Keys.AUTOMATIC_PROTECTION_REVIEW_ONLY] = enabled }
    }

    override suspend fun setNearDuplicateTimeWindowMinutes(minutes: Int) {
        dataStore.edit { it[Keys.NEAR_DUPLICATE_TIME_WINDOW_MINUTES] = minutes }
    }

    override suspend fun setDuplicateRuleSetGlobalCombinator(combinator: String) {
        dataStore.edit { it[Keys.DUPLICATE_RULE_SET_GLOBAL_COMBINATOR] = combinator }
    }

    override suspend fun setMerchantCategoryMemoryEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.MERCHANT_CATEGORY_MEMORY_ENABLED] = enabled }
    }

    override suspend fun setMerchantCategoryMemoryThreshold(count: Int) {
        dataStore.edit { it[Keys.MERCHANT_CATEGORY_MEMORY_THRESHOLD] = count }
    }

    override suspend fun setWidgetBorderEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.WIDGET_BORDER_ENABLED] = enabled }
    }

    override suspend fun setWidgetBorderThicknessDp(thicknessDp: Int) {
        dataStore.edit { it[Keys.WIDGET_BORDER_THICKNESS_DP] = thicknessDp }
    }

    override suspend fun setWidgetBorderColorArgb(colorArgb: Long) {
        dataStore.edit { it[Keys.WIDGET_BORDER_COLOR_ARGB] = colorArgb }
    }

    override suspend fun setTodayEffect(effect: String) {
        dataStore.edit { it[Keys.TODAY_EFFECT] = effect }
    }

    override suspend fun setTodayEffectStyle(style: String) {
        dataStore.edit { it[Keys.TODAY_EFFECT_STYLE] = style }
    }

    override suspend fun setTodayEffectColor(colorArgb: Long) {
        dataStore.edit { it[Keys.TODAY_EFFECT_COLOR] = colorArgb }
    }

    override suspend fun setTodayEffectColor2(colorArgb: Long?) {
        dataStore.edit {
            if (colorArgb == null) it.remove(Keys.TODAY_EFFECT_COLOR_2) else it[Keys.TODAY_EFFECT_COLOR_2] = colorArgb
        }
    }

    override suspend fun setTodayEffectSpeed(speed: Float) {
        dataStore.edit { it[Keys.TODAY_EFFECT_SPEED] = speed }
    }

    override suspend fun setTodayEffectShowInWidget(enabled: Boolean) {
        dataStore.edit { it[Keys.TODAY_EFFECT_SHOW_IN_WIDGET] = enabled }
    }

    override suspend fun setBatchCleanupManualReview(enabled: Boolean) {
        dataStore.edit { it[Keys.BATCH_CLEANUP_MANUAL_REVIEW] = enabled }
    }

    override suspend fun setNotificationsSystemDefault(enabled: Boolean) {
        dataStore.edit { it[Keys.NOTIFICATIONS_SYSTEM_DEFAULT] = enabled }
    }

    override suspend fun setNotificationsVibrationEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.NOTIFICATIONS_VIBRATION_ENABLED] = enabled }
    }

    override suspend fun setNotificationsSoundUri(uri: String?) {
        dataStore.edit {
            if (uri == null) it.remove(Keys.NOTIFICATIONS_SOUND_URI) else it[Keys.NOTIFICATIONS_SOUND_URI] = uri
        }
    }

    override suspend fun setNotificationsVolume(volume: Int) {
        dataStore.edit { it[Keys.NOTIFICATIONS_VOLUME] = volume }
    }

    override suspend fun setNotificationsLength(length: String) {
        dataStore.edit { it[Keys.NOTIFICATIONS_LENGTH] = length }
    }

    override suspend fun setNotificationsChannelVersion(version: Int) {
        dataStore.edit { it[Keys.NOTIFICATIONS_CHANNEL_VERSION] = version }
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
            prefs[Keys.IS_GRID_VIEW] = settings.isGridView
            prefs[Keys.DEBUG_TOASTS_ENABLED] = settings.debugToastsEnabled
            prefs[Keys.THEME_DARK_MODE] = settings.themeDarkMode
            prefs[Keys.THEME_COLORED] = settings.themeColored
            prefs[Keys.ATTACH_PHOTO_ON_SCAN] = settings.attachPhotoOnScan
            prefs[Keys.ATTACH_PHOTO_ON_RETRY] = settings.attachPhotoOnRetry
            prefs[Keys.AUTO_OPEN_SCANNED_EXPENSE] = settings.autoOpenScannedExpense
            prefs[Keys.LOCATION_PREFILL_ENABLED] = settings.locationPrefillEnabled
            prefs[Keys.DUPLICATE_CHECK_MODE_MANUAL] = settings.duplicateCheckModeManual
            prefs[Keys.DUPLICATE_CHECK_MODE_AUTOMATIC] = settings.duplicateCheckModeAutomatic
            prefs[Keys.AUTO_ACCEPT_DUPLICATE_MERGES] = settings.autoAcceptDuplicateMerges
            prefs[Keys.AUTOMATIC_PROTECTION_REVIEW_ONLY] = settings.automaticProtectionReviewOnly
            prefs[Keys.NEAR_DUPLICATE_TIME_WINDOW_MINUTES] = settings.nearDuplicateTimeWindowMinutes
            prefs[Keys.DUPLICATE_RULE_SET_GLOBAL_COMBINATOR] = settings.duplicateRuleSetGlobalCombinator
            prefs[Keys.MERCHANT_CATEGORY_MEMORY_ENABLED] = settings.merchantCategoryMemoryEnabled
            prefs[Keys.MERCHANT_CATEGORY_MEMORY_THRESHOLD] = settings.merchantCategoryMemoryThreshold
            prefs[Keys.WIDGET_BORDER_ENABLED] = settings.widgetBorderEnabled
            prefs[Keys.WIDGET_BORDER_THICKNESS_DP] = settings.widgetBorderThicknessDp
            prefs[Keys.WIDGET_BORDER_COLOR_ARGB] = settings.widgetBorderColorArgb
            prefs[Keys.TODAY_EFFECT] = settings.todayEffect
            prefs[Keys.TODAY_EFFECT_STYLE] = settings.todayEffectStyle
            prefs[Keys.TODAY_EFFECT_COLOR] = settings.todayEffectColor
            if (settings.todayEffectColor2 == null) {
                prefs.remove(Keys.TODAY_EFFECT_COLOR_2)
            } else {
                prefs[Keys.TODAY_EFFECT_COLOR_2] = settings.todayEffectColor2
            }
            prefs[Keys.TODAY_EFFECT_SPEED] = settings.todayEffectSpeed
            prefs[Keys.TODAY_EFFECT_SHOW_IN_WIDGET] = settings.todayEffectShowInWidget
            prefs[Keys.BATCH_CLEANUP_MANUAL_REVIEW] = settings.batchCleanupManualReview
            prefs[Keys.NOTIFICATIONS_SYSTEM_DEFAULT] = settings.notificationsSystemDefault
            prefs[Keys.NOTIFICATIONS_VIBRATION_ENABLED] = settings.notificationsVibrationEnabled
            if (settings.notificationsSoundUri == null) {
                prefs.remove(Keys.NOTIFICATIONS_SOUND_URI)
            } else {
                prefs[Keys.NOTIFICATIONS_SOUND_URI] = settings.notificationsSoundUri
            }
            prefs[Keys.NOTIFICATIONS_VOLUME] = settings.notificationsVolume
            prefs[Keys.NOTIFICATIONS_LENGTH] = settings.notificationsLength
            prefs[Keys.NOTIFICATIONS_CHANNEL_VERSION] = settings.notificationsChannelVersion
            // appCacheJson intentionally untouched — see interface doc comment.
        }
    }

    private fun defaultDeviceLanguage(): String =
        java.util.Locale.getDefault().language.ifBlank { ExpensesSettings.DEFAULT_LANGUAGE }
}
