package com.voxapps.commander.ui.screens.settings

import com.voxapps.design.picklist.Picklist
import com.voxapps.commander.ui.LocalLanguageManager

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.voxapps.commander.data.preferences.SettingsRepository
import com.voxapps.commander.data.remote.RemoteModelRegistry
import com.voxapps.logging.ui.LogViewerStrings
import com.voxapps.design.settings.LogsSettingsTab
import com.voxapps.design.settings.LogsTabStrings
import com.voxapps.design.settings.SettingsMenuDescription
import com.voxapps.design.settings.SettingsSectionHeader
import com.voxapps.commander.domain.localization.LanguageManager
import com.voxapps.commander.service.WakeWordService
import com.voxapps.commander.domain.model.AppModel
import com.voxapps.commander.state.AppStateManager
import com.voxapps.commander.ui.screens.main.ListeningScreen
import com.voxapps.commander.utils.Strings

private enum class SettingsPage {
    MENU, GENERAL, MODELS, SERVICE, APP_MANAGER,
    INTEGRATIONS, INTEGRATIONS_APPS, INTEGRATIONS_MEDIA, INTEGRATIONS_SEARCH,
    PERMISSIONS, ADVANCED, LOGS, DIAGNOSTICS, BACKUP
}

/** Where the back arrow goes from a given page: the integrations subpages return to their own
 *  submenu, everything else to the main menu. */
