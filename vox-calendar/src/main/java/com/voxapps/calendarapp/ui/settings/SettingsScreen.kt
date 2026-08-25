package com.voxapps.calendarapp.ui.settings

import android.app.Activity
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.IntentCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.voxapps.calendarapp.data.CalendarRepository
import com.voxapps.calendarapp.data.preferences.CalendarSettings
import com.voxapps.calendarapp.data.preferences.CalendarSettingsRepository
import com.voxapps.calendarapp.domain.localization.LanguageManager
import com.voxapps.calendarapp.state.CalendarStateManager
import com.voxapps.calendarapp.ui.LocalLanguageManager
import com.voxapps.design.VoxDarkMode
import com.voxapps.design.color.VoxColorSwatchPicker
import com.voxapps.design.effects.TodayEffect
import com.voxapps.design.effects.TodayEffectStyle
import com.voxapps.design.notifications.NotificationSoundPlayer
import com.voxapps.design.settings.NotificationSettingsCard
import com.voxapps.design.settings.SettingsMenuDescription
import com.voxapps.design.settings.SettingsSectionHeader
import com.voxapps.design.settings.ThemeSettingsScreen
import com.voxapps.design.settings.ThemeSettingsStrings
import com.voxapps.design.settings.TodayEffectSettings
import com.voxapps.design.settings.TodayEffectStrings
import com.voxapps.design.toEnumOr
import com.voxapps.logging.ui.LogViewerStrings
import com.voxapps.design.settings.SettingsSectionCard
import com.voxapps.design.settings.LogsSettingsTab
import com.voxapps.design.settings.LogsTabStrings

private enum class SettingsPage { MENU, GENERAL, THEME, NOTIFICATIONS, ICS, BACKUP, LOGS }

/** Settings shell (mirrors vox-expenses' SettingsScreen menu/subpage shape). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    stateManager: CalendarStateManager,
    settingsRepo: CalendarSettingsRepository,
    calendarRepository: CalendarRepository,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val languageManager = LocalLanguageManager.current
    val settings by settingsRepo.settingsFlow.collectAsStateWithLifecycle(initialValue = CalendarSettings())
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

    BackHandler { if (page == SettingsPage.MENU) onBack() else page = SettingsPage.MENU }

    val title = when (page) {
        SettingsPage.MENU -> languageManager.getString("settings")
        SettingsPage.GENERAL -> languageManager.getString("general")
        SettingsPage.THEME -> languageManager.getString("theme_section")
        SettingsPage.NOTIFICATIONS -> languageManager.getString("notifications_settings_title")
        SettingsPage.ICS -> languageManager.getString("ics_settings_title")
        SettingsPage.BACKUP -> languageManager.getString("backup_restore_title")
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
    ) { padding ->
        when (page) {
            SettingsPage.MENU -> {
                Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                    SettingsSectionHeader(languageManager.getString("settings_section_general"))
                    ListItem(
                        headlineContent = { Text(languageManager.getString("general")) },
                        supportingContent = { SettingsMenuDescription(languageManager.getString("general_menu_desc")) },
                        leadingContent = { Icon(Icons.Filled.Tune, contentDescription = null) },
                        modifier = Modifier.fillMaxWidth().clickable { page = SettingsPage.GENERAL }
                    )
                    SettingsSectionHeader(languageManager.getString("settings_section_appearance"))
                    ListItem(
                        headlineContent = { Text(languageManager.getString("theme_section")) },
                        supportingContent = { SettingsMenuDescription(languageManager.getString("theme_menu_desc")) },
                        leadingContent = { Icon(Icons.Filled.Palette, contentDescription = null) },
                        modifier = Modifier.fillMaxWidth().clickable { page = SettingsPage.THEME }
                    )
                    SettingsSectionHeader(languageManager.getString("settings_section_notifications"))
                    ListItem(
                        headlineContent = { Text(languageManager.getString("notifications_settings_title")) },
                        supportingContent = { SettingsMenuDescription(languageManager.getString("notifications_settings_menu_desc")) },
                        leadingContent = { Icon(Icons.Filled.Notifications, contentDescription = null) },
                        modifier = Modifier.fillMaxWidth().clickable { page = SettingsPage.NOTIFICATIONS }
                    )
                    SettingsSectionHeader(languageManager.getString("settings_section_data"))
                    ListItem(
                        headlineContent = { Text(languageManager.getString("ics_settings_title")) },
                        supportingContent = { SettingsMenuDescription(languageManager.getString("ics_settings_menu_desc")) },
                        leadingContent = { Icon(Icons.Filled.CalendarMonth, contentDescription = null) },
                        modifier = Modifier.fillMaxWidth().clickable { page = SettingsPage.ICS }
                    )
                    ListItem(
                        headlineContent = { Text(languageManager.getString("backup_restore_title")) },
                        supportingContent = { SettingsMenuDescription(languageManager.getString("backup_restore_menu_desc")) },
                        leadingContent = { Icon(Icons.Filled.Backup, contentDescription = null) },
                        modifier = Modifier.fillMaxWidth().clickable { page = SettingsPage.BACKUP }
                    )
                    SettingsSectionHeader(languageManager.getString("settings_section_advanced"))
                    ListItem(
                        headlineContent = { Text(languageManager.getString("logs_settings_title")) },
                        supportingContent = { SettingsMenuDescription(languageManager.getString("logs_settings_menu_desc")) },
                        leadingContent = { Icon(Icons.Filled.BugReport, contentDescription = null) },
                        modifier = Modifier.fillMaxWidth().clickable { page = SettingsPage.LOGS }
                    )
                }
            }
            SettingsPage.GENERAL -> GeneralSettingsTab(
                settings = settings,
                stateManager = stateManager,
                modifier = Modifier.fillMaxSize().padding(padding)
            )
            SettingsPage.THEME -> ThemeSettingsScreen(
                darkMode = settings.themeDarkMode.toEnumOr(VoxDarkMode.SYSTEM),
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
                    effect = settings.todayEffect.toEnumOr(TodayEffect.NONE),
                    style = settings.todayEffectStyle.toEnumOr(TodayEffectStyle.RING),
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
                modifier = Modifier.fillMaxSize().padding(padding),
                extraContent = { CalendarThemeExtras(settings = settings, stateManager = stateManager, languageManager = languageManager) }
            )
            SettingsPage.NOTIFICATIONS -> Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
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
            SettingsPage.ICS -> IcsSettingsTab(
                calendarRepository = calendarRepository,
                modifier = Modifier.fillMaxSize().padding(padding)
            )
            SettingsPage.BACKUP -> Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
                BackupSettingsSection(settingsRepo = settingsRepo, settings = settings)
            }
            SettingsPage.LOGS -> LogsSettingsTab(
                enabled = settings.debugLoggingEnabled,
                onEnabledChange = { stateManager.setDebugLoggingEnabled(it) },
                toastsEnabled = settings.debugToastsEnabled,
                onToastsEnabledChange = { stateManager.setDebugToastsEnabled(it) },
                strings = LogsTabStrings(
                    sectionLabel = languageManager.getString("logging_section"),
                    enabledLabel = languageManager.getString("debug_logging"),
                    enabledDesc = languageManager.getString("debug_logging_desc"),
                    toastsLabel = languageManager.getString("debug_toasts_label"),
                    viewer = LogViewerStrings(
                        sectionTitle = languageManager.getString("verbose_logging_section"),
                        clearLabel = languageManager.getString("clear_logs"),
                        copyLabel = languageManager.getString("copy_button"),
                        shareLabel = languageManager.getString("share_button"),
                        noLogsLabel = languageManager.getString("no_logs")
                    )
                ),
                shareSubject = "VoxCalendar Logs",
                modifier = Modifier.fillMaxSize().padding(padding)
            )
        }
    }
}

/** Appearance-adjacent settings that live on the Theme page via [ThemeSettingsScreen]'s
 *  `extraContent` slot rather than General — decorative transition animations and the home-screen
 *  widget's day-card border (on/off, thickness, color). */
