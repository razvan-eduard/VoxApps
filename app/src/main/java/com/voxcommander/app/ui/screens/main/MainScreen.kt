package com.voxcommander.app.ui.screens.main

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
import com.voxcommander.app.data.local.dao.FastMapDao
import com.voxcommander.app.data.preferences.SettingsRepository
import com.voxcommander.app.domain.localization.LanguageManager
import com.voxcommander.app.domain.model.AppModel
import com.voxcommander.app.domain.voice.VoiceManager
import com.voxcommander.app.state.AppStateManager
import com.voxcommander.app.ui.components.MicrophoneButton
import com.voxcommander.app.ui.components.AppScanModal
import com.voxcommander.app.ui.components.ModelNotPresentMessage
import com.voxcommander.app.ui.components.TopHeaderContainer
import com.voxcommander.app.ui.components.TopHeaderMode
import com.voxcommander.app.ui.components.VulkanTestModal
import com.voxcommander.app.ui.viewmodels.MainViewModel
import com.voxcommander.app.utils.NetworkMonitor
import com.voxcommander.app.utils.Strings
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    languageManager: LanguageManager,
    settingsRepo: SettingsRepository,
    appStateManager: AppStateManager,
    fastMapDao: FastMapDao,
    viewModel: MainViewModel,
    modelManagementViewModel: com.voxcommander.app.ui.viewmodels.ModelManagementViewModel,
    onDownloadModel: (String, String, String?) -> Unit,
    onDeleteUnusedModels: () -> Unit,
    onDeleteModel: (String, String) -> Unit,
    onCancelDownload: () -> Unit,
    downloadProgress: Float?,
    selectionSuccessMessage: String?,
    googleSttAvailable: Boolean,
    updateVoiceEngine: () -> Unit,
    onRequestOverlayPermission: () -> Unit,
    onRequestMicrophonePermission: () -> Unit,
    onRequestNotificationPermission: () -> Unit,
    onRequestLocationPermission: () -> Unit = {},
    onImportCustomModel: (String?) -> Unit = {},
    onClearCustomModel: () -> Unit = {},
    onImportOpenWakeWordModel: () -> Unit = {}
) {
    val lastIntent by viewModel.currentIntent.collectAsStateWithLifecycle()
    val isProcessing by viewModel.isProcessing.collectAsStateWithLifecycle()
    val transcription by viewModel.transcription.collectAsStateWithLifecycle()
    val searchResults by viewModel.searchResults.collectAsStateWithLifecycle()
    val isListening by VoiceManager.isListeningFlow.collectAsStateWithLifecycle()
    val uiState by appStateManager.uiState.collectAsStateWithLifecycle()
    val isOnline by NetworkMonitor.onlineFlow.collectAsStateWithLifecycle()

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
                        Button(onClick = { viewModel.processTextCommand(manualText) }) {
                            Text(languageManager.getString("test_button"))
                        }
                    }
                )

                Spacer(modifier = Modifier.height(32.dp))

                // Microphone Button
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    MicrophoneButton(
                        languageManager = languageManager,
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
                        languageManager = languageManager,
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
                        Text(
                            text = lastIntent?.let { gson.toJson(it) } ?: languageManager.getString("no_intent"),
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.align(Alignment.TopStart)
                        )
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
                        if (searchResults != null) {
                            Text(
                                text = searchResults!!,
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
            languageManager = languageManager,
            settingsRepo = settingsRepo,
            appStateManager = appStateManager,
            modelManagementViewModel = modelManagementViewModel,
            fastMapDao = fastMapDao,
            onDismissRequest = { currentHeaderMode = TopHeaderMode.NONE },
            onDownloadModel = onDownloadModel,
            onDeleteUnusedModels = onDeleteUnusedModels,
            onDeleteModel = onDeleteModel,
            onCancelDownload = onCancelDownload,
            onRefreshMain = { /* AppStateManager handles state updates automatically */ },
            downloadProgress = downloadProgress,
            selectionSuccessMessage = selectionSuccessMessage,
            googleSttAvailable = googleSttAvailable,
            updateVoiceEngine = updateVoiceEngine,
            onRequestOverlayPermission = onRequestOverlayPermission,
            onRequestMicrophonePermission = onRequestMicrophonePermission,
            onRequestNotificationPermission = onRequestNotificationPermission,
            onRequestLocationPermission = onRequestLocationPermission,
            onImportCustomModel = onImportCustomModel,
            onClearCustomModel = onClearCustomModel,
            onImportOpenWakeWordModel = onImportOpenWakeWordModel
        )

        // --- VULKAN TEST MODAL ---
        VulkanTestModal(
            vulkanTestState = appStateManager.vulkanTestState.collectAsStateWithLifecycle().value,
            vulkanTestPassed = appStateManager.vulkanTestPassed.collectAsStateWithLifecycle().value,
            onDismiss = { appStateManager.dismissVulkanTestResult() },
            languageManager = languageManager
        )

        // --- APP SCAN MODAL ---
        AppScanModal(
            scanState = appStateManager.appScanState.collectAsStateWithLifecycle().value,
            onDismiss = { appStateManager.dismissAppScanResult() },
            languageManager = languageManager
        )

        // --- IN-APP SPEAKING OVERLAY (when WakeWordService is not running) ---
        SpeakingOverlay(
            languageManager = languageManager
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
                        parser.fromJson(jsonText, com.voxcommander.app.domain.intent.model.NluIntent::class.java)
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
