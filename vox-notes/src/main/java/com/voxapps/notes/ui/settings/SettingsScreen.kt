package com.voxapps.notes.ui.settings

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
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Palette
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
import com.voxapps.logging.ui.LogViewerStrings
import com.voxapps.logging.ui.LogsSettingsTab
import com.voxapps.logging.ui.LogsTabStrings
import com.voxapps.notes.data.preferences.NotesSettings
import com.voxapps.notes.data.preferences.NotesSettingsRepository
import com.voxapps.notes.state.NotesStateManager
import com.voxapps.notes.state.NotesUiState
import com.voxapps.notes.ui.LocalLanguageManager

private enum class SettingsPage { MENU, GENERAL, THEME, NOTIFICATIONS, CATEGORIES, NOTE_CLEANUP, LOGS }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    stateManager: NotesStateManager,
    settingsRepo: NotesSettingsRepository,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val languageManager = LocalLanguageManager.current
    val settings by settingsRepo.settingsFlow.collectAsStateWithLifecycle(initialValue = NotesSettings())
    val ui by stateManager.uiState.collectAsStateWithLifecycle()
    val categories = (ui as? NotesUiState.Unlocked)?.categories ?: emptyList()
    val notes = (ui as? NotesUiState.Unlocked)?.notes?.map { it.note } ?: emptyList()

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

    // System back mirrors the top-bar arrow: subpage → menu, menu → notes. Without this the back
    // gesture reaches the activity and closes the app.
    BackHandler { if (page == SettingsPage.MENU) onBack() else page = SettingsPage.MENU }

    val title = when (page) {
        SettingsPage.MENU -> languageManager.getString("settings")
        SettingsPage.GENERAL -> languageManager.getString("general")
        SettingsPage.THEME -> languageManager.getString("theme_section")
        SettingsPage.NOTIFICATIONS -> languageManager.getString("notifications_settings_title")
        SettingsPage.CATEGORIES -> languageManager.getString("categories_settings_title")
        SettingsPage.NOTE_CLEANUP -> languageManager.getString("note_cleanup_settings_title")
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
                SettingsSectionHeader(languageManager.getString("settings_section_data"))
                ListItem(
                    headlineContent = { Text(languageManager.getString("categories_settings_title")) },
                    leadingContent = { Icon(Icons.AutoMirrored.Filled.MergeType, contentDescription = null) },
                    modifier = Modifier.fillMaxWidth().clickable { page = SettingsPage.CATEGORIES }
                )
                ListItem(
                    headlineContent = { Text(languageManager.getString("note_cleanup_settings_title")) },
                    leadingContent = { Icon(Icons.Filled.CleaningServices, contentDescription = null) },
                    modifier = Modifier.fillMaxWidth().clickable { page = SettingsPage.NOTE_CLEANUP }
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
                    onEffectChange = { stateManager.setTodayEffect(it.name) },
                    onStyleChange = { stateManager.setTodayEffectStyle(it.name) },
                    onPrimaryColorChange = { stateManager.setTodayEffectColor(it) },
                    onSecondaryColorChange = { stateManager.setTodayEffectColor2(it) },
                    onSpeedMultiplierChange = { stateManager.setTodayEffectSpeed(it) },
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
                        speedLabel = languageManager.getString("today_effect_speed")
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
            SettingsPage.CATEGORIES -> CategoriesSettingsTab(
                settings = settings,
                categories = categories,
                stateManager = stateManager,
                modifier = mod
            )
            SettingsPage.NOTE_CLEANUP -> NoteCleanupSettingsTab(
                settings = settings,
                notes = notes,
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
                    toastsLabel = languageManager.getString("debug_toasts_label"),
                    viewer = LogViewerStrings(
                        sectionTitle = languageManager.getString("verbose_logging_section"),
                        clearLabel = languageManager.getString("clear_logs"),
                        copyLabel = languageManager.getString("copy_button"),
                        shareLabel = languageManager.getString("share_button"),
                        noLogsLabel = languageManager.getString("no_logs")
                    )
                ),
                shareSubject = "VoxNotes Logs",
                modifier = mod
            )
        }
    }
}
