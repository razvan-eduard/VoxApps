package com.voxcommander.app.ui.components

import com.voxcommander.app.ui.LocalLanguageManager

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.voxcommander.app.data.local.dao.FastMapDao
import com.voxcommander.app.data.preferences.SettingsRepository
import com.voxcommander.app.domain.localization.LanguageManager
import com.voxcommander.app.domain.model.AppModel
import com.voxcommander.app.state.AppStateManager
import com.voxcommander.app.ui.screens.rules.RulesManagerContent
import com.voxcommander.app.ui.screens.settings.SettingsContent

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
    modelManagementViewModel: com.voxcommander.app.ui.viewmodels.ModelManagementViewModel,
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
