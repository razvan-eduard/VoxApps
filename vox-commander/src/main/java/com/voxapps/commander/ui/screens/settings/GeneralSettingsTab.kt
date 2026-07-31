package com.voxapps.commander.ui.screens.settings

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
    var expanded by remember { mutableStateOf(false) }
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
        Text(text = languageManager.getString("app_settings_section"), style = MaterialTheme.typography.titleMedium)

        // Repository Base URL with gray-out logic
        var isRepoFocused by remember { mutableStateOf(false) }
        TextField(
            value = modelRepoUrl,
            onValueChange = {
                modelRepoUrl = it
                scope.launch { settingsRepo.setModelRepoBaseUrl(it) }
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
                        com.voxapps.commander.domain.search.SearchProviderRegistry.fetchRemote(settingsRepo, force = true)
                        // fetchRemote() rebuilds every DynamicSearchProvider instance from scratch,
                        // so any previously-applied key is gone until these are called again — see
                        // SearchProviderRegistry.applySharedOpenAiKey's own doc comment. Missing here
                        // meant the OpenAI general/knowledge provider stayed locked (hasApiKey()
                        // false) after a manual sync even with a real key configured, until the next
                        // full app restart re-ran VoxApplication's own copy of this same sequence.
                        com.voxapps.commander.domain.search.SearchProviderRegistry.applyApiKeys(
                            settingsRepo.getAllSearchProviderApiKeys()
                        )
                        com.voxapps.commander.domain.search.SearchProviderRegistry.applySharedOpenAiKey(
                            settingsRepo.getApiKeySync()
                        )
                        com.voxapps.commander.domain.intent.registry.IntentCatalog.fetchRemote(settingsRepo, force = true)
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
        val autoDetect = uiState.voiceLanguageAutoDetect
        Column {
            OutlinedButton(
                onClick = { voiceLangExpanded = true },
                modifier = Modifier.fillMaxWidth()
            ) {
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
}
