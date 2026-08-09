package com.voxapps.commander.ui.screens.settings

import com.voxapps.commander.ui.LocalLanguageManager

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.voxapps.commander.data.preferences.SettingsRepository
import com.voxapps.commander.data.remote.EngineRuntime
import com.voxapps.commander.data.remote.RemoteModelRegistry
import com.voxapps.commander.domain.intent.interpreter.LlmModelInfo
import com.voxapps.commander.domain.localization.LanguageManager
import com.voxapps.commander.domain.model.AppModel
import com.voxapps.commander.state.AppStateManager
import com.voxapps.commander.ui.components.DropdownGroup
import com.voxapps.services.ServiceProbe
import com.voxapps.commander.ui.components.ConnectionTestCard
import com.voxapps.design.SettingsPicklist
import com.voxapps.commander.ui.components.EngineApiKeyField
import com.voxapps.commander.ui.components.EngineModelSection
import com.voxapps.commander.utils.Strings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IntentEnginesSubTab(

    settingsRepo: SettingsRepository,
    appStateManager: AppStateManager,
    onDownloadModel: (String, String, String?) -> Unit,
    onDeleteModel: (String, String) -> Unit,
    downloadProgress: Float?,
    downloadingItem: AppModel?,
    onCancelDownload: () -> Unit,
    onFallbackChanged: () -> Unit = {},
    refreshTrigger: Int = 0
) {
        val languageManager = LocalLanguageManager.current
    val uiState by appStateManager.uiState.collectAsStateWithLifecycle()

    // Download guard state
    var showMeteredWarning by remember { mutableStateOf(false) }
    var showWifiOnlyBlocked by remember { mutableStateOf(false) }
    var pendingDownloadSize by remember { mutableStateOf("") }
    var pendingDownloadAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    
    // 1. Engine key IS the processor — same value from models.json
    val engineKey = uiState.aiProcessor

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        // --- MASTER TOGGLE ---
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = languageManager.getString("cloud_intelligence_title"), 
                            style = MaterialTheme.typography.bodyLarge, 
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = languageManager.getString("cloud_intelligence_desc"), 
                            style = MaterialTheme.typography.bodySmall, 
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = uiState.cloudIntelligenceEnabled,
                        onCheckedChange = { appStateManager.setCloudIntelligenceEnabled(it) }
                    )
                }

                if (uiState.cloudIntelligenceEnabled) {
                    Spacer(modifier = Modifier.height(16.dp))
                    HorizontalDivider(modifier = Modifier.alpha(0.5f))
                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = languageManager.getString("ai_processor_label"),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    
                    Spacer(modifier = Modifier.height(8.dp))

                    // Every engine that interprets, on-device or not. The cloud services used to be
                    // appended here by hand — three lines that had to be kept in step with a label
                    // table and an availability check elsewhere; they are declared engines now.
                    val aiOptions = remember(uiState.availableModels) {
                        RemoteModelRegistry.getEngineKeysByType("llm")
                    }

                    SettingsPicklist(
                        items = aiOptions,
                        selected = uiState.aiProcessor,
                        itemLabel = { RemoteModelRegistry.getEngineLabel(it, languageManager) },
                        onSelect = { appStateManager.setAiProcessor(it) },
                        disabledSuffix = " (Incompatible)",
                        itemEnabled = { id ->
                            // Only what this device cannot run is disabled — whether it carries
                            // Gemini Nano is a probe result no declaration can supply. A missing
                            // credential is not a reason to disable: the field that fixes it sits
                            // under the selection, so greying the engine out made its own key
                            // unreachable.
                            id != Strings.AiProcessors.GEMINI_NATIVE ||
                                !settingsRepo.getSettingsSnapshot().geminiIncompatible
                        },
                        itemNote = { id ->
                            // Credentials come from uiState rather than a snapshot read: this is
                            // composition, so a value fetched here would be fixed until something
                            // else recomposed the menu.
                            if (RemoteModelRegistry.hasCapability(id, "requires_api_key") &&
                                !uiState.credentials.has(id)
                            ) " — needs an API key" else ""
                        }
                    ) {
                        ConnectionTestCard(
                            spec = RemoteModelRegistry.probeSpecFor(
                                uiState.aiProcessor,
                                uiState.credentials.forEngine(uiState.aiProcessor)
                            ),
                            // The "where to get a key" link belongs to the credential field,
                            // which also shows for engines with no endpoint to probe. Drawing it
                            // here too put it on screen twice.
                            settingsRepo = settingsRepo
                        )
                    }


                    // The credential for whichever engine is selected, and only when that engine
                    // needs one — asked where the choice is made rather than on another page.
                    EngineApiKeyField(
                        engineKey = uiState.aiProcessor,
                        appStateManager = appStateManager,
                        languageManager = languageManager
                    )
                }
            }
        }

        // --- NLU MODEL SELECTION ---
        // Only show if the current engine is a JSON-defined LLM with actual models
        val nluModels = uiState.availableModels[engineKey] ?: emptyList()
        
        if (uiState.cloudIntelligenceEnabled && nluModels.isNotEmpty()) {
            val selectedModel = remember(uiState.activeIntentModelId, nluModels) {
                nluModels.find { it.id == uiState.activeIntentModelId }
            }

            val nluGroups = remember(nluModels) {
                listOf(DropdownGroup(languageManager.getString("available_models_header"), nluModels))
            }

            EngineModelSection(
                title = languageManager.getString("nlu_model_selection_title"),
                // Only a downloadable engine can be an offline fallback. Left at its default, the
                // checkbox would also appear for a cloud engine — whose models report isBuiltIn,
                // which this section reads as "already downloaded" and therefore selectable.
                showFallback = RemoteModelRegistry.runtimeOf(engineKey) == EngineRuntime.LOCAL_FILE,

                settingsRepo = settingsRepo,
                appStateManager = appStateManager,
                groups = remember(nluGroups, uiState, refreshTrigger) { nluGroups },
                selectedItem = selectedModel,
                itemLabel = { "${it.label} (${it.sizeDescription})" },
                modelIdProvider = { it.id },
                onItemSelected = { model, isDownloaded ->
                    appStateManager.setActiveIntentModelId(model.id)
                    appStateManager.saveIntentModelSelection(engineKey, model.id)
                },
                onDownloadRequest = { model ->
                    val downloadAction = {
                        appStateManager.setActiveIntentModelId(model.id)
                        appStateManager.saveIntentModelSelection(engineKey, model.id)
                        onDownloadModel(model.id, engineKey, null)
                    }
                    val isMetered = com.voxapps.commander.utils.NetworkMonitor.isMetered
                    when {
                        uiState.downloadPreference == "wifi_only" && isMetered -> {
                            pendingDownloadSize = model.sizeDescription
                            showWifiOnlyBlocked = true
                        }
                        isMetered -> {
                            pendingDownloadSize = model.sizeDescription
                            pendingDownloadAction = downloadAction
                            showMeteredWarning = true
                        }
                        else -> downloadAction()
                    }
                },
                onDeleteRequest = { model -> onDeleteModel(model.id, engineKey) },
                onCancelDownload = onCancelDownload,
                downloadProgress = downloadProgress,
                downloadingItem = downloadingItem,
                currentProcessor = uiState.aiProcessor,
                fallbackCategory = Strings.FallbackCategories.INTENT,
                onFallbackChanged = onFallbackChanged,
                refreshTrigger = refreshTrigger
            )
            
            if (selectedModel != null) {
                Text(
                    text = languageManager.getString("engine_type_label").format(RemoteModelRegistry.getEngineLabel(engineKey, languageManager)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }
        }
    }

    // --- DOWNLOAD GUARD DIALOGS ---
    if (showMeteredWarning) {
        AlertDialog(
            onDismissRequest = { showMeteredWarning = false },
            title = { Text(languageManager.getString("metered_warning_title")) },
            text = { Text(languageManager.getString("metered_warning_msg").format(pendingDownloadSize)) },
            confirmButton = {
                TextButton(onClick = {
                    showMeteredWarning = false
                    pendingDownloadAction?.invoke()
                    pendingDownloadAction = null
                }) {
                    Text(languageManager.getString("metered_warning_continue"))
                }
            },
            dismissButton = {
                TextButton(onClick = { showMeteredWarning = false; pendingDownloadAction = null }) {
                    Text(languageManager.getString("cancel_button"))
                }
            }
        )
    }
    if (showWifiOnlyBlocked) {
        AlertDialog(
            onDismissRequest = { showWifiOnlyBlocked = false },
            title = { Text(languageManager.getString("wifi_only_blocked_title")) },
            text = { Text(languageManager.getString("wifi_only_blocked_msg").format(pendingDownloadSize)) },
            confirmButton = {
                TextButton(onClick = { showWifiOnlyBlocked = false }) {
                    Text(languageManager.getString("ok_button"))
                }
            }
        )
    }
}