@Composable
private fun ColumnScope.CalendarThemeExtras(
    settings: CalendarSettings,
    stateManager: CalendarStateManager,
    languageManager: LanguageManager
) {
    SettingsSectionCard(languageManager.getString("animations_enabled")) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                languageManager.getString("animations_enabled_desc"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            Switch(
                checked = settings.animationsEnabled,
                onCheckedChange = { stateManager.setAnimationsEnabled(it) }
            )
        }
    }

    SettingsSectionCard(languageManager.getString("widget_zone")) {
        // What the widget shows, then how it is drawn — one card, since both are the same widget.
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(languageManager.getString("widget_show_event_details"), style = MaterialTheme.typography.bodyLarge)
                Text(
                    languageManager.getString("widget_show_event_details_desc"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = settings.showEventDetailsInWidget,
                onCheckedChange = { stateManager.setShowEventDetailsInWidget(it) }
            )
        }

        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(languageManager.getString("widget_border_enabled"), style = MaterialTheme.typography.bodyLarge)
                Text(
                    languageManager.getString("widget_border_enabled_desc"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = settings.widgetBorderEnabled,
                onCheckedChange = { stateManager.setWidgetBorderEnabled(it) }
            )
        }
        Text(languageManager.getString("widget_border_thickness"), style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            val thicknessOptions = listOf(
                CalendarSettings.THICKNESS_THIN to "widget_border_thickness_thin",
                CalendarSettings.THICKNESS_MEDIUM to "widget_border_thickness_medium",
                CalendarSettings.THICKNESS_THICK to "widget_border_thickness_thick"
            )
            thicknessOptions.forEach { (thicknessDp, labelKey) ->
                FilterChip(
                    enabled = settings.widgetBorderEnabled,
                    selected = settings.widgetBorderThicknessDp == thicknessDp,
                    onClick = { stateManager.setWidgetBorderThicknessDp(thicknessDp) },
                    label = { Text(languageManager.getString(labelKey)) }
                )
            }
        }
        Text(languageManager.getString("widget_border_color"), style = MaterialTheme.typography.labelLarge)
        VoxColorSwatchPicker(
            selectedColor = settings.widgetBorderColorArgb,
            onColorSelected = { stateManager.setWidgetBorderColorArgb(it) },
            customColorDialogTitle = languageManager.getString("custom_color_title"),
            customColorUseLabel = languageManager.getString("use_color_button"),
            customColorCancelLabel = languageManager.getString("cancel"),
            customColorHueLabel = languageManager.getString("hue_label"),
            customColorSaturationLabel = languageManager.getString("saturation_label"),
            customColorBrightnessLabel = languageManager.getString("brightness_label")
        )
    }
}
