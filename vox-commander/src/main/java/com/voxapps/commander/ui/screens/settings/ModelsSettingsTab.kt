package com.voxapps.commander.ui.screens.settings

import com.voxapps.onboarding.VoxHintKeys
import com.voxapps.onboarding.VoxHintDialog
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
    onImportIntentModel: () -> Unit = {},
    refreshTrigger: Int = 0
) {
        val languageManager = LocalLanguageManager.current
        VoxHintDialog(
            store = appStateManager.hintStoreForUi,
            hintKey = VoxHintKeys.MODELS,
            title = languageManager.getString("hint_models_title"),
            body = languageManager.getString("hint_models_body"),
            okLabel = languageManager.getString("hint_ok"),
            dontShowAgainLabel = languageManager.getString("hint_dont_show_again")
        )
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
                onImportIntentModel = onImportIntentModel,
                refreshTrigger = refreshTrigger
            )
        }
    }
}
