package com.voxapps.expenses.ui.settings

import android.app.Activity
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MergeType
import androidx.compose.material.icons.filled.AttachMoney
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.IntentCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.voxapps.design.VoxDarkMode
import com.voxapps.design.effects.TodayEffect
import com.voxapps.design.effects.TodayEffectStyle
import com.voxapps.design.notifications.NotificationSoundPlayer
import com.voxapps.design.settings.NotificationSettingsCard
import com.voxapps.design.settings.SettingsSectionHeader
import com.voxapps.design.settings.ThemeSettingsScreen
import com.voxapps.design.settings.ThemeSettingsStrings
import com.voxapps.design.settings.TodayEffectSettings
import com.voxapps.design.settings.TodayEffectStrings
import com.voxapps.expenses.data.ExchangeRateRepository
import com.voxapps.expenses.data.preferences.ExpensesSettings
import com.voxapps.expenses.data.preferences.ExpensesSettingsRepository
import com.voxapps.expenses.state.ExpensesStateManager
import com.voxapps.expenses.state.ExpensesUiState
import com.voxapps.expenses.ui.LocalLanguageManager
import com.voxapps.logging.ui.LogViewerStrings
import com.voxapps.logging.ui.LogsSettingsTab
import com.voxapps.logging.ui.LogsTabStrings

private enum class SettingsPage {
    MENU, GENERAL, THEME, NOTIFICATIONS, VOICE, CATEGORIES, EXPENSE_CLEANUP, CURRENCY, NOTIFICATION_CAPTURE, SPENDING_LIMITS, LOGS
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    stateManager: ExpensesStateManager,
    settingsRepo: ExpensesSettingsRepository,
    exchangeRateRepository: ExchangeRateRepository,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val languageManager = LocalLanguageManager.current
    val settings by settingsRepo.settingsFlow.collectAsStateWithLifecycle(initialValue = ExpensesSettings())
    val ui by stateManager.uiState.collectAsStateWithLifecycle()
    val categories = (ui as? ExpensesUiState.Unlocked)?.categories ?: emptyList()
    val expenses = (ui as? ExpensesUiState.Unlocked)?.expenses?.map { it.expense } ?: emptyList()
    val spendingLimits by stateManager.spendingLimits.collectAsStateWithLifecycle(initialValue = emptyList())

    var page by remember { mutableStateOf(SettingsPage.MENU) }

