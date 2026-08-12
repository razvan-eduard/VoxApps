package com.voxapps.commander.ui.screens.settings

import com.voxapps.design.picklist.PicklistButtonAnchor
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
import com.voxapps.commander.domain.engine.CloudDeadline
import com.voxapps.commander.data.remote.RemoteModelRegistry
import com.voxapps.commander.domain.engine.EngineSpecs
import com.voxapps.commander.domain.model.ImportedModel
import com.voxapps.commander.domain.localization.LanguageManager
import com.voxapps.commander.domain.model.AppModel
import com.voxapps.commander.state.AppStateManager
import com.voxapps.services.ServiceProbe
import com.voxapps.design.picklist.ConnectionTestCard
import com.voxapps.design.picklist.Picklist
import com.voxapps.design.picklist.ServicePicklist
import com.voxapps.commander.ui.components.EngineModelSection
import com.voxapps.logging.Logger
import com.voxapps.commander.utils.Strings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceEnginesSubTab(

    settingsRepo: SettingsRepository,
    appStateManager: AppStateManager,
    onProcessorSelected: (String) -> Unit,
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
    // Build list of processors: JSON engines (type=voice) + Local/Virtual injections
        val processors = remember(uiState.availableModels, uiState.isExperimentalVulkanEnabled, uiState.isWhisperSystemEnabled) {
            val list = RemoteModelRegistry.getEngineKeysByType("voice").toMutableList()

            // Filter out Whisper (.bin) engines if Whisper system is not enabled
            if (!uiState.isWhisperSystemEnabled) {
                list.removeAll { RemoteModelRegistry.getExtension(it) == ".bin" }
            }
            
            // The cloud and OS-supplied engines are no longer injected here — they are declared in
            // virtual_models.json and arrive with every other voice engine. Two screens listing
            // engines by hand is how one of them came to offer an engine the other did not.
            //
            // Experimental Vulkan stays: it is not an engine of its own but stt_whisper asked to run
            // on the GPU, sharing that engine's models, so declaring it would give it an empty model
            // list and a claim to be the whisper engine in every by-packaging lookup.
            if (uiState.isWhisperSystemEnabled && uiState.isExperimentalVulkanEnabled && !list.contains(Strings.Processors.WHISPER_VULKAN)) {
                list.add(0, Strings.Processors.WHISPER_VULKAN)
            }
            list
        }

        val engineEntries = remember(processors) { processors.map { RemoteModelRegistry.serviceEntry(it) } }

        ServicePicklist(
            items = engineEntries,
            selected = engineEntries.firstOrNull { it.id == uiState.voiceProcessor },
            itemLabel = { RemoteModelRegistry.getEngineLabel(it.id, languageManager) },
            onSelect = { onProcessorSelected(it.id) },
            credentialFor = { uiState.credentials.forEngine(it.credentialOwnerId) },
            onCredentialCommit = { entry, key ->
                appStateManager.setEngineApiKey(entry.credentialOwnerId, key)
            },
            credentialLabel = languageManager.getString("engine_api_key"),
            helpTextFor = { entry ->
                entry.helpTextKey?.let { languageManager.getString(it) }
                    ?.takeIf { it.isNotBlank() && it != entry.helpTextKey }
            },
            timeoutSecondsFor = { CloudDeadline.secondsFor(it.id, settingsRepo) },
            itemEnabled = { entry ->
                val proc = entry.id
                /*
                 * Only what this device cannot do disables a row.
                 *
                 * A missing key does not: the field for it appears under the selection, so an
                 * engine greyed out for wanting a credential is an engine whose credential can
                 * never be entered. It was worse than that here — the guard consulted the *intent*
                 * engine's OpenAI key, a leftover from when every service shared one — so the
                 * transcription engine unlocked when a key for something else was entered.
                 */
                when {
                    // Schema-driven gates, same predicates the engines' own availability probes
                    // use: `runtime: "cloud"` answers to the cloud toggle, the "google_service"
                    // capability to the Google-services toggle.
                    RemoteModelRegistry.runtimeOf(proc) == com.voxapps.commander.data.remote.EngineRuntime.CLOUD &&
                        !uiState.cloudIntelligenceEnabled -> false
                    RemoteModelRegistry.hasCapability(proc, "google_service") &&
                        !uiState.googleServicesEnabled -> false
                    proc == Strings.Processors.GOOGLE -> googleSttAvailable
                    proc == Strings.Processors.WHISPER_VULKAN ->
                        uiState.isWhisperSystemEnabled && !settingsRepo.getSettingsSnapshot().vulkanIncompatible
                    else -> true
                }
            },
            disabledSuffix = languageManager.getString("engine_cloud_disabled_suffix"),
            itemNote = { entry ->
                if (entry.requiresCredential && !uiState.credentials.has(entry.id))
                    " — needs an API key" else ""
            }
        )

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
        Text(text = languageManager.getString("model_language_filter") ?: "Model Language Filter", style = MaterialTheme.typography.labelLarge)

        // A plain choice, drawn plainly. It used the model picker — the one with per-row download
        // arrows, on-device badges and a bottom sheet — with every row told it was already
        // downloaded and no download callback given, because a language is not a file.
        Picklist(
            items = availableLanguages,
            selected = modelFilterLang.takeIf { it in availableLanguages },
            itemLabel = { it.uppercase() },
            onSelect = { lang ->
                modelFilterLang = lang
                appStateManager.setModelFilterLang(lang)
            }
        )

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
        
        Picklist(
            items = apiModels,
            selected = selectedApiModel,
            itemLabel = { it },
            onSelect = { selectedApiModel = it },
            anchor = { label, onClick ->
                PicklistButtonAnchor(label, onClick, enabled = isSelectionEnabled)
            }
        )
        HorizontalDivider()
    }

    // 4. Engine Specific Sections
    // Two different questions that used to be one. Which language slot an import belongs to is
    // about the engine's models; which picker to open is about how its model is packaged.
    val isPerLanguage = RemoteModelRegistry.isPerLanguage(engineKey)
    val isDirectoryBased = RemoteModelRegistry.isArchiveEngine(engineKey) ||
        RemoteModelRegistry.getExtension(engineKey).isBlank()

    // Agnostic model filtering by language
    // Keyed on the imported path as well: an import lands while this screen is composed, and
    // nothing else in these keys changes when it does — the row appeared only after leaving the
    // tab and coming back.
    val filteredModels = remember(
        models, modelFilterLang, isCurrentProcessorMultilingual, refreshTrigger, uiState.customVoiceModelPath
    ) {
        val declared = if (isCurrentProcessorMultilingual) models
            else models.filter { it.langCode == modelFilterLang }
        // What the user imported, listed first and treated like any other model: it is chosen from
        // here, and removed by the same trash icon. It used to live in a card of its own below,
        // outranking whatever this list showed as selected.
        val imported = EngineSpecs.importedRows(
            settingsRepo,
            engineKey,
            modelFilterLang.takeIf { isPerLanguage }
        )
        imported + declared
    }

    // --- CUSTOM MODEL IMPORT ---
    val supportsCustomModel = RemoteModelRegistry.supportsCustomImport(engineKey)
    // Already resolved for this engine and language by AppState — the screen used to choose
    // between two engine-named fields by asking how the engine is packaged.
    val customModelPath = uiState.customVoiceModelPath
    val hasCustomModel = !customModelPath.isNullOrBlank() && java.io.File(customModelPath).exists()

    if (supportsCustomModel) {
        // No "custom model active" card any more: an import is a row in the list above, marked as
        // imported, selected like any other model and deleted by the same trash icon. The card said
        // the same thing in a second place and was the only way to remove one.
        OutlinedButton(
            onClick = { onImportCustomModel(if (isPerLanguage) modelFilterLang else null) },
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
            header = languageManager.getString("available_models_header"),
                items = filteredModels,
            selectedItem = remember(uiState.activeVoiceModelId, filteredModels) {
                filteredModels.find { it.id == uiState.activeVoiceModelId }
            },
            itemLabel = { model ->
                // An import is named for what it is and which language slot it fills — the two
                // facts that decide how it behaves. Its file name is one we invented when we copied
                // it in ("wake_vosk_custom_de"), and the name the user picked is not kept anywhere.
                // Marked, too: a downloaded model can be fetched again, this one cannot.
                if (model is ImportedModel) {
                    // Named by its own file: with several imports side by side, only the filename
                    // tells them apart. The generic string survives only for a legacy slugless
                    // entry whose file kept the invented name from the single-slot era.
                    val name = model.label.takeUnless { it.startsWith("${model.engineType}_custom") }
                        ?: (languageManager.getString("model_imported_name") +
                            (model.langCode?.let { " (${it.uppercase()})" } ?: ""))
                    "$name${languageManager.getString("model_imported_suffix")} (${model.sizeDescription})"
                } else {
                    "${model.label} (${model.sizeDescription})"
                }
            },
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
