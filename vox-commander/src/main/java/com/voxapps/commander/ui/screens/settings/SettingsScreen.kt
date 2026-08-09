package com.voxapps.commander.ui.screens.settings

import com.voxapps.design.picklist.Picklist
import com.voxapps.commander.ui.LocalLanguageManager

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import com.voxapps.commander.data.preferences.SettingsRepository
import com.voxapps.commander.data.remote.RemoteModelRegistry
import com.voxapps.design.VoxDarkMode
import com.voxapps.design.settings.ThemeSettingsScreen
import com.voxapps.design.settings.ThemeSettingsStrings
import com.voxapps.commander.domain.localization.LanguageManager
import com.voxapps.commander.service.WakeWordService
import com.voxapps.commander.domain.model.AppModel
import com.voxapps.commander.state.AppStateManager
import com.voxapps.commander.ui.screens.main.ListeningScreen
import com.voxapps.commander.utils.Strings
import com.voxapps.design.toEnumOr
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun SettingsContent(

    settingsRepo: SettingsRepository,
    appStateManager: AppStateManager,
    modelManagementViewModel: com.voxapps.commander.ui.viewmodels.ModelManagementViewModel,
    onDownloadModel: (String, String, String?) -> Unit,
    onDeleteUnusedModels: () -> Unit,
    onDeleteModel: (String, String) -> Unit,
    onCancelDownload: () -> Unit = {},
    downloadProgress: Float? = null,
    googleSttAvailable: Boolean = true,
    onRequestOverlayPermission: () -> Unit = {},
    onRequestMicrophonePermission: () -> Unit = {},
    onRequestNotificationPermission: () -> Unit = {},
    onRequestLocationPermission: () -> Unit = {},
    onRequestBatteryOptimizationPermission: () -> Unit = {},
    onImportCustomModel: (String?) -> Unit = {},
    onImportOpenWakeWordModel: () -> Unit = {}
) {
        val languageManager = LocalLanguageManager.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // REALTIME STATE - observe AppStateManager uiState for reactive updates
    val uiState by appStateManager.uiState.collectAsStateWithLifecycle()
    
    val pagerState = rememberPagerState(pageCount = { 10 })

    val isVoskLoading by modelManagementViewModel.isVoskLoading.collectAsStateWithLifecycle()
    val isVoskOffline by modelManagementViewModel.isVoskOffline.collectAsStateWithLifecycle()
    val voskError by modelManagementViewModel.voskError.collectAsStateWithLifecycle()
    
    val vmDownloadingItem by modelManagementViewModel.downloadingItem.collectAsStateWithLifecycle()
    val downloadError by modelManagementViewModel.downloadError.collectAsStateWithLifecycle()
    LaunchedEffect(downloadError) {
        downloadError?.let {
            android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_LONG).show()
            modelManagementViewModel.clearDownloadError()
        }
    }


    // Whether the file the user picked became this engine's model, and if not, why. A rejected
    // import leaves the list unchanged, which on its own looks like nothing happened.
    val importResult by modelManagementViewModel.importResult.collectAsStateWithLifecycle()
    // Held here rather than in the result: it is the user editing a choice, not the outcome.
    var importLanguage by remember(importResult) { mutableStateOf<String?>(null) }
    importResult?.let { result ->
        AlertDialog(
            onDismissRequest = { modelManagementViewModel.clearImportResult() },
            title = {
                Text(
                    languageManager.getString(
                        if (result.accepted) "import_accepted_title" else "import_rejected_title"
                    )
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        if (result.accepted) {
                            String.format(languageManager.getString("import_accepted_body"), result.modelName)
                        } else {
                            listOfNotNull(result.modelName, result.detail).joinToString("\n\n")
                        }
                    )

                    // An engine with per-language models cannot tell which language this file is,
                    // and neither can the file. Asked once, here, instead of taken from whichever
                    // filter happened to be set when the picker opened.
                    if (result.accepted && result.languages.isNotEmpty()) {
                        Text(
                            languageManager.getString("import_language_prompt"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Picklist(
                            items = result.languages,
                            selected = importLanguage ?: result.language ?: result.languages.firstOrNull(),
                            itemLabel = { it.uppercase() },
                            onSelect = { importLanguage = it }
                        )
                    }
                }
            },
            confirmButton = {
                // An accepted model that came out of an archive leaves the archive behind — often
                // hundreds of megabytes in Downloads, now duplicated inside the app. Offered, not
                // assumed: it is the user's file and they may want to keep it.
                val source = result.sourceArchive
                val confirm = {
                    importLanguage?.let {
                        modelManagementViewModel.setImportLanguage(result.engineKey, result.language, it)
                    }
                    importLanguage = null
                }
                if (result.accepted && source != null) {
                    TextButton(onClick = {
                        confirm()
                        modelManagementViewModel.deleteImportSource(source)
                    }) {
                        Text(languageManager.getString("import_delete_source"))
                    }
                } else {
                    TextButton(onClick = {
                        confirm()
                        modelManagementViewModel.clearImportResult()
                    }) {
                        Text(languageManager.getString("ok_button"))
                    }
                }
            },
            dismissButton = {
                if (result.accepted && result.sourceArchive != null) {
                    TextButton(onClick = {
                        importLanguage?.let {
                            modelManagementViewModel.setImportLanguage(result.engineKey, result.language, it)
                        }
                        importLanguage = null
                        modelManagementViewModel.clearImportResult()
                    }) {
                        Text(languageManager.getString("import_keep_source"))
                    }
                }
            }
        )
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
                            val tabs = listOf("tab_general", "tab_ai_models", "tab_service", "tab_app_manager", "tab_integrations", "tab_permissions", "tab_advanced", "tab_backup", "tab_theme", "tab_diagnostics")
                            
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
                    if (page == 6) { // Advanced (uses LazyColumn, no scroll wrapper)
                        AdvancedSettingsTab(

                            settingsRepo = settingsRepo,
                            appStateManager = appStateManager,
                            onCleanupRequest = { showCleanupDialog = true },
                            onClearDefaultFallback = {
                                modelManagementViewModel.clearDefaultOfflineFallback()
                            },
                            refreshTrigger = uiState.refreshTrigger
                        )
                    } else if (page == 7) { // Backup & Restore (card only, own scroll wrapper)
                        val settings by settingsRepo.settingsFlow.collectAsStateWithLifecycle(initialValue = settingsRepo.getSettingsSnapshot())
                        Column(
                            modifier = Modifier.fillMaxSize().padding(top = 16.dp).verticalScroll(rememberScrollState())
                        ) {
                            BackupSettingsSection(
                                settingsRepo = settingsRepo,
                                settings = settings,
                                credentials = uiState.credentials
                            )
                        }
                    } else if (page == 9) { // Diagnostics (LazyColumn of its own, no scroll wrapper)
                        // Which native libraries are present, which engine each belongs to, and
                        // whether that engine is the one running. The screen existed and was
                        // reachable from nowhere: nothing referenced this composable, so the status
                        // it renders was computed, published to a flow, and displayed by no one.
                        BenchmarkSettingsTab(
                            appStateManager = appStateManager,
                            refreshTrigger = uiState.refreshTrigger
                        )
                    } else if (page == 8) { // Theme (ThemeSettingsScreen already scrolls itself, no outer scroll wrapper)
                        ThemeSettingsScreen(
                            darkMode = uiState.themeDarkMode.toEnumOr(VoxDarkMode.SYSTEM),
                            colored = uiState.themeColored,
                            onDarkModeChange = { appStateManager.setThemeDarkMode(it.name) },
                            onColoredChange = { appStateManager.setThemeColored(it) },
                            strings = ThemeSettingsStrings(
                                darkModeSectionLabel = languageManager.getString("theme_section"),
                                themeSystemLabel = languageManager.getString("theme_system"),
                                themeLightLabel = languageManager.getString("theme_light"),
                                themeDarkLabel = languageManager.getString("theme_dark"),
                                coloredLabel = languageManager.getString("theme_colored"),
                                coloredDesc = languageManager.getString("theme_colored_desc")
                            ),
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        val focusManager = LocalFocusManager.current
                    Column(modifier = Modifier.fillMaxSize().padding(top = 16.dp).verticalScroll(rememberScrollState()).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = { focusManager.clearFocus() }), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            when (page) {
                                0 -> GeneralSettingsTab(

                                    settingsRepo = settingsRepo,
                                    appStateManager = appStateManager
                                )
                                1 -> ModelsSettingsTab(

                                    settingsRepo = settingsRepo,
                                    appStateManager = appStateManager,
                                    onProcessorSelected = {
                                        appStateManager.setVoiceProcessor(it)
                                    },
                                    googleSttAvailable = googleSttAvailable,
                                    onVoiceLanguageSelected = {
                                        appStateManager.setModelFilterLang(it)
                                    },
                                    onModelSelected = { model: AppModel, isDownloaded: Boolean, langCode: String ->
                                        modelManagementViewModel.selectVoiceModel(model.id, model.engineType, langCode)
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
                                    refreshTrigger = uiState.refreshTrigger
                                )
                                2 -> {
                                    ServiceSettingsTab(

                                        settingsRepo = settingsRepo,
                                        appStateManager = appStateManager,
                                        onStartService = { WakeWordService.startService(context) },
                                        onStopService = { WakeWordService.stopService(context) },
                                        downloadedColor = downloadedColor,
                                        onDownloadModel = onDownloadModel,
                                        onDeleteRequest = { model -> modelToDelete = model as? AppModel; showDeleteConfirmDialog = true },
                                        onCancelDownload = onCancelDownload,
                                        downloadProgress = downloadProgress,
                                        downloadingItem = vmDownloadingItem,
                                        onImportCustomModel = onImportOpenWakeWordModel,
                                        refreshTrigger = uiState.refreshTrigger
                                    )
                                }
                                3 -> AppManagerTab(

                                    settingsRepo = settingsRepo,
                                    appStateManager = appStateManager
                                )
                                4 -> {
                                    IntegrationsTab(

                                        settingsRepo = settingsRepo
                                    )
                                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                                    PipedSettingsSection(

                                        settingsRepo = settingsRepo
                                    )
                                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                                    SearchSettingsSection(
                                        appStateManager = appStateManager,

                                        settingsRepo = settingsRepo
                                    )
                                }
                                5 -> PermissionsSettingsTab(

                                    appStateManager = appStateManager,
                                    onRequestMicrophone = onRequestMicrophonePermission,
                                    onRequestNotification = onRequestNotificationPermission,
                                    onRequestOverlay = onRequestOverlayPermission,
                                    onRequestLocation = onRequestLocationPermission,
                                    onRequestBatteryOptimization = onRequestBatteryOptimizationPermission
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    SettingsDialogs(

        showCleanupDialog = showCleanupDialog,
        showDeleteConfirmDialog = showDeleteConfirmDialog,
        modelToDelete = modelToDelete,
        onDismissCleanup = { showCleanupDialog = false },
        onConfirmCleanup = { onDeleteUnusedModels(); showCleanupDialog = false; appStateManager.refreshAll() },
        onDismissDelete = { showDeleteConfirmDialog = false; modelToDelete = null },
        onConfirmDelete = {
            modelToDelete?.let { m ->
                onDeleteModel(m.id, m.engineType)
            }
            showDeleteConfirmDialog = false
            modelToDelete = null
        }
    )
}

@Composable
private fun SettingsDialogs(

    showCleanupDialog: Boolean,
    showDeleteConfirmDialog: Boolean,
    modelToDelete: AppModel?,
    onDismissCleanup: () -> Unit,
    onConfirmCleanup: () -> Unit,
    onDismissDelete: () -> Unit,
    onConfirmDelete: () -> Unit
) {
        val languageManager = LocalLanguageManager.current
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
