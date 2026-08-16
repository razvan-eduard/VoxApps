package com.voxapps.commander.ui.screens.settings

import com.voxapps.design.picklist.Picklist
import com.voxapps.commander.ui.LocalLanguageManager

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import com.voxapps.commander.data.preferences.SettingsRepository
import com.voxapps.commander.data.remote.RemoteModelRegistry
import com.voxapps.commander.domain.localization.LanguageManager
import com.voxapps.commander.state.AppStateManager
import com.voxapps.commander.utils.Strings
import kotlinx.coroutines.launch
import com.voxapps.services.SchemaCatalog
import com.voxapps.services.RemoteSchema
import com.voxapps.design.settings.SchemaUpdatesSection
import com.voxapps.design.settings.SchemaUpdatesStrings
import com.voxapps.design.settings.ThemeSettingsBody
import com.voxapps.design.settings.ThemeSettingsStrings
import com.voxapps.design.VoxDarkMode
import com.voxapps.design.toEnumOr
import com.voxapps.design.settings.SettingsSectionCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GeneralSettingsTab(

    settingsRepo: SettingsRepository,
    appStateManager: AppStateManager
) {
        val languageManager = LocalLanguageManager.current
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current

    val uiState by appStateManager.uiState.collectAsStateWithLifecycle()

    var modelRepoUrl by remember { mutableStateOf(settingsRepo.getSettingsSnapshot().modelRepoBaseUrl) }
    var selectedLanguage by remember(uiState.language) { mutableStateOf(uiState.language) }
    val languages = languageManager.getAvailableLanguages()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = { focusManager.clearFocus() }
            ),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // The same section every app that reads schemas shows. What differs between them is only
        // where the two values are stored, which is why it takes them rather than reading anything.
        SettingsSectionCard(languageManager.getString("schema_updates_section")) {
            SchemaUpdatesSection(
                strings = SchemaUpdatesStrings(
                    sectionLabel = languageManager.getString("schema_updates_section"),
                    description = languageManager.getString("schema_updates_desc"),
                    useRemoteLabel = languageManager.getString("schema_use_remote_label"),
                    useRemoteDescription = languageManager.getString("schema_use_remote_desc"),
                    repositoryUrlLabel = languageManager.getString("model_repository_url"),
                    checkNow = languageManager.getString("schema_sync_now"),
                    followingFormat = languageManager.getString("schema_following"),
                    inStep = languageManager.getString("schema_in_step"),
                    servingFormat = languageManager.getString("schema_serving"),
                    unreachableFormat = languageManager.getString("schema_unreachable"),
                    notCheckedYet = languageManager.getString("schema_not_checked"),
                    usingBundled = languageManager.getString("schema_using_bundled"),
                    problemFormat = languageManager.getString("schema_problem"),
                    reasonRejected = languageManager.getString("schema_reason_rejected"),
                    reasonUnsigned = languageManager.getString("schema_reason_unsigned"),
                    reasonUnreachable = languageManager.getString("schema_reason_unreachable")
                ),
                repositoryUrl = modelRepoUrl,
                useRemote = uiState.useRemoteSchemas,
                onRepositoryUrlChange = {
                    modelRepoUrl = it
                    scope.launch { settingsRepo.setModelRepoBaseUrl(it) }
                },
                onUseRemoteChange = { appStateManager.setUseRemoteSchemas(it) },
                onSchemasChanged = { appStateManager.refreshAll() }
            )

        }

        SettingsSectionCard(languageManager.getString("language")) {
            Picklist(
                items = languages,
                selected = selectedLanguage,
                itemLabel = { it.uppercase() },
                onSelect = { lang ->
                    selectedLanguage = lang
                    languageManager.loadLanguage(lang)
                    appStateManager.setAppLanguage(lang)
                }
            )

        }

        // Voice Language (used by STT, LLM interpretation, TTS)
        SettingsSectionCard(languageManager.getString("voice_language")) {
            Text(
                text = languageManager.getString("voice_language_desc") ?: "Language used for voice recognition, AI interpretation, and text-to-speech",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            val voiceLanguages = Strings.VoiceLanguages.ALL
            val autoDetect = uiState.voiceLanguageAutoDetect
            Column {
                Picklist(
                    items = voiceLanguages,
                    selected = uiState.voiceLanguage,
                    itemLabel = { it.uppercase() },
                    onSelect = { lang ->
                        appStateManager.setVoiceLanguage(lang)
                        appStateManager.setModelFilterLang(lang)
                    }
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { appStateManager.setVoiceLanguageAutoDetect(!autoDetect) }
                ) {
                    Checkbox(
                        checked = autoDetect,
                        onCheckedChange = { appStateManager.setVoiceLanguageAutoDetect(it) }
                    )
                    Text(
                        text = "AutoDetect (Only for supported models)",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (autoDetect) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

        }

        ThemeSettingsBody(
            darkMode = uiState.themeDarkMode.toEnumOr(VoxDarkMode.SYSTEM),
            colored = uiState.themeColored,
            onDarkModeChange = { appStateManager.setThemeDarkMode(it.name) },
            onColoredChange = { appStateManager.setThemeColored(it) },
            strings = ThemeSettingsStrings(
                darkModeSectionLabel = languageManager.getString("theme_section"),
                themeSystemLabel = languageManager.getString("theme_system"),
                themeLightLabel = languageManager.getString("theme_light"),
                themeDarkLabel = languageManager.getString("theme_dark"),
                coloredLabel = languageManager.getString("theme_colored"),
                coloredDesc = languageManager.getString("theme_colored_desc")
            )
        )
    }
}