    val ringtonePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val uri = IntentCompat.getParcelableExtra(result.data!!, RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
            stateManager.setNotificationsSoundUri(uri?.toString())
        }
    }

    val triggerPreview = { soundOnly: Boolean, overrideVolume: Int?, overrideLength: String?, overrideVibration: Boolean? ->
        NotificationSoundPlayer.play(
            context = context,
            soundUri = settings.notificationsSoundUri,
            volume = overrideVolume ?: settings.notificationsVolume,
            length = overrideLength ?: settings.notificationsLength,
            vibrationEnabled = overrideVibration ?: settings.notificationsVibrationEnabled,
            soundOnly = soundOnly
        )
    }

    // System back mirrors the top-bar arrow: subpage -> menu, menu -> expenses list.
    BackHandler { if (page == SettingsPage.MENU) onBack() else page = SettingsPage.MENU }

    val title = when (page) {
        SettingsPage.MENU -> languageManager.getString("settings")
        SettingsPage.GENERAL -> languageManager.getString("general")
        SettingsPage.THEME -> languageManager.getString("theme_section")
        SettingsPage.NOTIFICATIONS -> languageManager.getString("notifications_settings_title")
        SettingsPage.VOICE -> languageManager.getString("voice_settings_title")
        SettingsPage.CATEGORIES -> languageManager.getString("categories_settings_title")
        SettingsPage.EXPENSE_CLEANUP -> languageManager.getString("expense_cleanup_settings_title")
        SettingsPage.CURRENCY -> languageManager.getString("currency_settings_title")
        SettingsPage.NOTIFICATION_CAPTURE -> languageManager.getString("notification_capture_title")
        SettingsPage.SPENDING_LIMITS -> languageManager.getString("spending_limits_title")
        SettingsPage.LOGS -> languageManager.getString("logs_settings_title")
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = { if (page == SettingsPage.MENU) onBack() else page = SettingsPage.MENU }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = languageManager.getString("back"))
                    }
                }
            )
        }
    ) { pad ->
        val mod = Modifier.fillMaxSize().padding(pad)
        when (page) {
            SettingsPage.MENU -> Column(mod) {
                SettingsSectionHeader(languageManager.getString("settings_section_general"))
                ListItem(
                    headlineContent = { Text(languageManager.getString("general")) },
                    leadingContent = { Icon(Icons.Filled.Tune, contentDescription = null) },
                    modifier = Modifier.fillMaxWidth().clickable { page = SettingsPage.GENERAL }
                )
                SettingsSectionHeader(languageManager.getString("settings_section_appearance"))
                ListItem(
                    headlineContent = { Text(languageManager.getString("theme_section")) },
                    leadingContent = { Icon(Icons.Filled.Palette, contentDescription = null) },
                    modifier = Modifier.fillMaxWidth().clickable { page = SettingsPage.THEME }
                )
                SettingsSectionHeader(languageManager.getString("settings_section_notifications"))
                ListItem(
                    headlineContent = { Text(languageManager.getString("notifications_settings_title")) },
                    leadingContent = { Icon(Icons.Filled.Notifications, contentDescription = null) },
                    modifier = Modifier.fillMaxWidth().clickable { page = SettingsPage.NOTIFICATIONS }
                )
                SettingsSectionHeader(languageManager.getString("settings_section_capture"))
                ListItem(
                    headlineContent = { Text(languageManager.getString("voice_settings_title")) },
                    leadingContent = { Icon(Icons.Filled.Mic, contentDescription = null) },
                    modifier = Modifier.fillMaxWidth().clickable { page = SettingsPage.VOICE }
                )
                ListItem(
                    headlineContent = { Text(languageManager.getString("notification_capture_title")) },
                    leadingContent = { Icon(Icons.Filled.Notifications, contentDescription = null) },
                    modifier = Modifier.fillMaxWidth().clickable { page = SettingsPage.NOTIFICATION_CAPTURE }
                )
                SettingsSectionHeader(languageManager.getString("settings_section_data"))
                ListItem(
                    headlineContent = { Text(languageManager.getString("categories_settings_title")) },
                    leadingContent = { Icon(Icons.AutoMirrored.Filled.MergeType, contentDescription = null) },
                    modifier = Modifier.fillMaxWidth().clickable { page = SettingsPage.CATEGORIES }
                )
                ListItem(
                    headlineContent = { Text(languageManager.getString("expense_cleanup_settings_title")) },
                    leadingContent = { Icon(Icons.Filled.CleaningServices, contentDescription = null) },
                    modifier = Modifier.fillMaxWidth().clickable { page = SettingsPage.EXPENSE_CLEANUP }
                )
                ListItem(
                    headlineContent = { Text(languageManager.getString("currency_settings_title")) },
                    leadingContent = { Icon(Icons.Filled.AttachMoney, contentDescription = null) },
                    modifier = Modifier.fillMaxWidth().clickable { page = SettingsPage.CURRENCY }
                )
                ListItem(
                    headlineContent = { Text(languageManager.getString("spending_limits_title")) },
                    leadingContent = { Icon(Icons.Filled.Shield, contentDescription = null) },
                    modifier = Modifier.fillMaxWidth().clickable { page = SettingsPage.SPENDING_LIMITS }
                )
                SettingsSectionHeader(languageManager.getString("settings_section_advanced"))
                ListItem(
                    headlineContent = { Text(languageManager.getString("logs_settings_title")) },
                    leadingContent = { Icon(Icons.Filled.BugReport, contentDescription = null) },
                    modifier = Modifier.fillMaxWidth().clickable { page = SettingsPage.LOGS }
                )
            }
            SettingsPage.GENERAL -> GeneralSettingsTab(settings = settings, stateManager = stateManager, modifier = mod)
            SettingsPage.THEME -> ThemeSettingsScreen(
                darkMode = runCatching { VoxDarkMode.valueOf(settings.themeDarkMode) }.getOrDefault(VoxDarkMode.SYSTEM),
                colored = settings.themeColored,
                onDarkModeChange = { stateManager.setThemeDarkMode(it.name) },
                onColoredChange = { stateManager.setThemeColored(it) },
                strings = ThemeSettingsStrings(
                    darkModeSectionLabel = languageManager.getString("theme_section"),
                    themeSystemLabel = languageManager.getString("theme_system"),
                    themeLightLabel = languageManager.getString("theme_light"),
                    themeDarkLabel = languageManager.getString("theme_dark"),
                    coloredLabel = languageManager.getString("theme_colored"),
                    coloredDesc = languageManager.getString("theme_colored_desc")
                ),
                todayEffect = TodayEffectSettings(
                    effect = runCatching { TodayEffect.valueOf(settings.todayEffect) }.getOrDefault(TodayEffect.NONE),
                    style = runCatching { TodayEffectStyle.valueOf(settings.todayEffectStyle) }.getOrDefault(TodayEffectStyle.RING),
                    primaryColor = settings.todayEffectColor,
                    secondaryColor = settings.todayEffectColor2,
                    speedMultiplier = settings.todayEffectSpeed,
                    showInWidget = settings.todayEffectShowInWidget,
                    onEffectChange = { stateManager.setTodayEffect(it.name) },
                    onStyleChange = { stateManager.setTodayEffectStyle(it.name) },
                    onPrimaryColorChange = { stateManager.setTodayEffectColor(it) },
                    onSecondaryColorChange = { stateManager.setTodayEffectColor2(it) },
                    onSpeedMultiplierChange = { stateManager.setTodayEffectSpeed(it) },
                    onShowInWidgetChange = { stateManager.setTodayEffectShowInWidget(it) },
                    strings = TodayEffectStrings(
                        sectionLabel = languageManager.getString("today_effect_section"),
                        noneLabel = languageManager.getString("today_effect_none"),
                        fireLabel = languageManager.getString("today_effect_fire"),
                        glowLabel = languageManager.getString("today_effect_glow"),
                        wavesLabel = languageManager.getString("today_effect_waves"),
                        rainbowLabel = languageManager.getString("today_effect_rainbow"),
                        neonPulseLabel = languageManager.getString("today_effect_neon_pulse"),
                        styleLabel = languageManager.getString("today_effect_style"),
                        styleNoneLabel = languageManager.getString("today_effect_style_none"),
                        ringLabel = languageManager.getString("today_effect_style_ring"),
                        backgroundLabel = languageManager.getString("today_effect_style_background"),
                        fullLabel = languageManager.getString("today_effect_style_full"),
                        primaryColorLabel = languageManager.getString("today_effect_color"),
                        gradientLabel = languageManager.getString("today_effect_gradient"),
                        gradientDesc = languageManager.getString("today_effect_gradient_desc"),
                        secondaryColorLabel = languageManager.getString("today_effect_color_2"),
                        customColorDialogTitle = languageManager.getString("custom_color_title"),
                        customColorUseLabel = languageManager.getString("use_color_button"),
                        customColorCancelLabel = languageManager.getString("cancel"),
                        cancelLabel = languageManager.getString("cancel"),
                        hueLabel = languageManager.getString("hue_label"),
                        saturationLabel = languageManager.getString("saturation_label"),
                        brightnessLabel = languageManager.getString("brightness_label"),
                        speedLabel = languageManager.getString("today_effect_speed"),
                        showInWidgetLabel = languageManager.getString("today_effect_show_in_widget")
                    )
                ),
                modifier = mod
            )
            SettingsPage.NOTIFICATIONS -> Column(modifier = Modifier.fillMaxSize().padding(pad).padding(16.dp)) {
                NotificationSettingsCard(
                    systemDefault = settings.notificationsSystemDefault,
                    vibrationEnabled = settings.notificationsVibrationEnabled,
                    soundUri = settings.notificationsSoundUri,
                    volume = settings.notificationsVolume,
                    length = settings.notificationsLength,
                    onSystemDefaultChange = { stateManager.setNotificationsSystemDefault(it) },
                    onVibrationChange = { stateManager.setNotificationsVibrationEnabled(it) },
                    onVolumeChange = { stateManager.setNotificationsVolume(it) },
                    onLengthChange = { stateManager.setNotificationsLength(it) },
                    onPickSound = {
                        val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                            putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_NOTIFICATION)
                            putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, languageManager.getString("notifications_sound_label"))
                            putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, settings.notificationsSoundUri?.let { Uri.parse(it) })
                            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true)
                        }
                        ringtonePickerLauncher.launch(intent)
                    },
                    onPreview = triggerPreview,
                    getString = { languageManager.getString(it) }
                )
            }
            SettingsPage.VOICE -> VoiceSettingsTab(
                settings = settings,
                categories = categories,
                stateManager = stateManager,
                modifier = mod
            )
            SettingsPage.CATEGORIES -> CategoriesSettingsTab(
                settings = settings,
                categories = categories,
                stateManager = stateManager,
                modifier = mod
            )
            SettingsPage.EXPENSE_CLEANUP -> ExpenseCleanupSettingsTab(
                settings = settings,
                expenses = expenses,
                stateManager = stateManager,
                nextScheduledDedupMillis = (ui as? ExpensesUiState.Unlocked)?.nextScheduledDedupMillis,
                modifier = mod
            )
            SettingsPage.CURRENCY -> CurrencySettingsTab(
                settings = settings,
                exchangeRateRepository = exchangeRateRepository,
                stateManager = stateManager,
                modifier = mod
            )
            SettingsPage.NOTIFICATION_CAPTURE -> NotificationCaptureSettingsTab(
                paymentSourcePackages = settings.paymentSourcePackages,
                bankingSourcePackages = settings.bankingSourcePackages,
                autoAcceptNotificationExpenses = settings.autoAcceptNotificationExpenses,
                stateManager = stateManager,
                settingsRepo = settingsRepo,
                modifier = mod
            )
            SettingsPage.SPENDING_LIMITS -> SpendingLimitsSettingsTab(
                limits = spendingLimits,
                categories = categories,
                homeCurrency = settings.homeCurrency,
                stateManager = stateManager,
                modifier = mod
            )
            SettingsPage.LOGS -> LogsSettingsTab(
                enabled = settings.debugLoggingEnabled,
                onEnabledChange = { stateManager.setDebugLoggingEnabled(it) },
                toastsEnabled = settings.debugToastsEnabled,
                onToastsEnabledChange = { stateManager.setDebugToastsEnabled(it) },
                strings = LogsTabStrings(
                    enabledLabel = languageManager.getString("debug_logging"),
                    enabledDesc = languageManager.getString("debug_logging_desc"),
                    toastsLabel = languageManager.getString("debug_toasts"),
                    viewer = LogViewerStrings(
                        sectionTitle = languageManager.getString("verbose_logging_section"),
                        clearLabel = languageManager.getString("clear_logs"),
                        copyLabel = languageManager.getString("copy_button"),
                        shareLabel = languageManager.getString("share_button"),
                        noLogsLabel = languageManager.getString("no_logs")
                    )
                ),
                shareSubject = "VoxExpenses Logs",
                modifier = mod
            )
        }
    }
}
