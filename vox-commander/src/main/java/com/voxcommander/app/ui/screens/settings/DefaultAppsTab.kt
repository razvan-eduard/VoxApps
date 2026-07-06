package com.voxcommander.app.ui.screens.settings

import com.voxcommander.app.ui.LocalLanguageManager

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.voxcommander.app.data.preferences.SettingsRepository
import com.voxcommander.app.domain.intent.registry.AppRegistry
import com.voxcommander.app.domain.intent.taxonomy.IntentTaxonomy
import com.voxcommander.app.domain.localization.LanguageManager
import com.voxcommander.app.service.MediaSessionListenerService
import com.voxcommander.app.ui.components.AppSelectorDropdown
import kotlinx.coroutines.launch

private data class DomainInfo(
    val name: String,
    val description: String,
    val isCustom: Boolean = false
)

@Composable
fun DefaultAppsTab(

    settingsRepo: SettingsRepository,
    appStateManager: com.voxcommander.app.state.AppStateManager
) {
        val languageManager = LocalLanguageManager.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by settingsRepo.settingsFlow.collectAsStateWithLifecycle(initialValue = settingsRepo.getSettingsSnapshot())

    val builtInDomains = listOf(
        DomainInfo(IntentTaxonomy.Domains.AUDIO, "Music & media playback"),
        DomainInfo(IntentTaxonomy.Domains.MAPS, "Navigation & maps"),
        DomainInfo(IntentTaxonomy.Domains.MESSAGING, "Messages & communication")
    )
    val customDomains = settings.customDomains.map { DomainInfo(it, "Custom category", isCustom = true) }
    val allDomains = builtInDomains + customDomains

    var allApps by remember { mutableStateOf(AppRegistry.getAllInstalledAppEntries(context)) }
    val appScanState by appStateManager.appScanState.collectAsStateWithLifecycle()
    val isScanning = appScanState is com.voxcommander.app.state.AppScanState.Scanning

    var showAddCategoryDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = languageManager.getString("default_apps_description"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp)
        )

        allDomains.forEach { domainInfo ->
            Column {
                AppSelectorDropdown(
                    selectedPackages = settings.domainAppPackages[domainInfo.name] ?: emptyList(),
                    defaultPackage = settings.defaultAppPackages[domainInfo.name],
                    onToggleApp = { pkg ->
                        val current = (settings.domainAppPackages[domainInfo.name] ?: emptyList()).toMutableList()
                        if (pkg in current) current.remove(pkg) else current.add(pkg)
                        scope.launch { settingsRepo.setDomainApps(domainInfo.name, current) }
                    },
                    onSetDefault = { pkg ->
                        scope.launch { settingsRepo.setDefaultAppPackage(domainInfo.name, pkg) }
                    },
                    domain = null,
                    label = domainInfo.name.replaceFirstChar { it.uppercase() } + if (domainInfo.isCustom) " (custom)" else "",
                    filterMode = settings.domainAppFilters[domainInfo.name] ?: "all",
                    extraPackages = emptyList()

                )
                if (domainInfo.isCustom) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = { scope.launch { settingsRepo.removeCustomDomain(domainInfo.name) } }) {
                            Icon(Icons.Default.Delete, contentDescription = languageManager.getString("delete_category"), tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(languageManager.getString("delete_category"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }

        // Rescan apps button
        OutlinedButton(
            onClick = {
                appStateManager.startAppScan()
            },
            enabled = !isScanning,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (isScanning) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp
                )
            } else {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(8.dp))
            Text(if (isScanning) languageManager.getString("scanning") else languageManager.getString("rescan_apps"))
        }

        // Refresh app list when scan completes
        LaunchedEffect(appScanState) {
            if (appScanState is com.voxcommander.app.state.AppScanState.Done) {
                allApps = AppRegistry.getAllInstalledAppEntries(context)
            }
        }

        // Add custom category button
        OutlinedButton(
            onClick = { showAddCategoryDialog = true },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(languageManager.getString("add_custom_category"))
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

        // Media session permission
        val hasMediaSessionPermission = remember { MediaSessionListenerService.isPermissionGranted(context) }
        var permissionGranted by remember { mutableStateOf(hasMediaSessionPermission) }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = languageManager.getString("media_session_permission"),
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = languageManager.getString("media_session_permission_desc"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(8.dp))
            Switch(
                checked = permissionGranted,
                onCheckedChange = {
                    MediaSessionListenerService.requestPermission(context)
                }
            )
        }
    }

    if (showAddCategoryDialog) {
        AddCustomCategoryDialog(
            onDismiss = { showAddCategoryDialog = false },
            onConfirm = { name ->
                scope.launch {
                    settingsRepo.addCustomDomain(name)
                    showAddCategoryDialog = false
                }
            }

        )
    }
}

@Composable
private fun AddCustomCategoryDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
        val languageManager = LocalLanguageManager.current
    var name by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(languageManager.getString("add_custom_category")) },
        text = {
            Column {
                Text(
                    text = languageManager.getString("add_custom_category_desc"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.lowercase().replace(" ", "_") },
                    label = { Text(languageManager.getString("category_name")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (name.isNotBlank()) onConfirm(name.trim()) },
                enabled = name.isNotBlank()
            ) { Text(languageManager.getString("add_button")) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(languageManager.getString("cancel_button")) }
        }
    )
}
