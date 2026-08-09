package com.voxapps.commander.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.voxapps.design.picklist.GroupedPicklist
import com.voxapps.design.picklist.GroupedPicklistSheet
import com.voxapps.commander.data.preferences.SettingsRepository
import com.voxapps.commander.domain.localization.LanguageManager
import com.voxapps.commander.ui.LocalLanguageManager
import com.voxapps.commander.domain.model.AppModel
import com.voxapps.commander.state.AppStateManager
import com.voxapps.commander.data.remote.EngineRuntime
import com.voxapps.commander.data.remote.RemoteModelRegistry
import com.voxapps.commander.utils.Strings
import kotlinx.coroutines.launch

/**
 * Universal component for managing ANY engine model (Whisper, Vosk, NLU).
 * Handles: Dropdown selection, IMMEDIATE Download (No Popups), Delete confirmation, and Categorized Fallback.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> EngineModelSection(
    title: String,
    settingsRepo: SettingsRepository,
    appStateManager: AppStateManager,
    /** The caption above the rows, and the rows. There was a list of sections here and every caller
     *  passed exactly one, so the grouping was a shape nothing used. */
    header: String?,
    items: List<T>,
    selectedItem: T?,
    itemLabel: (T) -> String,
    modelIdProvider: (T) -> String,
    onItemSelected: (T, Boolean) -> Unit,
    onDownloadRequest: (T) -> Unit,
    onDeleteRequest: (T) -> Unit,
    onCancelDownload: () -> Unit,
    downloadProgress: Float?,
    downloadingItem: Any?,
    currentProcessor: String,
    fallbackCategory: String = Strings.FallbackCategories.VOICE,
    onFallbackChanged: () -> Unit = {},
    refreshTrigger: Int = 0,
    onShowInfo: (() -> Unit)? = null,
    showFallback: Boolean = true
) {
    val languageManager = LocalLanguageManager.current
    val uiState by appStateManager.uiState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var showSheet by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Preselection priority: stored id -> first on-device model -> first item.
    val allItems = items
    val isOnDevice: (T) -> Boolean = { (it as? AppModel)?.isBuiltIn == true || uiState.isModelDownloaded(modelIdProvider(it)) }
    val firstOnDevice = allItems.firstOrNull(isOnDevice)
    val effectiveSelectedItem = selectedItem ?: firstOnDevice ?: allItems.firstOrNull()

    // Persist the resolved selection back to settings when the stored id is invalid and an
    // on-device model exists — reusing the caller's own onItemSelected (pure persist for all
    // tabs; the STT reload happens reactively). One-shot & convergent: after persist,
    // selectedItem becomes non-null so this won't re-fire. Never auto-persists a not-downloaded
    // item. isDownloaded=true (it's on-device) so no download is triggered.
    LaunchedEffect(selectedItem, items) {
        if (selectedItem == null && firstOnDevice != null) {
            onItemSelected(firstOnDevice, true)
        }
    }

    // 1. Header
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = title, style = MaterialTheme.typography.labelLarge)
        if (onShowInfo != null) {
            IconButton(onClick = onShowInfo) {
                Icon(Icons.Outlined.Info, contentDescription = "Info")
            }
        }
    }

    if (items.isEmpty()) {
        Card(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.1f))
        ) {
            Text(
                text = "No models available for download. Check models.json or repository URL.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(16.dp)
            )
        }
        return
    }

    // 2. Main Dropdown
    GroupedPicklist(
        selectedItem = effectiveSelectedItem,
        itemLabel = itemLabel,
        isDownloaded = { item -> uiState.isModelDownloaded(modelIdProvider(item)) },
        isDefault = { item ->
            item == effectiveSelectedItem
        },
        isBuiltIn = { item ->
            (item as? AppModel)?.isBuiltIn == true
        },
        onDeviceLabel = languageManager.getString("on_device_label"),
        onItemSelected = { item, isDownloaded ->
            onItemSelected(item, isDownloaded)
            if (!isDownloaded) {
                onDownloadRequest(item)
            }
        },
        onExpandedChange = { showSheet = it },
        onDownloadRequest = { item ->
            // Click on arrow from main button: trigger download, but don't force select/close
            onDownloadRequest(item) 
        },
        onDeleteRequest = { onDeleteRequest(it) },
        onCancelDownload = onCancelDownload,
        downloadProgress = downloadProgress,
        downloadingItem = downloadingItem

    )

    // 3. Selection Sheet
    if (showSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSheet = false },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            GroupedPicklistSheet(
                title = title,
                header = header,
                items = items,
                        itemLabel = itemLabel,
                isDownloaded = { item -> uiState.isModelDownloaded(modelIdProvider(item)) },
                isDefault = { item ->
                    item == effectiveSelectedItem
                },
                isBuiltIn = { item ->
                    (item as? AppModel)?.isBuiltIn == true
                },
                onDeviceLabel = languageManager.getString("on_device_label"),
                onItemSelected = { item, isDownloaded ->
                    // Full row click: select model and close sheet
                    onItemSelected(item, isDownloaded)
                    if (!isDownloaded) {
                        onDownloadRequest(item)
                    }
                    showSheet = false
                },
                onDownloadRequest = { item ->
                    // Arrow button click: KEEP SHEET OPEN for multiple downloads
                    onDownloadRequest(item)
                },
                onDeleteRequest = { onDeleteRequest(it) },
                onCancelDownload = onCancelDownload,
                downloadProgress = downloadProgress,
                downloadingItem = downloadingItem

            )
        }
    }

    // 4. Categorized Fallback Logic
    //
    // A fallback is what answers when the primary could not, and the commonest reason it could not
    // is that the network was the problem — so an engine that needs the network is not offered as
    // one. Only a *declared* cloud runtime is excluded: an engine the schema does not describe is
    // left exactly as it is rather than quietly losing its checkbox. The enablement below is
    // `isBuiltIn || isModelDownloaded`, and a virtual model is built-in by definition, so without
    // this every cloud service would offer itself as the offline fallback the moment virtual
    // engines join the registry.
    val servesWithoutNetwork =
        RemoteModelRegistry.runtimeOf(currentProcessor) != EngineRuntime.CLOUD

    if (showFallback && servesWithoutNetwork && effectiveSelectedItem != null) {
        val modelId = modelIdProvider(effectiveSelectedItem)
        val isBuiltIn = (effectiveSelectedItem as? AppModel)?.isBuiltIn == true
        val isDownloaded = isBuiltIn || uiState.isModelDownloaded(modelId)
        
        val defaultProcessor = if (fallbackCategory == Strings.FallbackCategories.VOICE) uiState.defaultVoiceFallbackProcessor else uiState.defaultIntentFallbackProcessor
        val defaultModelId = if (fallbackCategory == Strings.FallbackCategories.VOICE) uiState.defaultVoiceFallbackModel else uiState.defaultIntentFallbackModel
        
        val isDefault = defaultProcessor == currentProcessor && defaultModelId == modelId
        var showChangeDialog by remember { mutableStateOf(false) }

        Surface(
            onClick = {
                if (isDownloaded) {
                    if (!isDefault) {
                        if (defaultProcessor != null && defaultModelId != null && defaultModelId != modelId) {
                            showChangeDialog = true
                        } else {
                            if (fallbackCategory == Strings.FallbackCategories.VOICE) scope.launch { settingsRepo.setDefaultVoiceFallback(currentProcessor, modelId) }
                            else scope.launch { settingsRepo.setDefaultIntentFallback(currentProcessor, modelId) }
                            onFallbackChanged()
                        }
                    } else {
                        if (fallbackCategory == Strings.FallbackCategories.VOICE) scope.launch { settingsRepo.clearDefaultVoiceFallback() }
                        else scope.launch { settingsRepo.clearDefaultIntentFallback() }
                        onFallbackChanged()
                    }
                }
            },
            enabled = isDownloaded,
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            shape = MaterialTheme.shapes.small,
            border = BorderStroke(
                width = if (isDefault) 2.dp else 1.dp,
                color = if (!isDownloaded) MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                        else if (isDefault) MaterialTheme.colorScheme.primary 
                        else MaterialTheme.colorScheme.outline
            ),
            color = if (!isDownloaded) MaterialTheme.colorScheme.surface.copy(alpha = 0.5f)
                    else if (isDefault) MaterialTheme.colorScheme.primaryContainer 
                    else MaterialTheme.colorScheme.surface
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(6.dp)) {
                Checkbox(checked = isDefault, onCheckedChange = null, enabled = isDownloaded)
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = languageManager.getString("default_offline_fallback_model"),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isDownloaded) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                )
            }
        }

        if (showChangeDialog) {
            AlertDialog(
                onDismissRequest = { showChangeDialog = false },
                title = { Text(languageManager.getString("change_default_model_title")) },
                text = { Text(languageManager.getString("change_default_model_message").format(defaultModelId, modelId)) },
                confirmButton = {
                    Button(onClick = {
                        if (fallbackCategory == "voice") scope.launch { settingsRepo.setDefaultVoiceFallback(currentProcessor, modelId) }
                        else scope.launch { settingsRepo.setDefaultIntentFallback(currentProcessor, modelId) }
                        onFallbackChanged()
                        showChangeDialog = false
                    }) { Text(languageManager.getString("change")) }
                },
                dismissButton = { Button(onClick = { showChangeDialog = false }) { Text(languageManager.getString("cancel_button")) } }
            )
        }
    }
}
