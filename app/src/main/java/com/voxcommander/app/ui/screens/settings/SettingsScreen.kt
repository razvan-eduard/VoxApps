package com.voxcommander.app.ui.screens.settings

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.voxcommander.app.data.preferences.SettingsRepository
import com.voxcommander.app.data.remote.RemoteModelRegistry
import com.voxcommander.app.domain.localization.LanguageManager
import com.voxcommander.app.service.WakeWordService
import com.voxcommander.app.domain.model.AppModel
import com.voxcommander.app.state.AppStateManager
import com.voxcommander.app.ui.screens.main.ListeningScreen
import com.voxcommander.app.utils.Strings
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun SettingsContent(
    languageManager: LanguageManager,
    settingsRepo: SettingsRepository,
    appStateManager: AppStateManager,
    modelManagementViewModel: com.voxcommander.app.ui.viewmodels.ModelManagementViewModel,
    onDownloadModel: (String, String, String?) -> Unit,
    onDeleteUnusedModels: () -> Unit,
    onDeleteModel: (String, String) -> Unit,
    onCancelDownload: () -> Unit = {},
    onRefreshMain: () -> Unit = {},
    downloadProgress: Float? = null,
    googleSttAvailable: Boolean = true,
    updateVoiceEngine: () -> Unit = {},
    onRequestOverlayPermission: () -> Unit = {},
    onRequestMicrophonePermission: () -> Unit = {},
    onRequestNotificationPermission: () -> Unit = {},
    onRequestLocationPermission: () -> Unit = {},
    onImportCustomModel: (String?) -> Unit = {},
    onClearCustomModel: () -> Unit = {},
    onImportOpenWakeWordModel: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // REALTIME STATE - observe AppStateManager uiState for reactive updates
    val uiState by appStateManager.uiState.collectAsStateWithLifecycle()
    
    val pagerState = rememberPagerState(pageCount = { 6 })

    val isVoskLoading by modelManagementViewModel.isVoskLoading.collectAsStateWithLifecycle()
    val isVoskOffline by modelManagementViewModel.isVoskOffline.collectAsStateWithLifecycle()
    val voskError by modelManagementViewModel.voskError.collectAsStateWithLifecycle()
    
    val vmDownloadingItem by modelManagementViewModel.downloadingItem.collectAsStateWithLifecycle()

    LaunchedEffect(downloadProgress) {
        if (downloadProgress == null || downloadProgress >= 1.0f) {
            onRefreshMain()
        }
    }

    var modelToDelete by remember { mutableStateOf<AppModel?>(null) }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }
    var showCleanupDialog by remember { mutableStateOf(false) }

    val downloadedColor = Color(0xFF2E7D32)

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
                    color = MaterialTheme.colorScheme.surface
                ) {
                    Column {
                        Text(
                            text = languageManager.getString("settings"),
                            style = MaterialTheme.typography.headlineSmall,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp)
                        )

                        ScrollableTabRow(
                            selectedTabIndex = pagerState.currentPage,
                            edgePadding = 16.dp,
                            containerColor = Color.Transparent,
                            divider = {}, 
                            indicator = { tabPositions ->
                                if (pagerState.currentPage < tabPositions.size) {
                                    TabRowDefaults.SecondaryIndicator(
                                        Modifier.tabIndicatorOffset(tabPositions[pagerState.currentPage])
                                    )
                                }
                            },
                            modifier = Modifier.padding(bottom = 12.dp)
                        ) {
                            val tabs = listOf("tab_general", "tab_ai_models", "tab_service", "tab_integrations", "tab_permissions", "tab_advanced")
                            
                            tabs.forEachIndexed { index, tabKey ->
                                val selected = pagerState.currentPage == index
                                Tab(
                                    selected = selected,
                                    onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                                    text = { Text(text = languageManager.getString(tabKey), fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal) },
                                    modifier = Modifier.padding(horizontal = 4.dp).background(color = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent, shape = RoundedCornerShape(24.dp))
                                )
                            }
                        }
                    }
                }
            }
        ) { padding ->
            Box(modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
                HorizontalPager(
                    state = pagerState, 
                    modifier = Modifier.fillMaxSize(), 
                    beyondViewportPageCount = 1
                ) { page ->
                    if (page == 5) { // Advanced (uses LazyColumn, no scroll wrapper)
                        AdvancedSettingsTab(
                            languageManager = languageManager,
                            settingsRepo = settingsRepo,
                            appStateManager = appStateManager,
                            onCleanupRequest = { showCleanupDialog = true },
                            onClearDefaultFallback = { 
                                modelManagementViewModel.clearDefaultOfflineFallback()
                            },
                            onVerboseLoggingChange = { 
                                appStateManager.setVerboseLoggingEnabled(it)
                            },
                            refreshTrigger = uiState.refreshTrigger
                        )
                    } else {
                        val focusManager = LocalFocusManager.current
                    Column(modifier = Modifier.fillMaxSize().padding(top = 16.dp).verticalScroll(rememberScrollState()).clickable { focusManager.clearFocus() }, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            when (page) {
                                0 -> GeneralSettingsTab(
                                    languageManager = languageManager,
                                    settingsRepo = settingsRepo,
                                    appStateManager = appStateManager
                                )
                                1 -> ModelsSettingsTab(
                                    languageManager = languageManager,
                                    settingsRepo = settingsRepo,
                                    appStateManager = appStateManager,
                                    onProcessorSelected = {
                                        appStateManager.setVoiceProcessor(it)
                                        updateVoiceEngine(); onRefreshMain()
                                    },
                                    hasApiKey = uiState.apiKey != null,
                                    googleSttAvailable = googleSttAvailable,
                                    onVoiceLanguageSelected = {
                                        appStateManager.setModelFilterLang(it)
                                        updateVoiceEngine(); onRefreshMain()
                                    },
                                    onModelSelected = { model: AppModel, isDownloaded: Boolean, langCode: String ->
                                        modelManagementViewModel.selectVoiceModel(model.id, model.engineType, langCode)
                                        updateVoiceEngine(); onRefreshMain()
                                    },
                                    onDownloadModel = onDownloadModel,
                                    onDeleteModel = { modelId, engineKey -> 
                                        modelManagementViewModel.deleteModel(modelId, engineKey) 
                                    },
                                    downloadProgress = downloadProgress,
                                    downloadingItem = vmDownloadingItem,
                                    downloadedColor = downloadedColor,
                                    onCancelDownload = onCancelDownload,
                                    onDeleteRequest = { model ->
                                        modelToDelete = model
                                        showDeleteConfirmDialog = true
                                    },
                                    onFallbackChanged = { appStateManager.refreshAll() },
                                    onImportCustomModel = onImportCustomModel,
                                    onClearCustomModel = onClearCustomModel,
                                    refreshTrigger = uiState.refreshTrigger
                                )
                                2 -> {
                                    ServiceSettingsTab(
                                        languageManager = languageManager,
                                        settingsRepo = settingsRepo,
                                        appStateManager = appStateManager,
                                        onStartService = { WakeWordService.startService(context) },
                                        onStopService = { WakeWordService.stopService(context) },
                                        downloadedColor = downloadedColor,
                                        onDownloadRequest = { model -> onDownloadModel(model.id, model.engineType, "en") },
                                        onDeleteRequest = { model -> modelToDelete = model as? AppModel; showDeleteConfirmDialog = true },
                                        onCancelDownload = onCancelDownload,
                                        downloadProgress = downloadProgress,
                                        downloadingItem = vmDownloadingItem,
                                        onImportCustomModel = { onImportOpenWakeWordModel() },
                                        refreshTrigger = uiState.refreshTrigger
                                    )
                                }
                                3 -> {
                                    IntegrationsTab(
                                        languageManager = languageManager,
                                        settingsRepo = settingsRepo
                                    )
                                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                                    PipedSettingsSection(
                                        languageManager = languageManager,
                                        settingsRepo = settingsRepo
                                    )
                                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                                    SearchSettingsSection(
                                        languageManager = languageManager,
                                        settingsRepo = settingsRepo
                                    )
                                }
                                4 -> PermissionsSettingsTab(
                                    languageManager = languageManager,
                                    appStateManager = appStateManager,
                                    onRequestMicrophone = onRequestMicrophonePermission,
                                    onRequestNotification = onRequestNotificationPermission,
                                    onRequestOverlay = onRequestOverlayPermission,
                                    onRequestLocation = onRequestLocationPermission
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    SettingsDialogs(
        languageManager = languageManager,
        showCleanupDialog = showCleanupDialog,
        showDeleteConfirmDialog = showDeleteConfirmDialog,
        modelToDelete = modelToDelete,
        onDismissCleanup = { showCleanupDialog = false },
        onConfirmCleanup = { onDeleteUnusedModels(); showCleanupDialog = false; appStateManager.refreshAll() },
        onDismissDelete = { showDeleteConfirmDialog = false; modelToDelete = null },
        onConfirmDelete = {
            modelToDelete?.let { m ->
                onDeleteModel(m.id, m.engineType)
                onRefreshMain()
            }
            showDeleteConfirmDialog = false
            modelToDelete = null
        }
    )
}

@Composable
private fun SettingsDialogs(
    languageManager: LanguageManager,
    showCleanupDialog: Boolean,
    showDeleteConfirmDialog: Boolean,
    modelToDelete: AppModel?,
    onDismissCleanup: () -> Unit,
    onConfirmCleanup: () -> Unit,
    onDismissDelete: () -> Unit,
    onConfirmDelete: () -> Unit
) {
    if (showCleanupDialog) {
        AlertDialog(
            onDismissRequest = onDismissCleanup,
            title = { Text(languageManager.getString("cleanup_unused_title")) },
            text = { Text(languageManager.getString("cleanup_unused_msg")) },
            confirmButton = { TextButton(onClick = onConfirmCleanup, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text(languageManager.getString("delete_button")) } },
            dismissButton = { TextButton(onClick = onDismissCleanup) { Text(languageManager.getString("cancel_button")) } }
        )
    }

    if (showDeleteConfirmDialog && modelToDelete != null) {
        AlertDialog(
            onDismissRequest = onDismissDelete,
            title = { Text(languageManager.getString("confirm_delete_title")) },
            text = { Text(languageManager.getString("confirm_delete_msg").format(modelToDelete.engineType, "${modelToDelete.label} (${modelToDelete.sizeDescription})")) },
            confirmButton = { TextButton(onClick = onConfirmDelete, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text(languageManager.getString("delete_button")) } },
            dismissButton = { TextButton(onClick = onDismissDelete) { Text(languageManager.getString("cancel_button")) } }
        )
    }
}
