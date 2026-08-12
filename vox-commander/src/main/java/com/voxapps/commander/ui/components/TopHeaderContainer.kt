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
import kotlinx.coroutines.launch

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
    onImportIntentModel: () -> Unit = {},
    onImportOpenWakeWordModel: () -> Unit = {}
) {
        val languageManager = LocalLanguageManager.current
    if (mode == TopHeaderMode.NONE) return

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    // Set by RulesManagerContent's onChangesDetected while an in-progress (not-yet-saved) rule
    // draft exists — gates the discard-confirmation prompt below so swipe-to-dismiss/tap-outside
    // doesn't silently throw away several taps' worth of trigger/query word selection.
    var hasUnsavedRuleChanges by remember { mutableStateOf(false) }
    var canSaveRuleChanges by remember { mutableStateOf(false) }
    var saveRuleAction by remember { mutableStateOf<(suspend () -> Unit)?>(null) }
    var showDiscardConfirmation by remember { mutableStateOf(false) }

    val requestDismiss: () -> Unit = {
        if (mode == TopHeaderMode.RULES && hasUnsavedRuleChanges) {
            // The sheet has already animated to Hidden by the time onDismissRequest fires (Material3
            // ModalBottomSheet has no way to veto an in-progress swipe), so re-show it while asking
            // for confirmation rather than trying to cancel a gesture that already completed.
            scope.launch { sheetState.show() }
            showDiscardConfirmation = true
        } else {
            onDismissRequest()
        }
    }

    if (showDiscardConfirmation) {
        AlertDialog(
            onDismissRequest = { showDiscardConfirmation = false },
            title = { Text(languageManager.getString("discard_rule_title")) },
            text = { Text(languageManager.getString("discard_rule_message")) },
            confirmButton = {
                TextButton(
                    enabled = canSaveRuleChanges,
                    onClick = {
                        showDiscardConfirmation = false
                        hasUnsavedRuleChanges = false
                        scope.launch {
                            saveRuleAction?.invoke()
                            sheetState.hide()
                            onDismissRequest()
                        }
                    }
                ) { Text(languageManager.getString("save_and_close_button")) }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { showDiscardConfirmation = false }) {
                        Text(languageManager.getString("cancel_button"))
                    }
                    TextButton(onClick = {
                        showDiscardConfirmation = false
                        hasUnsavedRuleChanges = false
                        scope.launch { sheetState.hide() }.invokeOnCompletion { onDismissRequest() }
                    }) { Text(languageManager.getString("discard_button")) }
                }
            }
        )
    }

    ModalBottomSheet(
        onDismissRequest = requestDismiss,
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
                        onImportIntentModel = onImportIntentModel,
                        onImportOpenWakeWordModel = onImportOpenWakeWordModel
                    )
                }
                TopHeaderMode.RULES -> {
                    RulesManagerContent(
                        settingsRepo = settingsRepo,
                        appStateManager = appStateManager,
                        fastMapDao = fastMapDao,
                        onChangesDetected = { hasUnsavedRuleChanges = it },
                        onSaveAvailabilityChanged = { canSaveRuleChanges = it },
                        onSaveRequested = { saveRuleAction = it }
                    )
                }
                else -> {}
            }
        }
    }
}
