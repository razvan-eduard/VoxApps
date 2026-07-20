package com.voxapps.commander.ui.components

import com.voxapps.commander.ui.LocalLanguageManager

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.voxapps.commander.data.local.dao.FastMapDao
import com.voxapps.commander.data.preferences.SettingsRepository
import com.voxapps.commander.domain.localization.LanguageManager
import com.voxapps.commander.domain.model.AppModel
import com.voxapps.commander.state.AppStateManager
import com.voxapps.commander.ui.screens.rules.RulesManagerContent
import com.voxapps.commander.ui.screens.settings.SettingsContent

enum class TopHeaderMode {
    NONE, SETTINGS, RULES
}

/**
 * Unified FULLSCREEN container for all management overlays.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TopHeaderContainer(
    mode: TopHeaderMode,

    settingsRepo: SettingsRepository,
    appStateManager: AppStateManager,
    modelManagementViewModel: com.voxapps.commander.ui.viewmodels.ModelManagementViewModel,
    fastMapDao: FastMapDao,
    onDismissRequest: () -> Unit,
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
    onClearCustomModel: () -> Unit = {},
    onImportOpenWakeWordModel: () -> Unit = {}
) {
        val languageManager = LocalLanguageManager.current
    if (mode == TopHeaderMode.NONE) return

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        scrimColor = Color.Black.copy(alpha = 0.32f),
        dragHandle = null,
        modifier = Modifier.fillMaxSize()
    ) {
        key(mode) {
            when (mode) {
                TopHeaderMode.SETTINGS -> {
                    SettingsContent(

                        settingsRepo = settingsRepo,
                        appStateManager = appStateManager,
                        modelManagementViewModel = modelManagementViewModel,
                        onDownloadModel = onDownloadModel,
                        onDeleteUnusedModels = onDeleteUnusedModels,
                        onDeleteModel = onDeleteModel,
                        onCancelDownload = onCancelDownload,
                        downloadProgress = downloadProgress,
                        googleSttAvailable = googleSttAvailable,
                        onRequestOverlayPermission = onRequestOverlayPermission,
                        onRequestMicrophonePermission = onRequestMicrophonePermission,
                        onRequestNotificationPermission = onRequestNotificationPermission,
                        onRequestLocationPermission = onRequestLocationPermission,
                        onRequestBatteryOptimizationPermission = onRequestBatteryOptimizationPermission,
                        onImportCustomModel = onImportCustomModel,
                        onClearCustomModel = onClearCustomModel,
                        onImportOpenWakeWordModel = onImportOpenWakeWordModel
                    )
                }
                TopHeaderMode.RULES -> {
                    RulesManagerContent(

                        settingsRepo = settingsRepo,
                        appStateManager = appStateManager,
                        fastMapDao = fastMapDao,
                        onSaveAndClose = onDismissRequest
                    )
                }
                else -> {}
            }
        }
    }
}
