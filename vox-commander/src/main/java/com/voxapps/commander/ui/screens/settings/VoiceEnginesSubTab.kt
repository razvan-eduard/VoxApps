package com.voxapps.commander.ui.screens.settings

import com.voxapps.commander.ui.LocalLanguageManager

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.voxapps.commander.data.preferences.SettingsRepository
import com.voxapps.commander.data.remote.EngineRuntime
import com.voxapps.commander.data.remote.RemoteModelItem
import com.voxapps.commander.data.remote.RemoteModelRegistry
import com.voxapps.commander.domain.localization.LanguageManager
import com.voxapps.commander.domain.model.AppModel
import com.voxapps.commander.state.AppStateManager
import com.voxapps.commander.ui.components.DropdownGroup
import com.voxapps.commander.ui.components.EngineModelSection
import com.voxapps.commander.ui.components.GroupedDropdownContent
import com.voxapps.commander.ui.components.GroupedDropdownMenu
import com.voxapps.logging.Logger
import com.voxapps.commander.utils.Strings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceEnginesSubTab(

    settingsRepo: SettingsRepository,
    appStateManager: AppStateManager,
    onProcessorSelected: (String) -> Unit,
    hasApiKey: Boolean,
    googleSttAvailable: Boolean,
    onVoiceLanguageSelected: (String) -> Unit,
    onModelSelected: (AppModel, Boolean, String) -> Unit,
    onDownloadModel: (String, String, String?) -> Unit,
    downloadProgress: Float?,
    downloadingItem: AppModel? = null,
    downloadedColor: Color,
    onCancelDownload: () -> Unit,
    onDeleteRequest: (AppModel) -> Unit,
    onFallbackChanged: () -> Unit = {},
    onImportCustomModel: (String?) -> Unit = {},
    onClearCustomModel: () -> Unit = {},
    refreshTrigger: Int = 0
) {
        val languageManager = LocalLanguageManager.current
    // REALTIME STATE from AppStateManager
    val uiState by appStateManager.uiState.collectAsStateWithLifecycle()

    // Download guard state
    var showMeteredWarning by remember { mutableStateOf(false) }
    var showWifiOnlyBlocked by remember { mutableStateOf(false) }
    var pendingDownloadSize by remember { mutableStateOf("") }
    var pendingDownloadAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    // 1. Engine key IS the processor — same value from models.json
    val engineKey = uiState.voiceProcessor
    
    val isCurrentProcessorMultilingual = RemoteModelRegistry.isMultilingual(engineKey)
    val availableLanguages = RemoteModelRegistry.getLanguages(engineKey)

    Logger.log("VoiceEnginesSubTab: engineKey=$engineKey, isMultilingual=$isCurrentProcessorMultilingual, availLangs=${availableLanguages.size}", "VoiceEnginesSubTab")
    Logger.log("VoiceEnginesSubTab: availableModels keys=${uiState.availableModels.keys}", "VoiceEnginesSubTab")

    // 1. Processor Selection
    Text(text = languageManager.getString("voice_processor_section"), style = MaterialTheme.typography.titleMedium)
    Box {
        var processorExpanded by remember { mutableStateOf(false) }
        
        // Build list of processors: JSON engines (type=voice) + Local/Virtual injections
        val processors = remember(uiState.availableModels, uiState.isExperimentalVulkanEnabled, uiState.isWhisperSystemEnabled) {
            val list = RemoteModelRegistry.getEngineKeysByType("voice").toMutableList()

            // Filter out Whisper (.bin) engines if Whisper system is not enabled
            if (!uiState.isWhisperSystemEnabled) {
                list.removeAll { RemoteModelRegistry.getExtension(it) == ".bin" }
            }
            
            // Add virtual models
            if (!list.contains(Strings.Processors.GOOGLE)) list.add(Strings.Processors.GOOGLE)
            if (!list.contains(Strings.Processors.WHISPER_API)) list.add(Strings.Processors.WHISPER_API)
            
            // Experimental Vulkan (only if Whisper system is enabled)
            if (uiState.isWhisperSystemEnabled && uiState.isExperimentalVulkanEnabled && !list.contains(Strings.Processors.WHISPER_VULKAN)) {
                list.add(0, Strings.Processors.WHISPER_VULKAN)
            }
            list
        }

        OutlinedButton(onClick = { processorExpanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text(RemoteModelRegistry.getEngineLabel(uiState.voiceProcessor, languageManager))
        }
        
        DropdownMenu(expanded = processorExpanded, onDismissRequest = { processorExpanded = false }, modifier = Modifier.fillMaxWidth()) {
            processors.forEach { proc ->
                val enabled = when (proc) {
                    Strings.Processors.WHISPER_API -> hasApiKey
                    Strings.Processors.GOOGLE -> googleSttAvailable
                    Strings.Processors.WHISPER_VULKAN -> uiState.isWhisperSystemEnabled && !settingsRepo.getSettingsSnapshot().vulkanIncompatible
                    else -> true
                }
                
                DropdownMenuItem(
                    text = { 
                        Text(
                            text = RemoteModelRegistry.getEngineLabel(proc, languageManager), 
                            color = if (enabled) LocalContentColor.current else Color.Gray
                        ) 
                    },
                    onClick = { if (enabled) { onProcessorSelected(proc); processorExpanded = false } },
                    enabled = enabled
                )
            }
        }
    }

    HorizontalDivider()

    // 2. Model Language Filter (only for non-multilingual engines — filters model list by language)
    val models = uiState.availableModels[engineKey] ?: emptyList()

    var modelFilterLang by remember(engineKey, availableLanguages, uiState.modelFilterLang) {
        mutableStateOf(
            if (!isCurrentProcessorMultilingual && availableLanguages.isNotEmpty()) {
                if (uiState.modelFilterLang in availableLanguages) uiState.modelFilterLang
                else if ("en" in availableLanguages) "en"
                else availableLanguages.first()
            } else uiState.modelFilterLang
        )
    }

    if (!isCurrentProcessorMultilingual && availableLanguages.isNotEmpty()) {
        val languages = availableLanguages.map { lang ->
            lang to lang.uppercase()
        }

        var showLanguageSheet by remember { mutableStateOf(false) }
        val languageSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

        val languageGroups = listOf(DropdownGroup(languageManager.getString("available_languages_header") ?: "AVAILABLE LANGUAGES", languages))
        val selectedLangPair = languages.find { it.first == modelFilterLang }

        Text(text = languageManager.getString("model_language_filter") ?: "Model Language Filter", style = MaterialTheme.typography.labelLarge)

        GroupedDropdownMenu(
            selectedItem = selectedLangPair,
            groups = languageGroups,
            itemLabel = { it.second },
            isDownloaded = { true },
            onDeviceLabel = "",
            onItemSelected = { pair, _ -> modelFilterLang = pair.first; appStateManager.setModelFilterLang(pair.first) },
            onExpandedChange = { showSheet -> showLanguageSheet = showSheet }

        )

        if (showLanguageSheet) {
            ModalBottomSheet(onDismissRequest = { showLanguageSheet = false }, sheetState = languageSheetState) {
                GroupedDropdownContent(
                    title = languageManager.getString("model_language_filter") ?: "Model Language Filter",
                    groups = languageGroups,
                    itemLabel = { it.second },
                    isDownloaded = { true },
                    onDeviceLabel = "",
                    onItemSelected = { pair, _ -> modelFilterLang = pair.first; appStateManager.setModelFilterLang(pair.first); showLanguageSheet = false }

                )
            }
        }

        HorizontalDivider()
    }

    // 3. API Model Selection (OpenAI Whisper)
    if (uiState.voiceProcessor == Strings.Processors.WHISPER_API) {
        val apiModels = listOf("whisper-1")
        var selectedApiModel by remember { mutableStateOf(apiModels.first()) }
        val isSelectionEnabled = apiModels.size > 1
        
        Text(
            text = "OpenAI Whisper Model", 
            style = MaterialTheme.typography.labelLarge,
            color = if (isSelectionEnabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        )
        
        var expanded by remember { mutableStateOf(false) }
        Box {
            OutlinedButton(
                onClick = { if (isSelectionEnabled) expanded = true }, 
                modifier = Modifier.fillMaxWidth(),
                enabled = isSelectionEnabled
            ) {
                Text(text = selectedApiModel)
            }
            
            DropdownMenu(
                expanded = expanded, 
                onDismissRequest = { expanded = false },
                modifier = Modifier.fillMaxWidth(0.9f)
            ) {
                apiModels.forEach { model ->
                    DropdownMenuItem(
                        text = { Text(model, modifier = Modifier.fillMaxWidth()) },
                        onClick = {
                            selectedApiModel = model
                            expanded = false
                        }
                    )
                }
            }
        }
        HorizontalDivider()
    }

    // 4. Engine Specific Sections
    // Agnostic model filtering by language
    val filteredModels = remember(models, modelFilterLang, isCurrentProcessorMultilingual) {
        if (isCurrentProcessorMultilingual) models 
        else models.filter { it.langCode == modelFilterLang }
    }

    // --- CUSTOM MODEL IMPORT ---
    val isZipEngine = RemoteModelRegistry.isZipEngine(engineKey)
    val supportsCustomModel = RemoteModelRegistry.getExtension(engineKey).isNotEmpty()
    val customModelPath = if (isZipEngine) {
        uiState.customVoskModelPaths[modelFilterLang]
    } else {
        uiState.customWhisperModelPath
    }
    val hasCustomModel = !customModelPath.isNullOrBlank() && java.io.File(customModelPath).exists()

    if (supportsCustomModel) {
        if (hasCustomModel) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f))
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = languageManager.getString("custom_model_active"),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = customModelPath,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onClearCustomModel,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(languageManager.getString("clear_custom_model"))
                }
            }
        }
    } else {
        OutlinedButton(
            onClick = { onImportCustomModel(if (isZipEngine) modelFilterLang else null) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(languageManager.getString("import_custom_model"))
        }
        Text(
            text = languageManager.getString("import_custom_model_desc"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    } // end if (supportsCustomModel)

    Spacer(modifier = Modifier.height(12.dp))

    if (filteredModels.isNotEmpty()) {
        EngineModelSection(
            title = languageManager.getString("select_model"),
            // Only a downloadable engine can be an offline fallback. Left at its default, the
            // checkbox would also appear for a cloud engine — whose models report isBuiltIn, which
            // this section reads as "already downloaded" and therefore selectable.
            showFallback = RemoteModelRegistry.runtimeOf(engineKey) == EngineRuntime.LOCAL_FILE,

            settingsRepo = settingsRepo,
            appStateManager = appStateManager,
            groups = remember(filteredModels, refreshTrigger) {
                listOf(DropdownGroup(languageManager.getString("available_models_header"), filteredModels))
            },
            selectedItem = remember(uiState.activeVoiceModelId, filteredModels) {
                filteredModels.find { it.id == uiState.activeVoiceModelId }
            },
            itemLabel = { "${it.label} (${it.sizeDescription})" },
            modelIdProvider = { it.id },
            onItemSelected = { model, isDownloaded ->
                val code = model.langCode ?: uiState.modelFilterLang
                onModelSelected(model, isDownloaded, code)
            },
            onDownloadRequest = { model ->
                val code = model.langCode ?: uiState.modelFilterLang
                val downloadAction = {
                    appStateManager.saveVoiceModelSelection(engineKey, model.id)
                    onDownloadModel(model.id, engineKey, code)
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
            onDeleteRequest = { model -> onDeleteRequest(model) },
            onCancelDownload = onCancelDownload,
            downloadProgress = downloadProgress,
            downloadingItem = downloadingItem,
            currentProcessor = uiState.voiceProcessor,
            fallbackCategory = Strings.FallbackCategories.VOICE,
            onFallbackChanged = onFallbackChanged,
            refreshTrigger = refreshTrigger
        )
    }

    // --- MICROPHONE SENSITIVITY ---
    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
    Text(
        text = languageManager.getString("stt_sensitivity_label") ?: "Microphone Sensitivity",
        style = MaterialTheme.typography.labelLarge
    )
    Text(
        text = languageManager.getString("stt_sensitivity_desc") ?: "Adjust how sensitive the microphone is when listening for commands",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        val sttLevels = listOf("low", "medium", "high")
        val sttLabels = listOf(
            languageManager.getString("stt_sensitivity_low") ?: "Low",
            languageManager.getString("stt_sensitivity_medium") ?: "Medium",
            languageManager.getString("stt_sensitivity_high") ?: "High"
        )
        sttLevels.forEachIndexed { idx, level ->
            FilterChip(
                selected = uiState.sttSensitivity == level,
                onClick = { appStateManager.setSttSensitivity(level) },
                label = { Text(sttLabels[idx]) }
            )
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
