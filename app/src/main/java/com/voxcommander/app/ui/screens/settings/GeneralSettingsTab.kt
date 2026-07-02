package com.voxcommander.app.ui.screens.settings

import androidx.compose.foundation.clickable
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
import com.voxcommander.app.data.preferences.SettingsRepository
import com.voxcommander.app.data.remote.RemoteModelRegistry
import com.voxcommander.app.domain.localization.LanguageManager
import com.voxcommander.app.state.AppStateManager
import com.voxcommander.app.utils.Strings
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GeneralSettingsTab(
    languageManager: LanguageManager,
    settingsRepo: SettingsRepository,
    appStateManager: AppStateManager
) {
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current

    val uiState by appStateManager.uiState.collectAsStateWithLifecycle()

    var modelRepoUrl by remember { mutableStateOf(settingsRepo.getSettingsSnapshot().modelRepoBaseUrl) }
    var selectedLanguage by remember(uiState.language) { mutableStateOf(uiState.language) }
    var expanded by remember { mutableStateOf(false) }
    val languages = languageManager.getAvailableLanguages()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .clickable(onClick = { focusManager.clearFocus() }),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(text = languageManager.getString("app_settings_section"), style = MaterialTheme.typography.titleMedium)

        // Repository Base URL with gray-out logic
        var isRepoFocused by remember { mutableStateOf(false) }
        TextField(
            value = modelRepoUrl,
            onValueChange = {
                modelRepoUrl = it
                kotlinx.coroutines.runBlocking { settingsRepo.setModelRepoBaseUrl(it) }
            },
            label = { Text(languageManager.getString("model_repository_url")) },
            placeholder = { Text(languageManager.getString("repository_url_placeholder")) },
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { isRepoFocused = it.isFocused },
            trailingIcon = {
                IconButton(onClick = {
                    scope.launch {
                        val success = RemoteModelRegistry.fetchJson(settingsRepo, force = true)
                        if (success) {
                            appStateManager.refreshAll()
                        }
                        com.voxcommander.app.domain.search.SearchProviderRegistry.fetchRemote(settingsRepo, force = true)
                    }
                }) {
                    Icon(Icons.Default.Refresh, contentDescription = "Sync JSON")
                }
            },
            colors = if (!isRepoFocused) TextFieldDefaults.colors(
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                unfocusedIndicatorColor = Color.Transparent
            ) else TextFieldDefaults.colors()
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        Text(text = languageManager.getString("language"), style = MaterialTheme.typography.labelLarge)

        Box {
            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                Text(selectedLanguage.uppercase())
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, modifier = Modifier.fillMaxWidth()) {
                languages.forEach { lang ->
                    DropdownMenuItem(
                        text = { Text(lang.uppercase()) },
                        onClick = {
                            selectedLanguage = lang
                            languageManager.loadLanguage(lang)
                            appStateManager.setAppLanguage(lang)
                            expanded = false
                        }
                    )
                }
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        // Voice Language (used by STT, LLM interpretation, TTS)
        Text(text = languageManager.getString("voice_language"), style = MaterialTheme.typography.labelLarge)
        Text(
            text = languageManager.getString("voice_language_desc") ?: "Language used for voice recognition, AI interpretation, and text-to-speech",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        val voiceLanguages = Strings.VoiceLanguages.ALL
        var voiceLangExpanded by remember { mutableStateOf(false) }
        Box {
            OutlinedButton(onClick = { voiceLangExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                Text(uiState.voiceLanguage.uppercase())
            }
            DropdownMenu(expanded = voiceLangExpanded, onDismissRequest = { voiceLangExpanded = false }, modifier = Modifier.fillMaxWidth()) {
                voiceLanguages.forEach { lang ->
                    DropdownMenuItem(
                        text = { Text(lang.uppercase()) },
                        onClick = {
                            appStateManager.setVoiceLanguage(lang)
                            appStateManager.setModelFilterLang(lang)
                            voiceLangExpanded = false
                        }
                    )
                }
            }
        }
    }
}
