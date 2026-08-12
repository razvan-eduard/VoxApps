package com.voxapps.commander.ui.screens.main

import com.voxapps.commander.ui.LocalLanguageManager

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.voxapps.commander.data.local.dao.FastMapDao
import com.voxapps.commander.data.preferences.SettingsRepository
import com.voxapps.commander.domain.localization.LanguageManager
import com.voxapps.commander.domain.model.AppModel
import com.voxapps.commander.domain.voice.VoiceManager
import com.voxapps.commander.state.AppStateManager
import com.voxapps.commander.ui.components.MicrophoneButton
import com.voxapps.commander.ui.components.AppScanModal
import com.voxapps.commander.ui.components.ModelNotPresentMessage
import com.voxapps.commander.ui.components.TopHeaderContainer
import com.voxapps.commander.ui.components.TopHeaderMode
import com.voxapps.commander.ui.components.VulkanTestModal
import com.voxapps.commander.ui.viewmodels.MainViewModel
import com.voxapps.commander.utils.NetworkMonitor
import com.voxapps.commander.utils.Strings
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(

    settingsRepo: SettingsRepository,
    appStateManager: AppStateManager,
    fastMapDao: FastMapDao,
    viewModel: MainViewModel,
    modelManagementViewModel: com.voxapps.commander.ui.viewmodels.ModelManagementViewModel,
    onDownloadModel: (String, String, String?) -> Unit,
    onDeleteUnusedModels: () -> Unit,
    onDeleteModel: (String, String) -> Unit,
    onCancelDownload: () -> Unit,
    downloadProgress: Float?,
    selectionSuccessMessage: String?,
    googleSttAvailable: Boolean,
    onRequestOverlayPermission: () -> Unit,
    onRequestMicrophonePermission: () -> Unit,
    onRequestNotificationPermission: () -> Unit,
    onRequestLocationPermission: () -> Unit = {},
    onRequestBatteryOptimizationPermission: () -> Unit = {},
    onImportCustomModel: (String?) -> Unit = {},
    onImportIntentModel: () -> Unit = {},
    onImportOpenWakeWordModel: () -> Unit = {},
    // Incremented (not a plain Boolean) by MainActivity whenever a widget "tap to speak" launch
    // arrives — from onCreate's initial intent AND from onNewIntent if the activity was already
    // running — so a second widget tap while the app is already open still re-triggers even
    // though a Boolean would look "unchanged". 0 = no pending request (the default, normal launch).
    autoStartListeningTrigger: Int = 0
) {
        val languageManager = LocalLanguageManager.current
    val lastIntent by viewModel.currentIntent.collectAsStateWithLifecycle()
    val isProcessing by viewModel.isProcessing.collectAsStateWithLifecycle()
    val transcription by viewModel.transcription.collectAsStateWithLifecycle()
    val searchResults by viewModel.searchResults.collectAsStateWithLifecycle()
    val isListening by VoiceManager.isListeningFlow.collectAsStateWithLifecycle()
    val uiState by appStateManager.uiState.collectAsStateWithLifecycle()
    val isOnline by NetworkMonitor.onlineFlow.collectAsStateWithLifecycle()

    // Widget "tap to speak" — mirrors the in-app mic button's onClick below exactly.
    LaunchedEffect(autoStartListeningTrigger) {
        if (autoStartListeningTrigger > 0 && !isProcessing) {
            viewModel.processVoiceCommand(uiState.modelFilterLang, uiState.voiceProcessor)
        }
    }

    var currentHeaderMode by remember { mutableStateOf(TopHeaderMode.NONE) }
    
    var manualText by remember { mutableStateOf("") }
    val gson = remember { GsonBuilder().setPrettyPrinting().create() }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(languageManager.getString("main_screen")) },
                    actions = {
                        IconButton(onClick = { currentHeaderMode = TopHeaderMode.RULES }) {
                            Icon(Icons.Default.List, contentDescription = languageManager.getString("content_desc_rules"))
                        }
                        IconButton(onClick = { currentHeaderMode = TopHeaderMode.SETTINGS }) {
                            Icon(Icons.Default.Settings, contentDescription = languageManager.getString("content_desc_settings"))
                        }
                    }
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // --- OFFLINE BANNER ---
                if (!isOnline) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                    ) {
                        Text(
                            text = languageManager.getString("offline_banner"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                }

                // Manual Text Input
                OutlinedTextField(
                    value = manualText,
                    onValueChange = { manualText = it },
                    label = { Text(languageManager.getString("manual_command")) },
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        Button(onClick = { viewModel.processTextCommand(manualText, uiState.modelFilterLang) }) {
                            Text(languageManager.getString("test_button"))
                        }
                    }
                )

                Spacer(modifier = Modifier.height(32.dp))

                // Microphone Button
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    MicrophoneButton(

                        appStateManager = appStateManager,
                        isProcessing = isProcessing,
                        onClick = {
                            if (isProcessing) {
                                viewModel.stopVoiceCommand()
                            } else {
                                viewModel.processVoiceCommand(uiState.modelFilterLang, uiState.voiceProcessor)
                            }
                        }
                    )

                    ModelNotPresentMessage(

                        appStateManager = appStateManager
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Show transcription only, "Recording" status is now handled visually by the button
                Text(
                    text = if (isProcessing && transcription.isEmpty()) "" else transcription,
                    style = MaterialTheme.typography.bodyMedium
                )

                Spacer(modifier = Modifier.height(32.dp))

                Text(
                    text = languageManager.getString("last_intent"),
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )

                Spacer(modifier = Modifier.height(8.dp))

                var showManualIntentDialog by remember { mutableStateOf(false) }

                // Last Intent (half height)
                Card(
                    modifier = Modifier.fillMaxWidth().weight(1f)
                        .clickable {
                            if (lastIntent != null) showManualIntentDialog = true
                        },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Box(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                        if (lastIntent != null) {
                            Text(
                                text = gson.toJson(lastIntent),
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.align(Alignment.TopStart)
                            )
                        } else {
                            Text(
                                text = languageManager.getString("no_intent"),
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.align(Alignment.Center)
                            )
                        }
                    }
                }

                if (showManualIntentDialog) {
                    ManualIntentDialog(
                        initialJson = lastIntent?.let { gson.toJson(it) } ?: "",
                        onDismiss = { showManualIntentDialog = false },
                        onSend = { json ->
                            viewModel.routeManualIntent(json)
                            showManualIntentDialog = false
                        }
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Search Results (half height)
                Text(
                    text = languageManager.getString("search_results"),
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )

                Spacer(modifier = Modifier.height(8.dp))

                Card(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Box(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                        val results = searchResults
                        if (results != null) {
                            Text(
                                text = results,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .verticalScroll(rememberScrollState())
                            )
                        } else {
                            Text(
                                text = languageManager.getString("no_search_results"),
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.align(Alignment.Center)
                            )
                        }
                    }
                }
            }
        }

        // --- UNIFIED TOP HEADER CONTAINER ---
        TopHeaderContainer(
            mode = currentHeaderMode,

            settingsRepo = settingsRepo,
            appStateManager = appStateManager,
            modelManagementViewModel = modelManagementViewModel,
            fastMapDao = fastMapDao,
            onDismissRequest = { currentHeaderMode = TopHeaderMode.NONE },
            onDownloadModel = onDownloadModel,
            onDeleteUnusedModels = onDeleteUnusedModels,
            onDeleteModel = onDeleteModel,
            onCancelDownload = onCancelDownload,
            downloadProgress = downloadProgress,
            selectionSuccessMessage = selectionSuccessMessage,
            googleSttAvailable = googleSttAvailable,
            onRequestOverlayPermission = onRequestOverlayPermission,
            onRequestMicrophonePermission = onRequestMicrophonePermission,
            onRequestNotificationPermission = onRequestNotificationPermission,
            onRequestLocationPermission = onRequestLocationPermission,
            onRequestBatteryOptimizationPermission = onRequestBatteryOptimizationPermission,
            onImportCustomModel = onImportCustomModel,
            onImportIntentModel = onImportIntentModel,
            onImportOpenWakeWordModel = onImportOpenWakeWordModel
        )

        // --- VULKAN TEST MODAL ---
        VulkanTestModal(
            vulkanTestState = appStateManager.vulkanTestState.collectAsStateWithLifecycle().value,
            vulkanTestPassed = appStateManager.vulkanTestPassed.collectAsStateWithLifecycle().value,
            onDismiss = { appStateManager.dismissVulkanTestResult() }

        )

        // --- APP SCAN MODAL ---
        AppScanModal(
            scanState = appStateManager.appScanState.collectAsStateWithLifecycle().value,
            onDismiss = { appStateManager.dismissAppScanResult() }

        )

        // --- IN-APP SPEAKING OVERLAY (when WakeWordService is not running) ---
        SpeakingOverlay(

            onStop = {
                com.voxapps.commander.domain.voice.TtsManager.stop()
                com.voxapps.commander.domain.voice.VoiceManager.stopListening()
                appStateManager.setVoiceState(com.voxapps.commander.state.VoiceState.IDLE)
            }
        )
    }
}

@Composable
private fun ManualIntentDialog(
    initialJson: String,
    onDismiss: () -> Unit,
    onSend: (String) -> Unit
) {
    var jsonText by remember { mutableStateOf(initialJson) }
    var parseError by remember { mutableStateOf<String?>(null) }
    val parser = remember { Gson() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Manual Intent (JSON)") },
        text = {
            Column {
                Text(
                    text = "Edit the JSON and tap Send to route this intent directly.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = jsonText,
                    onValueChange = {
                        jsonText = it
                        parseError = null
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 200.dp, max = 400.dp),
                    textStyle = androidx.compose.ui.text.TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp
                    ),
                    isError = parseError != null,
                    supportingText = {
                        parseError?.let {
                            Text(it, color = MaterialTheme.colorScheme.error)
                        }
                    }
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    try {
                        parser.fromJson(jsonText, com.voxapps.commander.domain.intent.model.NluIntent::class.java)
                        onSend(jsonText)
                    } catch (e: Exception) {
                        parseError = "Invalid JSON: ${e.message}"
                    }
                }
            ) { Text("Send") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
