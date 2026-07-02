package com.voxcommander.app.ui.screens.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.delay
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withAnnotation
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.voxcommander.app.data.preferences.SettingsRepository
import com.voxcommander.app.data.remote.RemoteModelRegistry
import com.voxcommander.app.domain.localization.LanguageManager
import com.voxcommander.app.domain.model.AppModel
import com.voxcommander.app.domain.voice.VoiceManager
import com.voxcommander.app.domain.voice.WakeWordCalibrator
import com.voxcommander.app.domain.voice.WakeWordProfile
import com.voxcommander.app.state.AppStateManager
import com.voxcommander.app.ui.components.DropdownGroup
import com.voxcommander.app.ui.components.GroupedDropdownContent
import com.voxcommander.app.ui.components.EngineModelSection
import com.voxcommander.app.ui.components.GroupedDropdownMenu
import com.voxcommander.app.ui.components.VoiceInputTextField
import com.voxcommander.app.ui.screens.main.ListeningScreen
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import com.voxcommander.app.utils.Strings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServiceSettingsTab(
    languageManager: LanguageManager,
    settingsRepo: SettingsRepository,
    appStateManager: AppStateManager,
    onStartService: () -> Unit,
    onStopService: () -> Unit,
    downloadedColor: Color,
    onDownloadRequest: (AppModel) -> Unit,
    onDeleteRequest: (AppModel) -> Unit,
    onCancelDownload: () -> Unit,
    downloadProgress: Float?,
    downloadingItem: Any? = null,
    onImportCustomModel: ((String?) -> Unit)? = null,
    refreshTrigger: Int = 0
) {
    val uiState by appStateManager.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showDeleteProfileDialog by remember { mutableStateOf(false) }

    val currentEngineKey = uiState.wakeWordEngineType ?: RemoteModelRegistry.getDefaultWakeWordEngineKey()

    // Engine capabilities (driven by models.json, not hardcoded)
    val supportsCalibration = RemoteModelRegistry.hasCapability(currentEngineKey, "calibration")
    val supportsModelDownload = RemoteModelRegistry.hasCapability(currentEngineKey, "model_download")
    val supportsBuiltinKeywords = RemoteModelRegistry.hasCapability(currentEngineKey, "builtin_keywords")
    val requiresApiKey = RemoteModelRegistry.hasCapability(currentEngineKey, "requires_api_key")
    val hasBuiltinModels = RemoteModelRegistry.hasCapability(currentEngineKey, "builtin_models")
    val supportsWakeWordText = RemoteModelRegistry.hasCapability(currentEngineKey, "wake_word_text")

    // Vosk language metadata (only used for language picker when Vosk is selected)
    val isVoskMultilingual = RemoteModelRegistry.isMultilingual("wake_vosk")
    val availableVoskLanguages = RemoteModelRegistry.getLanguages("wake_vosk")

    // Warning dialog state for engine switch while service running
    var pendingEngineSwitch by remember { mutableStateOf<String?>(null) }

    fun selectEngine(engineKey: String) {
        if (uiState.isWakeWordServiceListening && engineKey != currentEngineKey) {
            pendingEngineSwitch = engineKey
        } else {
            appStateManager.setWakeWordEngineType(engineKey)
        }
    }

    if (pendingEngineSwitch != null) {
        AlertDialog(
            onDismissRequest = { pendingEngineSwitch = null },
            title = { Text(languageManager.getString("ww_engine_switch_warning_title")) },
            text = { Text(languageManager.getString("ww_engine_switch_warning_msg")) },
            confirmButton = {
                TextButton(onClick = {
                    onStopService()
                    appStateManager.setWakeWordEngineType(pendingEngineSwitch!!)
                    pendingEngineSwitch = null
                }) { Text(languageManager.getString("ww_engine_switch_warning_confirm")) }
            },
            dismissButton = {
                TextButton(onClick = { pendingEngineSwitch = null }) {
                    Text(languageManager.getString("cancel_button"))
                }
            }
        )
    }

    Text(text = languageManager.getString("service_settings_section"), style = MaterialTheme.typography.titleMedium)

    // --- SUB-TABS: Wake Word + TTS ---
    var selectedSubTab by remember { mutableIntStateOf(0) }

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
            text = { Text(languageManager.getString("tab_wake_word") ?: "Wake Word", style = MaterialTheme.typography.labelLarge) }
        )
        Tab(
            selected = selectedSubTab == 1,
            onClick = { selectedSubTab = 1 },
            text = { Text(languageManager.getString("tab_tts") ?: "TTS", style = MaterialTheme.typography.labelLarge) }
        )
    }

    Spacer(modifier = Modifier.height(16.dp))

    if (selectedSubTab == 0) {
    // --- COMMON: Wake Word Enable Switch ---
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(languageManager.getString("wake_word_enabled"))
        Switch(
            checked = uiState.wakeWordEnabled,
            onCheckedChange = { enabled ->
                appStateManager.setWakeWordEnabled(enabled)
                if (!enabled && uiState.isWakeWordServiceListening) {
                    onStopService()
                }
            }
        )
    }

    if (uiState.wakeWordEnabled) {
        // --- ENGINE PICKLIST (same pattern as VoiceEnginesSubTab) ---
        Text(
            text = languageManager.getString("ww_engine_title"),
            style = MaterialTheme.typography.labelLarge
        )
        Text(
            text = languageManager.getString("ww_engine_desc"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(4.dp))

        val wakeEngines = remember(uiState.availableModels) {
            RemoteModelRegistry.getEngineKeysByType("wake_word")
        }

        Box {
            var engineExpanded by remember { mutableStateOf(false) }
            OutlinedButton(
                onClick = { engineExpanded = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(RemoteModelRegistry.getEngineLabel(currentEngineKey, languageManager))
            }
            DropdownMenu(
                expanded = engineExpanded,
                onDismissRequest = { engineExpanded = false },
                modifier = Modifier.fillMaxWidth()
            ) {
                wakeEngines.forEach { engKey ->
                    DropdownMenuItem(
                        text = { Text(RemoteModelRegistry.getEngineLabel(engKey, languageManager)) },
                        onClick = {
                            selectEngine(engKey)
                            engineExpanded = false
                        }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // --- COMMON: Command Queue Toggle ---
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(languageManager.getString("command_queue_enabled"))
            Switch(
                checked = uiState.commandQueueEnabled,
                onCheckedChange = { enabled ->
                    appStateManager.setCommandQueueEnabled(enabled)
                }
            )
        }

        // Picovoice AccessKey input (only for engines that require it)
        if (requiresApiKey) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = languageManager.getString("ww_porcupine_accesskey"),
                style = MaterialTheme.typography.labelLarge
            )
            val uriHandler = LocalUriHandler.current
            val descText = languageManager.getString("ww_porcupine_accesskey_desc")
            val linkUrl = "https://console.picovoice.ai"
            val annotatedDesc = remember(descText) {
                buildAnnotatedString {
                    val linkStart = descText.indexOf("console.picovoice.ai")
                    if (linkStart >= 0) {
                        append(descText.substring(0, linkStart))
                        withStyle(
                            SpanStyle(
                                color = Color.Unspecified,
                                textDecoration = TextDecoration.Underline
                            )
                        ) {
                            withAnnotation(tag = "URL", annotation = linkUrl) {
                                append("console.picovoice.ai")
                            }
                        }
                        append(descText.substring(linkStart + "console.picovoice.ai".length))
                    } else {
                        append(descText)
                    }
                }
            }
            ClickableText(
                text = annotatedDesc,
                style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                onClick = { offset ->
                    annotatedDesc.getStringAnnotations("URL", offset, offset)
                        .firstOrNull()?.let { uriHandler.openUri(it.item) }
                }
            )
            var localAccessKey by remember { mutableStateOf(uiState.picovoiceAccessKey ?: "") }
            LaunchedEffect(uiState.picovoiceAccessKey) {
                if ((uiState.picovoiceAccessKey ?: "") != localAccessKey) {
                    localAccessKey = uiState.picovoiceAccessKey ?: ""
                }
            }
            OutlinedTextField(
                value = localAccessKey,
                onValueChange = {
                    localAccessKey = it
                    appStateManager.setPicovoiceAccessKey(it.ifBlank { null })
                },
                label = { Text("AccessKey") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))
        }

        // --- ENGINE-SPECIFIC: Calibration ---
        if (supportsCalibration) {
            val hasProfile = uiState.wakeWordProfileJson != null
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        Text(
            text = languageManager.getString("ww_calibrate_title"),
            style = MaterialTheme.typography.labelLarge
        )
        Text(
            text = languageManager.getString("ww_calibrate_desc"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        val context = LocalContext.current
        val calibrator = remember { WakeWordCalibrator(context) { } }
        val calibrationState by calibrator.state.collectAsStateWithLifecycle()
        var showCalibrationDialog by remember { mutableStateOf(false) }

        // Editable profile name field (shown when profile exists)
        if (hasProfile) {
            val currentProfile = remember(uiState.wakeWordProfileJson) {
                uiState.wakeWordProfileJson?.let { WakeWordProfile.fromJson(it) }
            }
            var profileNameText by remember(currentProfile?.profileName) {
                mutableStateOf(currentProfile?.profileName ?: "")
            }
            var wasFocused by remember { mutableStateOf(false) }
            OutlinedTextField(
                value = profileNameText,
                onValueChange = { profileNameText = it },
                label = { Text(languageManager.getString("profile_name_label")) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
                    .onFocusChanged { focusState ->
                        if (wasFocused && !focusState.isFocused) {
                            val namedProfile = currentProfile?.copy(
                                profileName = profileNameText.trim().ifBlank { null }
                            )
                            if (namedProfile != null) {
                                appStateManager.setWakeWordProfile(WakeWordProfile.toJson(namedProfile))
                            }
                        }
                        wasFocused = focusState.isFocused
                    }
            )
        } else {
            Text(
                text = languageManager.getString("ww_calibrate_default"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        var showProfileNameDialog by remember { mutableStateOf(false) }
        var pendingProfile by remember { mutableStateOf<WakeWordProfile?>(null) }

        // Auto-save profile on completion
        LaunchedEffect(calibrationState) {
            if (calibrationState is WakeWordCalibrator.CalibrationState.Complete) {
                VoiceManager.setCalibrationListening(false)
                val profile = (calibrationState as WakeWordCalibrator.CalibrationState.Complete).profile
                pendingProfile = profile
                showProfileNameDialog = true
                delay(1500)
                showCalibrationDialog = false
                calibrator.stop()
            } else if (calibrationState is WakeWordCalibrator.CalibrationState.Failed) {
                VoiceManager.setCalibrationListening(false)
                delay(3000)
                calibrator.stop()
            } else if (calibrationState is WakeWordCalibrator.CalibrationState.Listening) {
                VoiceManager.setCalibrationListening(true)
            } else if (calibrationState is WakeWordCalibrator.CalibrationState.Analyzing) {
                VoiceManager.setCalibrationListening(false)
            } else if (calibrationState is WakeWordCalibrator.CalibrationState.Waiting) {
                VoiceManager.setCalibrationListening(false)
            } else if (calibrationState is WakeWordCalibrator.CalibrationState.MeasuringNoise) {
                VoiceManager.setCalibrationListening(false)
            }
        }

        // Pipe calibrator volume to VoiceManager so ListeningScreen shows live audio
        LaunchedEffect(calibrator) {
            calibrator.volumeFlow.collect { vol ->
                VoiceManager.setCalibrationVolume(vol)
            }
        }

        // Calibration buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val isCalibrating = calibrationState is WakeWordCalibrator.CalibrationState.Waiting ||
                calibrationState is WakeWordCalibrator.CalibrationState.Listening ||
                calibrationState is WakeWordCalibrator.CalibrationState.Analyzing ||
                calibrationState is WakeWordCalibrator.CalibrationState.MeasuringNoise

            Button(
                onClick = {
                    showCalibrationDialog = true
                    calibrator.startCalibration()
                },
                enabled = !isCalibrating && !hasProfile,
                modifier = Modifier.weight(1f)
            ) {
                Text(languageManager.getString("ww_calibrate_start"))
            }

            if (hasProfile) {
                OutlinedButton(
                    onClick = { showDeleteProfileDialog = true },
                    enabled = !isCalibrating,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(languageManager.getString("ww_calibrate_delete"))
                }
            }
        }

        // Calibration Dialog
        if (showCalibrationDialog) {
            CalibrationDialog(
                state = calibrationState,
                languageManager = languageManager,
                appStateManager = appStateManager,
                onReady = { round -> calibrator.signalReady(round) },
                onDismiss = {
                    VoiceManager.setCalibrationListening(false)
                    calibrator.stop()
                    showCalibrationDialog = false
                }
            )
        }

        // Profile Name Dialog — shown after calibration completes
        if (showProfileNameDialog && pendingProfile != null) {
            var profileName by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = {
                    // Save without name if dismissed
                    appStateManager.setWakeWordProfile(WakeWordProfile.toJson(pendingProfile!!))
                    showProfileNameDialog = false
                    pendingProfile = null
                },
                title = { Text(languageManager.getString("profile_name_title")) },
                text = {
                    Column {
                        Text(
                            text = languageManager.getString("profile_name_desc"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = profileName,
                            onValueChange = { profileName = it },
                            label = { Text(languageManager.getString("profile_name_label")) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val namedProfile = pendingProfile!!.copy(
                                profileName = profileName.trim().ifBlank { null }
                            )
                            appStateManager.setWakeWordProfile(WakeWordProfile.toJson(namedProfile))
                            showProfileNameDialog = false
                            pendingProfile = null
                        }
                    ) { Text(languageManager.getString("profile_name_save")) }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            appStateManager.setWakeWordProfile(WakeWordProfile.toJson(pendingProfile!!))
                            showProfileNameDialog = false
                            pendingProfile = null
                        }
                    ) { Text(languageManager.getString("profile_name_skip")) }
                }
            )
        }

        // Delete Profile Confirmation Dialog
        if (showDeleteProfileDialog) {
            AlertDialog(
                onDismissRequest = { showDeleteProfileDialog = false },
                title = { Text(languageManager.getString("ww_delete_profile_title") ?: "Delete Voice Profile") },
                text = { Text(languageManager.getString("ww_delete_profile_msg") ?: "This will remove your voice calibration profile. Wake word detection will fall back to basic mode. Are you sure?") },
                confirmButton = {
                    TextButton(onClick = {
                        appStateManager.clearWakeWordProfile()
                        showDeleteProfileDialog = false
                    }) { Text(languageManager.getString("ww_calibrate_delete")) }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteProfileDialog = false }) {
                        Text(languageManager.getString("cancel_button") ?: "Cancel")
                    }
                }
            )
        }

        } // end if (supportsCalibration) — calibration section

        // --- WAKE WORD SENSITIVITY ---
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        Text(
            text = languageManager.getString("ww_sensitivity_label") ?: "Wake Word Sensitivity",
            style = MaterialTheme.typography.labelLarge
        )
        Text(
            text = languageManager.getString("ww_sensitivity_desc") ?: "Adjust how easily the wake word triggers",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(4.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val levels = listOf("low", "medium", "high")
            val labels = listOf(
                languageManager.getString("ww_sensitivity_low") ?: "Low",
                languageManager.getString("ww_sensitivity_medium") ?: "Medium",
                languageManager.getString("ww_sensitivity_high") ?: "High"
            )
            levels.forEachIndexed { idx, level ->
                FilterChip(
                    selected = uiState.wakeWordSensitivity == level,
                    onClick = { appStateManager.setWakeWordSensitivity(level) },
                    label = { Text(labels[idx]) }
                )
            }
        }

        // --- ENGINE-SPECIFIC: Model Selection via EngineModelSection ---
        val engineModels = remember(currentEngineKey, refreshTrigger) {
            RemoteModelRegistry.getModels(currentEngineKey)
        }

        val displayModels = remember(engineModels, uiState.modelFilterLang, supportsModelDownload) {
            if (supportsModelDownload && !isVoskMultilingual) engineModels.filter { it.langCode == uiState.modelFilterLang }
            else engineModels
        }

        val selectedModel = remember(displayModels, uiState.wakeWordModelPath) {
            val path = uiState.wakeWordModelPath
            if (path != null) displayModels.find { it.id == path } else displayModels.firstOrNull()
        }

        if (displayModels.isNotEmpty()) {
            EngineModelSection(
                title = languageManager.getString("wake_word_model"),
                languageManager = languageManager,
                settingsRepo = settingsRepo,
                appStateManager = appStateManager,
                groups = remember(displayModels, refreshTrigger) {
                    listOf(DropdownGroup(languageManager.getString("available_models_header") ?: "AVAILABLE MODELS", displayModels))
                },
                selectedItem = selectedModel,
                itemLabel = { if (supportsModelDownload) "${it.label} (${it.sizeDescription})" else it.label },
                modelIdProvider = { it.id },
                onItemSelected = { model, _ ->
                    appStateManager.setWakeWordModelPath(model.id)
                    if (supportsBuiltinKeywords) {
                        appStateManager.setWakeWord(model.label)
                    }
                },
                onDownloadRequest = { model -> onDownloadRequest(model) },
                onDeleteRequest = { model -> onDeleteRequest(model) },
                onCancelDownload = onCancelDownload,
                downloadProgress = downloadProgress,
                downloadingItem = downloadingItem,
                currentProcessor = currentEngineKey,
                fallbackCategory = Strings.FallbackCategories.VOICE,
                refreshTrigger = refreshTrigger
            )
        }

        // --- COMMON: Wake Word Text Field ---
        val hasProfile = uiState.wakeWordProfileJson != null

        val isWakeWordModelOnDevice = if (supportsBuiltinKeywords || hasBuiltinModels) true
            else remember(selectedModel, refreshTrigger) {
                selectedModel != null && uiState.isModelDownloaded(selectedModel.id)
            }

        var localWakeWord by remember { mutableStateOf(uiState.wakeWord ?: "") }
        LaunchedEffect(uiState.wakeWord) {
            if ((uiState.wakeWord ?: "") != localWakeWord) {
                localWakeWord = uiState.wakeWord ?: ""
            }
        }

        VoiceInputTextField(
            value = if (hasProfile) "" else localWakeWord,
            onValueChange = {
                if (!hasProfile) {
                    localWakeWord = it
                    appStateManager.setWakeWord(it)
                }
            },
            label = { Text(languageManager.getString("wake_word_label")) },
            placeholder = { Text(if (hasProfile) languageManager.getString("ww_profile_used") else languageManager.getString("wake_word_hint")) },
            languageManager = languageManager,
            modelFilterLang = uiState.modelFilterLang,
            voiceProcessor = uiState.voiceProcessor,
            isModelOnDevice = isWakeWordModelOnDevice,
            readOnly = hasProfile,
            enabled = !hasProfile
        )

        // Built-in keywords hint
        if (supportsBuiltinKeywords) {
            Text(
                text = languageManager.getString("ww_porcupine_keywords_hint"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, top = 2.dp)
            )
        }

        // --- COMMON: Service Status + Start/Stop ---
        Text(text = languageManager.getString("service_status"), style = MaterialTheme.typography.labelLarge)
        Text(
            text = if (uiState.isWakeWordServiceListening) languageManager.getString("service_running") else languageManager.getString("service_stopped"),
            style = MaterialTheme.typography.bodyMedium,
            color = if (uiState.isWakeWordServiceListening) downloadedColor else MaterialTheme.colorScheme.secondary
        )

        val isModelOnDevice = if (supportsBuiltinKeywords || hasBuiltinModels) true
            else remember(selectedModel, uiState, refreshTrigger) {
                selectedModel != null && uiState.isModelDownloaded(selectedModel.id)
            }

        Button(
            onClick = if (uiState.isWakeWordServiceListening) onStopService else onStartService,
            modifier = Modifier.fillMaxWidth(),
            enabled = uiState.isWakeWordServiceListening || isModelOnDevice,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (uiState.isWakeWordServiceListening) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
            )
        ) {
            Text(if (uiState.isWakeWordServiceListening) languageManager.getString("stop_service") else languageManager.getString("start_service"))
        }

        // Show warning if model not on device (engines that require download)
        if (supportsModelDownload && !isModelOnDevice && !uiState.isWakeWordServiceListening) {
            Text(
                text = "Selected model not on device. Please download the model first.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        DefaultAppsTab(
            languageManager = languageManager,
            settingsRepo = settingsRepo,
            appStateManager = appStateManager
        )
    } // end if (uiState.wakeWordEnabled)
    } // end if (selectedSubTab == 0)
    else {
        // --- TTS SUB-TAB ---
        Text(
            text = languageManager.getString("tts_settings_title") ?: "Text-to-Speech",
            style = MaterialTheme.typography.titleMedium
        )

        // TTS Enable Toggle
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = languageManager.getString("tts_enabled_label") ?: "Enable TTS responses",
                style = MaterialTheme.typography.bodyLarge
            )
            Switch(
                checked = uiState.ttsEnabled,
                onCheckedChange = { appStateManager.setTtsEnabled(it) }
            )
        }

        if (uiState.ttsEnabled) {
            // Speech Rate Slider
            Text(
                text = (languageManager.getString("tts_speech_rate_label") ?: "Speech rate") + ": ${"%.1f".format(uiState.ttsSpeechRate)}x",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Slider(
                value = uiState.ttsSpeechRate,
                onValueChange = { appStateManager.setTtsSpeechRate(it) },
                valueRange = 0.5f..2.0f,
                steps = 14
            )

            // Pitch Slider
            Text(
                text = (languageManager.getString("tts_pitch_label") ?: "Pitch") + ": ${"%.1f".format(uiState.ttsPitch)}x",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Slider(
                value = uiState.ttsPitch,
                onValueChange = { appStateManager.setTtsPitch(it) },
                valueRange = 0.5f..2.0f,
                steps = 14
            )

            // Audio Focus Mode
            Text(
                text = languageManager.getString("tts_audio_focus_label") ?: "Media during TTS",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val modes = listOf("none", "duck", "pause")
                val labels = listOf(
                    languageManager.getString("tts_audio_focus_none") ?: "Ignore",
                    languageManager.getString("tts_audio_focus_duck") ?: "Lower volume",
                    languageManager.getString("tts_audio_focus_pause") ?: "Pause"
                )
                modes.forEachIndexed { idx, mode ->
                    FilterChip(
                        selected = uiState.ttsAudioFocusMode == mode,
                        onClick = { appStateManager.setTtsAudioFocusMode(mode) },
                        label = { Text(labels[idx]) }
                    )
                }
            }
        }
    } // end else (TTS sub-tab)
}

@Composable
private fun CalibrationDialog(
    state: WakeWordCalibrator.CalibrationState,
    languageManager: LanguageManager,
    appStateManager: AppStateManager,
    onReady: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(languageManager.getString("ww_calibrate_title"))
        },
        text = {
            when (state) {
                is WakeWordCalibrator.CalibrationState.MeasuringNoise -> {
                    Column {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                        )
                        Text(
                            text = state.instruction,
                            style = MaterialTheme.typography.bodyLarge,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
                is WakeWordCalibrator.CalibrationState.Waiting -> {
                    val roundText = languageManager.getString("ww_calibrate_round")
                        .replace("{0}", state.round.toString())
                        .replace("{1}", state.total.toString())
                    Column {
                        // Progress bar
                        LinearProgressIndicator(
                            progress = state.round.toFloat() / state.total,
                            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                        )
                        Text(
                            text = roundText,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = state.instruction,
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = languageManager.getString("ww_calibrate_tap_ready"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                is WakeWordCalibrator.CalibrationState.Listening -> {
                    val roundText = languageManager.getString("ww_calibrate_round")
                        .replace("{0}", state.round.toString())
                        .replace("{1}", state.total.toString())
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        LinearProgressIndicator(
                            progress = state.round.toFloat() / state.total,
                            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                        )
                        Text(
                            text = roundText,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = languageManager.getString("ww_calibrate_listening"),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(Modifier.height(12.dp))
                        ListeningScreen(
                            languageManager = languageManager,
                            appStateManager = appStateManager,
                            onStop = onDismiss
                        )
                    }
                }
                is WakeWordCalibrator.CalibrationState.Analyzing -> {
                    val roundText = languageManager.getString("ww_calibrate_round")
                        .replace("{0}", state.round.toString())
                        .replace("{1}", "5")
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        LinearProgressIndicator(
                            progress = state.round.toFloat() / 5,
                            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                        )
                        Text(
                            text = "$roundText - ${languageManager.getString("ww_calibrate_analyzing")}",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                        Spacer(Modifier.height(12.dp))
                        ListeningScreen(
                            languageManager = languageManager,
                            appStateManager = appStateManager,
                            onStop = onDismiss
                        )
                    }
                }
                is WakeWordCalibrator.CalibrationState.Complete -> {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        LinearProgressIndicator(
                            progress = 1f,
                            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                        )
                        Text(
                            text = languageManager.getString("ww_calibrate_complete"),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                is WakeWordCalibrator.CalibrationState.Failed -> {
                    Text(
                        text = languageManager.getString("ww_calibrate_failed") + ": ${state.message}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                else -> {}
            }
        },
        confirmButton = {
            when (state) {
                is WakeWordCalibrator.CalibrationState.Waiting -> {
                    Button(onClick = { onReady(state.round) }) {
                        Text(languageManager.getString("ww_calibrate_ready"))
                    }
                }
                else -> {}
            }
        },
        dismissButton = {
            when (state) {
                is WakeWordCalibrator.CalibrationState.Complete -> {}
                else -> {
                    TextButton(onClick = onDismiss) {
                        Text(languageManager.getString("cancel_button"))
                    }
                }
            }
        }
    )
}
