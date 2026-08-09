package com.voxapps.commander.ui.screens.settings

import com.voxapps.design.picklist.Picklist
import com.voxapps.design.picklist.PicklistCompactAnchor
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
import com.voxapps.commander.data.remote.EngineRuntime
import com.voxapps.commander.data.remote.RemoteModelRegistry
import com.voxapps.commander.utils.Strings
import com.voxapps.commander.domain.localization.LanguageManager
import com.voxapps.commander.domain.model.AppModel
import com.voxapps.commander.state.AppStateManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelsSettingsTab(

    settingsRepo: SettingsRepository,
    appStateManager: AppStateManager,
    onProcessorSelected: (String) -> Unit,
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
    refreshTrigger: Int = 0
) {
        val languageManager = LocalLanguageManager.current
    val uiState by appStateManager.uiState.collectAsStateWithLifecycle()
    val nluModels = remember(uiState.availableModels) { uiState.availableModels["nlu_llm"] ?: emptyList() }

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
        Spacer(modifier = Modifier.height(8.dp))

        // --- OFFLINE FALLBACK SECTION ---
        Text(text = languageManager.getString("offline_fallback_section"), style = MaterialTheme.typography.titleMedium)

        val timeoutOptions = listOf(
            5 to "5 s", 10 to "10 s", 20 to "20 s", 35 to "35 s", 50 to "50 s",
            60 to "1 min", 300 to "5 min", 600 to "10 min"
        )
    
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = languageManager.getString("timeout_label"), style = MaterialTheme.typography.labelLarge)

            Picklist(
                items = timeoutOptions,
                // A stored value the option list does not offer still has to name itself — it can
                // come from a backup written when the list was different.
                selected = timeoutOptions.find { it.first == offlineFallbackTimeout }
                    ?: (offlineFallbackTimeout to "$offlineFallbackTimeout s"),
                itemLabel = { it.second },
                onSelect = { (seconds, _) ->
                    offlineFallbackTimeout = seconds
                    appStateManager.setOfflineFallbackTimeout(seconds)
                },
                // Sits at the end of its own labelled row, so it takes the inline anchor and a menu
                // that drops from the button rather than spanning the row.
                anchor = { label, onClick -> PicklistCompactAnchor(label, onClick) },
                menuFillsWidth = false
            )
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
