package com.voxapps.commander.ui.screens.settings

import com.voxapps.commander.ui.LocalLanguageManager

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.voxapps.commander.data.preferences.SettingsRepository
import com.voxapps.commander.data.remote.RemoteModelRegistry
import com.voxapps.commander.domain.localization.LanguageManager
import com.voxapps.commander.domain.model.AppModel
import com.voxapps.commander.state.AppStateManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelsSettingsTab(

    settingsRepo: SettingsRepository,
    appStateManager: AppStateManager,
    onProcessorSelected: (String) -> Unit,
    hasApiKey: Boolean,
    googleSttAvailable: Boolean,
    onVoiceLanguageSelected: (String) -> Unit,
    onModelSelected: (AppModel, Boolean, String) -> Unit,
    onDownloadModel: (String, String, String?) -> Unit,
    onDeleteModel: (String, String) -> Unit,
    onDeleteRequest: (AppModel) -> Unit,
    onCancelDownload: () -> Unit,
    downloadProgress: Float?,
    downloadingItem: AppModel? = null,
    downloadedColor: Color,
    onFallbackChanged: () -> Unit = {},
    onImportCustomModel: (String?) -> Unit = {},
    onClearCustomModel: () -> Unit = {},
    refreshTrigger: Int = 0
) {
        val languageManager = LocalLanguageManager.current
    val uiState by appStateManager.uiState.collectAsStateWithLifecycle()
    val nluModels = remember(uiState.availableModels) { uiState.availableModels["nlu_llm"] ?: emptyList() }

    var apiKey by remember(uiState.apiKey) { mutableStateOf(uiState.apiKey ?: "") }
    var geminiApiKey by remember(uiState.geminiApiKey) { mutableStateOf(uiState.geminiApiKey ?: "") }
    var offlineFallbackTimeout by remember(uiState.refreshTrigger) { mutableIntStateOf(settingsRepo.getSettingsSnapshot().offlineFallbackTimeout) }

    var selectedSubTab by remember { mutableIntStateOf(0) }
    val focusManager = LocalFocusManager.current

    LaunchedEffect(selectedSubTab) {
        focusManager.clearFocus()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = { focusManager.clearFocus() }
            )
    ) {
        // --- API KEYS SECTION ---
        Text(text = languageManager.getString("api_keys_section"), style = MaterialTheme.typography.titleMedium)

        Card(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
            ),
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                var isApiFocused by remember { mutableStateOf(false) }
                TextField(
                    value = apiKey,
                    onValueChange = {
                        apiKey = it
                        appStateManager.setApiKey(it)
                    },
                    label = { Text(languageManager.getString("api_key")) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { isApiFocused = it.isFocused },
                    visualTransformation = if (isApiFocused) VisualTransformation.None else PasswordVisualTransformation(),
                    singleLine = !isApiFocused,
                    maxLines = if (isApiFocused) 5 else 1,
                    colors = if (!isApiFocused) TextFieldDefaults.colors(
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        unfocusedIndicatorColor = Color.Transparent
                    ) else TextFieldDefaults.colors()
                )

                Spacer(modifier = Modifier.height(8.dp))

                var isGeminiKeyFocused by remember { mutableStateOf(false) }
                TextField(
                    value = geminiApiKey,
                    onValueChange = {
                        geminiApiKey = it
                        appStateManager.setGeminiApiKey(it)
                    },
                    label = { Text(languageManager.getString("gemini_api_key")) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { isGeminiKeyFocused = it.isFocused },
                    visualTransformation = if (isGeminiKeyFocused) VisualTransformation.None else PasswordVisualTransformation(),
                    singleLine = !isGeminiKeyFocused,
                    maxLines = if (isGeminiKeyFocused) 5 else 1,
                    colors = if (!isGeminiKeyFocused) TextFieldDefaults.colors(
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        unfocusedIndicatorColor = Color.Transparent
                    ) else TextFieldDefaults.colors()
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // --- OFFLINE FALLBACK SECTION ---
        Text(text = languageManager.getString("offline_fallback_section"), style = MaterialTheme.typography.titleMedium)

        val timeoutOptions = listOf(
            5 to "5 s", 10 to "10 s", 20 to "20 s", 35 to "35 s", 50 to "50 s",
            60 to "1 min", 300 to "5 min", 600 to "10 min"
        )
        var timeoutExpanded by remember { mutableStateOf(false) }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = languageManager.getString("timeout_label"), style = MaterialTheme.typography.labelLarge)

            Box {
                OutlinedButton(
                    onClick = { timeoutExpanded = true },
                    modifier = Modifier.widthIn(min = 120.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    val currentLabel = timeoutOptions.find { it.first == offlineFallbackTimeout }?.second ?: "$offlineFallbackTimeout s"
                    Text(currentLabel)
                    Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                }
                DropdownMenu(
                    expanded = timeoutExpanded,
                    onDismissRequest = { timeoutExpanded = false }
                ) {
                    timeoutOptions.forEach { (seconds, label) ->
                        DropdownMenuItem(
                            text = { Text(label) },
                            onClick = {
                                offlineFallbackTimeout = seconds
                                appStateManager.setOfflineFallbackTimeout(seconds)
                                timeoutExpanded = false
                            }
                        )
                    }
                }
            }
        }

        if (uiState.defaultVoiceFallbackProcessor != null && uiState.defaultVoiceFallbackModel != null) {
            val allVoiceModels = RemoteModelRegistry.getEngineKeysByType("voice").flatMap { uiState.availableModels[it] ?: emptyList() }

            val voiceModelLabel = allVoiceModels.find { it.id == uiState.defaultVoiceFallbackModel }?.label ?: uiState.defaultVoiceFallbackModel
            Text(
                text = "Voice: ${uiState.defaultVoiceFallbackProcessor} ($voiceModelLabel)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary
            )
        }

        if (uiState.defaultIntentFallbackProcessor != null && uiState.defaultIntentFallbackModel != null) {
            val intentModelLabel = nluModels.find { it.id == uiState.defaultIntentFallbackModel }?.label ?: uiState.defaultIntentFallbackModel
            Text(
                text = "Intent: ${uiState.defaultIntentFallbackProcessor} ($intentModelLabel)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary
            )
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        // --- ENGINE SUB-TABS ---
        TabRow(
            selectedTabIndex = selectedSubTab,
            containerColor = Color.Transparent,
            divider = {},
            indicator = { tabPositions ->
                if (selectedSubTab < tabPositions.size) {
                    TabRowDefaults.SecondaryIndicator(
                        Modifier.tabIndicatorOffset(tabPositions[selectedSubTab])
                    )
                }
            }
        ) {
            Tab(
                selected = selectedSubTab == 0,
                onClick = { selectedSubTab = 0 },
                text = { Text(languageManager.getString("voice_engines"), style = MaterialTheme.typography.labelLarge) }
            )
            Tab(
                selected = selectedSubTab == 1,
                onClick = { selectedSubTab = 1 },
                text = { Text(languageManager.getString("intent_engines"), style = MaterialTheme.typography.labelLarge) }
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (selectedSubTab == 0) {
            VoiceEnginesSubTab(

                settingsRepo = settingsRepo,
                appStateManager = appStateManager,
                onProcessorSelected = onProcessorSelected,
                hasApiKey = hasApiKey,
                googleSttAvailable = googleSttAvailable,
                onVoiceLanguageSelected = onVoiceLanguageSelected,
                onModelSelected = onModelSelected,
                onDownloadModel = onDownloadModel,
                downloadProgress = downloadProgress,
                downloadingItem = downloadingItem,
                downloadedColor = downloadedColor,
                onCancelDownload = onCancelDownload,
                onDeleteRequest = onDeleteRequest,
                onFallbackChanged = onFallbackChanged,
                onImportCustomModel = onImportCustomModel,
                onClearCustomModel = onClearCustomModel,
                refreshTrigger = refreshTrigger
            )
        } else {
            IntentEnginesSubTab(

                settingsRepo = settingsRepo,
                appStateManager = appStateManager,
                onDownloadModel = onDownloadModel,
                onDeleteModel = onDeleteModel,
                downloadProgress = downloadProgress,
                downloadingItem = downloadingItem,
                onCancelDownload = onCancelDownload,
                onFallbackChanged = onFallbackChanged,
                refreshTrigger = refreshTrigger
            )
        }
    }
}