private fun backTarget(page: SettingsPage): SettingsPage = when (page) {
    SettingsPage.INTEGRATIONS_APPS,
    SettingsPage.INTEGRATIONS_MEDIA,
    SettingsPage.INTEGRATIONS_SEARCH -> SettingsPage.INTEGRATIONS
    else -> SettingsPage.MENU
}

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
    onImportIntentModel: () -> Unit = {},
    onImportOpenWakeWordModel: () -> Unit = {}
) {
        val languageManager = LocalLanguageManager.current
    val context = LocalContext.current

    // REALTIME STATE - observe AppStateManager uiState for reactive updates
    val uiState by appStateManager.uiState.collectAsStateWithLifecycle()

    var page by remember { mutableStateOf(SettingsPage.MENU) }
    // The subpage back arrow and the system back do the same thing; on the menu itself the
    // system back keeps meaning "leave settings", which the caller already handles.
    BackHandler(enabled = page != SettingsPage.MENU) { page = backTarget(page) }

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

    val pageTitle = when (page) {
        SettingsPage.MENU -> languageManager.getString("settings")
        SettingsPage.GENERAL -> languageManager.getString("tab_general")
        SettingsPage.MODELS -> languageManager.getString("tab_ai_models")
        SettingsPage.SERVICE -> languageManager.getString("tab_service")
        SettingsPage.APP_MANAGER -> languageManager.getString("tab_app_manager")
        SettingsPage.INTEGRATIONS -> languageManager.getString("tab_integrations")
        SettingsPage.INTEGRATIONS_APPS -> languageManager.getString("integrations_apps_title")
        SettingsPage.INTEGRATIONS_MEDIA -> languageManager.getString("media_services_section")
        SettingsPage.INTEGRATIONS_SEARCH -> languageManager.getString("search_section")
        SettingsPage.PERMISSIONS -> languageManager.getString("tab_permissions")
        SettingsPage.ADVANCED -> languageManager.getString("tab_advanced")
        SettingsPage.LOGS -> languageManager.getString("logs_settings_title")
        SettingsPage.DIAGNOSTICS -> languageManager.getString("tab_diagnostics")
        SettingsPage.BACKUP -> languageManager.getString("tab_backup")
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
                    color = MaterialTheme.colorScheme.surface
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (page != SettingsPage.MENU) {
                            IconButton(onClick = { page = backTarget(page) }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = languageManager.getString("settings"))
                            }
                        }
                        Text(
                            text = pageTitle,
                            style = MaterialTheme.typography.headlineSmall,
                            modifier = Modifier.padding(
                                start = if (page == SettingsPage.MENU) 16.dp else 0.dp,
                                top = 16.dp, bottom = 16.dp, end = 16.dp
                            )
                        )
                    }
                }
            }
        ) { padding ->
            Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                when (page) {
                    // Same menu shape as the other Vox apps' settings screens: flat section
                    // headers from core/design separating plain ListItem entries, one per page.
                    SettingsPage.MENU -> Column(
                        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                    ) {
                        SettingsSectionHeader(languageManager.getString("settings_section_general"))
                        ListItem(
                            headlineContent = { Text(languageManager.getString("tab_general")) },
                            supportingContent = { SettingsMenuDescription(languageManager.getString("tab_general_menu_desc")) },
                            leadingContent = { Icon(Icons.Default.Tune, contentDescription = null) },
                            modifier = Modifier.fillMaxWidth().clickable { page = SettingsPage.GENERAL }
                        )
                        SettingsSectionHeader(languageManager.getString("settings_section_engines"))
                        ListItem(
                            headlineContent = { Text(languageManager.getString("tab_ai_models")) },
                            supportingContent = { SettingsMenuDescription(languageManager.getString("tab_ai_models_menu_desc")) },
                            leadingContent = { Icon(Icons.Default.Memory, contentDescription = null) },
                            modifier = Modifier.fillMaxWidth().clickable { page = SettingsPage.MODELS }
                        )
                        ListItem(
                            headlineContent = { Text(languageManager.getString("tab_service")) },
                            supportingContent = { SettingsMenuDescription(languageManager.getString("tab_service_menu_desc")) },
                            leadingContent = { Icon(Icons.Default.Mic, contentDescription = null) },
                            modifier = Modifier.fillMaxWidth().clickable { page = SettingsPage.SERVICE }
                        )
                        SettingsSectionHeader(languageManager.getString("settings_section_apps"))
                        ListItem(
                            headlineContent = { Text(languageManager.getString("tab_app_manager")) },
                            supportingContent = { SettingsMenuDescription(languageManager.getString("tab_app_manager_menu_desc")) },
                            leadingContent = { Icon(Icons.Default.Apps, contentDescription = null) },
                            modifier = Modifier.fillMaxWidth().clickable { page = SettingsPage.APP_MANAGER }
                        )
                        ListItem(
                            headlineContent = { Text(languageManager.getString("tab_integrations")) },
                            supportingContent = { SettingsMenuDescription(languageManager.getString("tab_integrations_menu_desc")) },
                            leadingContent = { Icon(Icons.Default.Extension, contentDescription = null) },
                            modifier = Modifier.fillMaxWidth().clickable { page = SettingsPage.INTEGRATIONS }
                        )
                        SettingsSectionHeader(languageManager.getString("settings_section_system"))
                        ListItem(
                            headlineContent = { Text(languageManager.getString("tab_permissions")) },
                            supportingContent = { SettingsMenuDescription(languageManager.getString("tab_permissions_menu_desc")) },
                            leadingContent = { Icon(Icons.Default.Shield, contentDescription = null) },
                            modifier = Modifier.fillMaxWidth().clickable { page = SettingsPage.PERMISSIONS }
                        )
                        ListItem(
                            headlineContent = { Text(languageManager.getString("tab_advanced")) },
                            supportingContent = { SettingsMenuDescription(languageManager.getString("tab_advanced_menu_desc")) },
                            leadingContent = { Icon(Icons.Default.Build, contentDescription = null) },
                            modifier = Modifier.fillMaxWidth().clickable { page = SettingsPage.ADVANCED }
                        )
                        ListItem(
                            headlineContent = { Text(languageManager.getString("logs_settings_title")) },
                            supportingContent = { SettingsMenuDescription(languageManager.getString("logs_settings_menu_desc")) },
                            leadingContent = { Icon(Icons.Default.BugReport, contentDescription = null) },
                            modifier = Modifier.fillMaxWidth().clickable { page = SettingsPage.LOGS }
                        )
                        SettingsSectionHeader(languageManager.getString("settings_section_data"))
                        ListItem(
                            headlineContent = { Text(languageManager.getString("tab_backup")) },
                            supportingContent = { SettingsMenuDescription(languageManager.getString("tab_backup_menu_desc")) },
                            leadingContent = { Icon(Icons.Default.Backup, contentDescription = null) },
                            modifier = Modifier.fillMaxWidth().clickable { page = SettingsPage.BACKUP }
                        )
                        ListItem(
                            headlineContent = { Text(languageManager.getString("tab_diagnostics")) },
                            supportingContent = { SettingsMenuDescription(languageManager.getString("tab_diagnostics_menu_desc")) },
                            leadingContent = { Icon(Icons.Default.Speed, contentDescription = null) },
                            modifier = Modifier.fillMaxWidth().clickable { page = SettingsPage.DIAGNOSTICS }
                        )
                    }
                    // Advanced uses a LazyColumn of its own, no scroll wrapper.
                    SettingsPage.ADVANCED -> Box(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                        AdvancedSettingsTab(

                            settingsRepo = settingsRepo,
                            appStateManager = appStateManager,
                            onCleanupRequest = { showCleanupDialog = true },
                            onClearDefaultFallback = {
                                modelManagementViewModel.clearDefaultOfflineFallback()
                            },
                            refreshTrigger = uiState.refreshTrigger
                        )
                    }
                    SettingsPage.BACKUP -> Box(
                        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp).verticalScroll(rememberScrollState())
                    ) {
                        val settings by settingsRepo.settingsFlow.collectAsStateWithLifecycle(initialValue = settingsRepo.getSettingsSnapshot())
                        BackupSettingsSection(
                            settingsRepo = settingsRepo,
                            settings = settings,
                            credentials = uiState.credentials
                        )
                    }
                    // Benchmarks and the native-library inventory: what the install is made of and
                    // how fast it runs, which is a different question from what gets backed up.
                    SettingsPage.DIAGNOSTICS -> Box(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                        BenchmarkSettingsTab(
                            appStateManager = appStateManager,
                            refreshTrigger = uiState.refreshTrigger
                        )
                    }
                    // The log viewer grows without bound, so it gets a page rather than a card at
                    // the bottom of Advanced.
                    SettingsPage.LOGS -> {
                        val settings by settingsRepo.settingsFlow.collectAsStateWithLifecycle(initialValue = settingsRepo.getSettingsSnapshot())
                        LogsSettingsTab(
                            enabled = settings.debugLoggingEnabled,
                            onEnabledChange = { appStateManager.setDebugLoggingEnabled(it) },
                            toastsEnabled = settings.debugToastsEnabled,
                            onToastsEnabledChange = { appStateManager.setDebugToastsEnabled(it) },
                            strings = LogsTabStrings(
                                sectionLabel = languageManager.getString("logging_section"),
                                enabledLabel = languageManager.getString("debug_logging"),
                                enabledDesc = languageManager.getString("debug_logging_desc"),
                                toastsLabel = languageManager.getString("debug_toasts_label"),
                                viewer = LogViewerStrings(
                                    sectionTitle = languageManager.getString("verbose_logging_section"),
                                    clearLabel = languageManager.getString("clear_logs"),
                                    copyLabel = languageManager.getString("copy_button"),
                                    shareLabel = languageManager.getString("share_button"),
                                    noLogsLabel = languageManager.getString("no_logs")
                                )
                            ),
                            shareSubject = "VoxCommander Logs",
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    // Three unrelated areas that used to be stacked on one page; a submenu instead,
                    // so each opens as its own final screen.
                    SettingsPage.INTEGRATIONS -> Column(
                        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                    ) {
                        ListItem(
                            headlineContent = { Text(languageManager.getString("integrations_apps_title")) },
                            supportingContent = { SettingsMenuDescription(languageManager.getString("integrations_apps_menu_desc")) },
                            leadingContent = { Icon(Icons.Default.Extension, contentDescription = null) },
                            modifier = Modifier.fillMaxWidth().clickable { page = SettingsPage.INTEGRATIONS_APPS }
                        )
                        ListItem(
                            headlineContent = { Text(languageManager.getString("media_services_section")) },
                            supportingContent = { SettingsMenuDescription(languageManager.getString("media_services_menu_desc")) },
                            leadingContent = { Icon(Icons.Default.PlayArrow, contentDescription = null) },
                            modifier = Modifier.fillMaxWidth().clickable { page = SettingsPage.INTEGRATIONS_MEDIA }
                        )
                        ListItem(
                            headlineContent = { Text(languageManager.getString("search_section")) },
                            supportingContent = { SettingsMenuDescription(languageManager.getString("search_menu_desc")) },
                            leadingContent = { Icon(Icons.Default.Search, contentDescription = null) },
                            modifier = Modifier.fillMaxWidth().clickable { page = SettingsPage.INTEGRATIONS_SEARCH }
                        )
                    }
                    else -> {
                        val focusManager = LocalFocusManager.current
                        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp).padding(top = 16.dp).verticalScroll(rememberScrollState()).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = { focusManager.clearFocus() }), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            when (page) {
                                SettingsPage.GENERAL -> GeneralSettingsTab(

                                    settingsRepo = settingsRepo,
                                    appStateManager = appStateManager
                                )
                                SettingsPage.MODELS -> ModelsSettingsTab(

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
                                    onImportIntentModel = onImportIntentModel,
                                    refreshTrigger = uiState.refreshTrigger
                                )
                                SettingsPage.SERVICE -> {
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
                                SettingsPage.APP_MANAGER -> AppManagerTab(

                                    settingsRepo = settingsRepo,
                                    appStateManager = appStateManager
                                )
                                SettingsPage.INTEGRATIONS_APPS -> IntegrationsTab(
                                    settingsRepo = settingsRepo
                                )
                                SettingsPage.INTEGRATIONS_MEDIA -> PipedSettingsSection(
                                    settingsRepo = settingsRepo
                                )
                                SettingsPage.INTEGRATIONS_SEARCH -> SearchSettingsSection(
                                    appStateManager = appStateManager,
                                    settingsRepo = settingsRepo
                                )
                                SettingsPage.PERMISSIONS -> PermissionsSettingsTab(

                                    appStateManager = appStateManager,
                                    onRequestMicrophone = onRequestMicrophonePermission,
                                    onRequestNotification = onRequestNotificationPermission,
                                    onRequestOverlay = onRequestOverlayPermission,
                                    onRequestLocation = onRequestLocationPermission,
                                    onRequestBatteryOptimization = onRequestBatteryOptimizationPermission
                                )
                                else -> {}
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
