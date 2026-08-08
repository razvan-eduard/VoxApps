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
import com.voxapps.services.SchemaCatalog
import com.voxapps.services.RemoteSchema

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

        Text(
            text = languageManager.getString("schema_updates_section"),
            style = MaterialTheme.typography.titleSmall
        )
        Text(
            text = languageManager.getString("schema_updates_desc"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // Off means the app behaves identically every launch: whatever is in force stays in force
        // until the button below is pressed. On means the repository is asked at startup, and only a
        // file whose bytes actually changed is adopted.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = languageManager.getString("schema_auto_update_label"),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
            Switch(
                checked = uiState.schemaAutoUpdate,
                onCheckedChange = { appStateManager.setSchemaAutoUpdate(it) }
            )
        }

        // Repository Base URL with gray-out logic
        var isRepoFocused by remember { mutableStateOf(false) }
        var syncing by remember { mutableStateOf(false) }
        var syncReport by remember { mutableStateOf<String?>(null) }
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
                IconButton(
                    enabled = !syncing,
                    onClick = {
                        syncing = true
                        syncReport = null
                        scope.launch {
                            // Every schema the app loaded, whatever the toggle says: the catalog is
                            // the list, so this cannot drift out of step with what exists.
                            val results = SchemaCatalog.refreshAll(modelRepoUrl)
                            val updated = results.count { it.value is RemoteSchema.Refreshed.Updated }
                            val unreachable = results.count { it.value is RemoteSchema.Refreshed.Unreachable }
                            val rejected = results.count { it.value is RemoteSchema.Refreshed.Rejected }
                            if (updated > 0) appStateManager.refreshAll()
                            syncReport = String.format(
                                languageManager.getString("schema_sync_report"),
                                updated, results.size - updated - unreachable - rejected, unreachable + rejected
                            )
                            syncing = false
                        }
                    }
                ) {
                    if (syncing) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Default.Refresh, contentDescription = languageManager.getString("schema_sync_now"))
                }
            },
            colors = if (!isRepoFocused) TextFieldDefaults.colors(
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                unfocusedIndicatorColor = Color.Transparent
            ) else TextFieldDefaults.colors()
        )

        syncReport?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        // The files, then where they came from — one line rather than a source repeated per name.
        // Mixed only happens between a refresh and a reset, so it is worth saying plainly when it
        // does rather than making the user compare six lines.
        val provenance = SchemaCatalog.provenance()
        if (provenance.isNotEmpty()) {
            Text(
                text = provenance.joinToString(" · ") { it.fileName },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            val fromRepo = provenance.count { it.source == RemoteSchema.Source.ACCEPTED }
            Text(
                text = when (fromRepo) {
                    0 -> languageManager.getString("schema_source_bundled")
                    provenance.size -> languageManager.getString("schema_source_accepted")
                    else -> String.format(
                        languageManager.getString("schema_source_mixed"),
                        fromRepo, provenance.size - fromRepo
                    )
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

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
