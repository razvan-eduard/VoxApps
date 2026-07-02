package com.voxcommander.app.ui.screens.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import com.voxcommander.app.data.preferences.AppAliasRule
import com.voxcommander.app.data.preferences.SettingsRepository
import com.voxcommander.app.domain.intent.registry.AppRegistry
import com.voxcommander.app.domain.localization.LanguageManager
import com.voxcommander.app.service.MediaSessionListenerService
import com.voxcommander.app.state.AppStateManager
import com.voxcommander.app.ui.components.AppSelectorDropdown
import kotlinx.coroutines.launch
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppManagerTab(
    languageManager: LanguageManager,
    settingsRepo: SettingsRepository,
    appStateManager: AppStateManager
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val uiState by appStateManager.uiState.collectAsStateWithLifecycle()
    val settings by settingsRepo.settingsFlow.collectAsStateWithLifecycle(initialValue = settingsRepo.getSettingsSnapshot())

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // --- SECTION 1: Default Apps (moved from ServiceSettingsTab) ---
        Text(
            text = languageManager.getString("default_apps_description") ?: "Select which apps VoxCommander can use and set defaults per category.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        DefaultAppsContent(
            languageManager = languageManager,
            settingsRepo = settingsRepo,
            appStateManager = appStateManager
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        // --- SECTION 2: App Alias Manager ---
        Text(
            text = languageManager.getString("app_alias_manager_title"),
            style = MaterialTheme.typography.titleMedium
        )
        Text(
            text = languageManager.getString("app_alias_manager_desc"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        AppAliasManagerSection(
            aliasRules = uiState.appAliasRules,
            onAddRule = { rule ->
                val updated = (settings.appAliasRules + rule)
                scope.launch { settingsRepo.setAppAliasRules(updated) }
            },
            onUpdateRule = { rule ->
                val updated = settings.appAliasRules.map { if (it.id == rule.id) rule else it }
                scope.launch { settingsRepo.setAppAliasRules(updated) }
            },
            onDeleteRule = { ruleId ->
                val updated = settings.appAliasRules.filter { it.id != ruleId }
                scope.launch { settingsRepo.setAppAliasRules(updated) }
            },
            languageManager = languageManager
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        // --- SECTION 3: Media Session Permission (moved from DefaultAppsTab) ---
        val hasMediaSessionPermission = remember { MediaSessionListenerService.isPermissionGranted(context) }
        var permissionGranted by remember { mutableStateOf(hasMediaSessionPermission) }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = languageManager.getString("media_session_permission") ?: "Media session control",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = languageManager.getString("media_session_permission_desc") ?: "Allow VoxCommander to control media playback",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(8.dp))
            Switch(
                checked = permissionGranted,
                onCheckedChange = { MediaSessionListenerService.requestPermission(context) }
            )
        }
    }
}

// --- DEFAULT APPS CONTENT (moved from DefaultAppsTab) ---

@Composable
private fun DefaultAppsContent(
    languageManager: LanguageManager,
    settingsRepo: SettingsRepository,
    appStateManager: AppStateManager
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by settingsRepo.settingsFlow.collectAsStateWithLifecycle(initialValue = settingsRepo.getSettingsSnapshot())

    val builtInDomains = listOf(
        Triple("audio", "Music & media playback", false),
        Triple("maps", "Navigation & maps", false),
        Triple("messaging", "Messages & communication", false)
    )
    val customDomains = settings.customDomains.map { Triple(it, "Custom category", true) }
    val allDomains = builtInDomains + customDomains

    val appScanState by appStateManager.appScanState.collectAsStateWithLifecycle()
    val isScanning = appScanState is com.voxcommander.app.state.AppScanState.Scanning

    var showAddCategoryDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        allDomains.forEach { (domainName, _, isCustom) ->
            Column {
                AppSelectorDropdown(
                    selectedPackages = settings.domainAppPackages[domainName] ?: emptyList(),
                    defaultPackage = settings.defaultAppPackages[domainName],
                    onToggleApp = { pkg ->
                        val current = (settings.domainAppPackages[domainName] ?: emptyList()).toMutableList()
                        if (pkg in current) current.remove(pkg) else current.add(pkg)
                        scope.launch { settingsRepo.setDomainApps(domainName, current) }
                    },
                    onSetDefault = { pkg ->
                        scope.launch { settingsRepo.setDefaultAppPackage(domainName, pkg) }
                    },
                    domain = null,
                    label = domainName.replaceFirstChar { it.uppercase() } + if (isCustom) " (custom)" else "",
                    filterMode = settings.domainAppFilters[domainName] ?: "all",
                    extraPackages = emptyList(),
                    languageManager = languageManager
                )
                if (isCustom) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = { scope.launch { settingsRepo.removeCustomDomain(domainName) } }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete category", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Delete category", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }

        OutlinedButton(
            onClick = { appStateManager.startAppScan() },
            enabled = !isScanning,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (isScanning) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            } else {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(8.dp))
            Text(if (isScanning) (languageManager.getString("scanning") ?: "Scanning...") else (languageManager.getString("rescan_apps") ?: "Rescan apps"))
        }

        LaunchedEffect(appScanState) {
            if (appScanState is com.voxcommander.app.state.AppScanState.Done) {
                AppRegistry.getAllInstalledAppEntries(context)
            }
        }

        OutlinedButton(
            onClick = { showAddCategoryDialog = true },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(languageManager.getString("add_custom_category") ?: "Add custom category")
        }
    }

    if (showAddCategoryDialog) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAddCategoryDialog = false },
            title = { Text(languageManager.getString("add_custom_category") ?: "Add custom category") },
            text = {
                Column {
                    Text(
                        text = languageManager.getString("add_custom_category_desc") ?: "Enter a category name (lowercase, no spaces).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it.lowercase().replace(" ", "_") },
                        label = { Text(languageManager.getString("category_name") ?: "Category name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (name.isNotBlank()) {
                            scope.launch {
                                settingsRepo.addCustomDomain(name.trim())
                                showAddCategoryDialog = false
                            }
                        }
                    },
                    enabled = name.isNotBlank()
                ) { Text(languageManager.getString("add_button") ?: "Add") }
            },
            dismissButton = {
                TextButton(onClick = { showAddCategoryDialog = false }) {
                    Text(languageManager.getString("cancel_button"))
                }
            }
        )
    }
}

// --- APP ALIAS MANAGER ---

@Composable
private fun AppAliasManagerSection(
    aliasRules: List<AppAliasRule>,
    onAddRule: (AppAliasRule) -> Unit,
    onUpdateRule: (AppAliasRule) -> Unit,
    onDeleteRule: (String) -> Unit,
    languageManager: LanguageManager
) {
    var showAddDialog by remember { mutableStateOf(false) }
    var editingRule by remember { mutableStateOf<AppAliasRule?>(null) }
    var pendingDeleteRule by remember { mutableStateOf<AppAliasRule?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (aliasRules.isEmpty()) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = languageManager.getString("app_alias_empty"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp)
                )
            }
        } else {
            aliasRules.forEach { rule ->
                AppAliasRuleCard(
                    rule = rule,
                    onEdit = { editingRule = rule },
                    onDelete = { pendingDeleteRule = rule },
                    onToggle = { onUpdateRule(rule.copy(enabled = it)) }
                )
            }
        }

        OutlinedButton(
            onClick = { showAddDialog = true },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(languageManager.getString("app_alias_add"))
        }
    }

    if (showAddDialog) {
        AppAliasEditDialog(
            rule = null,
            onDismiss = { showAddDialog = false },
            onSave = { newRule ->
                onAddRule(newRule)
                showAddDialog = false
            },
            languageManager = languageManager
        )
    }

    if (editingRule != null) {
        AppAliasEditDialog(
            rule = editingRule,
            onDismiss = { editingRule = null },
            onSave = { updated ->
                onUpdateRule(updated)
                editingRule = null
            },
            languageManager = languageManager
        )
    }

    pendingDeleteRule?.let { rule ->
        AlertDialog(
            onDismissRequest = { pendingDeleteRule = null },
            title = { Text(languageManager.getString("app_alias_delete_title")) },
            text = {
                Text("Delete alias rule for \"${rule.displayName}\"? Aliases: ${rule.aliases.joinToString(", ")}")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteRule(rule.id)
                        pendingDeleteRule = null
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text(languageManager.getString("delete_button")) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteRule = null }) {
                    Text(languageManager.getString("cancel_button"))
                }
            }
        )
    }
}

@Composable
private fun AppAliasRuleCard(
    rule: AppAliasRule,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onToggle: (Boolean) -> Unit
) {
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = rule.displayName,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = rule.packageName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Switch(checked = rule.enabled, onCheckedChange = onToggle)
            }

            Spacer(Modifier.height(8.dp))

            // Alias chips
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                rule.aliases.forEach { alias ->
                    AssistChip(
                        onClick = {},
                        label = { Text(alias, style = MaterialTheme.typography.labelSmall) }
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onEdit) {
                    Icon(Icons.Default.Edit, contentDescription = "Edit", modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Edit", style = MaterialTheme.typography.labelSmall)
                }
                Spacer(Modifier.width(4.dp))
                TextButton(
                    onClick = onDelete,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete", modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Delete", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AppAliasEditDialog(
    rule: AppAliasRule?,
    onDismiss: () -> Unit,
    onSave: (AppAliasRule) -> Unit,
    languageManager: LanguageManager
) {
    var selectedPackage by remember { mutableStateOf(rule?.packageName) }
    var aliases by remember { mutableStateOf(rule?.aliases?.joinToString(", ") ?: "") }
    var aliasList by remember { mutableStateOf(rule?.aliases ?: emptyList()) }

    val allApps = remember { AppRegistry.allInstalledApps() }
    val selectedApp = remember(selectedPackage, allApps) {
        allApps.find { it.packageName == selectedPackage }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            tonalElevation = 6.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    text = if (rule == null)
                        languageManager.getString("app_alias_add_title")
                    else
                        languageManager.getString("app_alias_edit_title"),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                // App selector (single-select variant)
                AppSelectorDropdown(
                    selectedPackage = selectedPackage,
                    onAppSelected = { entry ->
                        selectedPackage = entry?.packageName
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = languageManager.getString("app_alias_select_app"),
                    allowNone = false,
                    languageManager = languageManager,
                    maxDropdownHeight = 200.dp
                )

                Spacer(Modifier.height(12.dp))

                // Current aliases display
                if (aliasList.isNotEmpty()) {
                    Text(
                        text = languageManager.getString("app_alias_current"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        aliasList.forEach { alias ->
                            InputChip(
                                selected = false,
                                onClick = {
                                    aliasList = aliasList - alias
                                },
                                label = { Text(alias, style = MaterialTheme.typography.labelSmall) },
                                trailingIcon = {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = "Remove alias",
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }

                // Add alias input
                OutlinedTextField(
                    value = aliases,
                    onValueChange = { aliases = it },
                    label = { Text(languageManager.getString("app_alias_input_label")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(modifier = Modifier.padding(top = 8.dp)) {
                    OutlinedButton(
                        onClick = {
                            val trimmed = aliases.trim()
                            if (trimmed.isNotBlank() && trimmed.lowercase() !in aliasList) {
                                aliasList = aliasList + trimmed.lowercase()
                                aliases = ""
                            }
                        },
                        enabled = aliases.isNotBlank() && selectedPackage != null
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(languageManager.getString("app_alias_add_btn"))
                    }
                }

                Spacer(Modifier.height(16.dp))

                // Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(languageManager.getString("cancel_button"))
                    }
                    Spacer(Modifier.width(8.dp))
                    TextButton(
                        onClick = {
                            val finalAliases = aliasList.toMutableList()
                            val trimmed = aliases.trim()
                            if (trimmed.isNotBlank() && trimmed.lowercase() !in finalAliases) {
                                finalAliases.add(trimmed.lowercase())
                            }
                            if (selectedPackage != null && finalAliases.isNotEmpty()) {
                                val displayName = selectedApp?.displayName ?: selectedPackage!!
                                onSave(
                                    AppAliasRule(
                                        id = rule?.id ?: UUID.randomUUID().toString(),
                                        packageName = selectedPackage!!,
                                        displayName = displayName,
                                        aliases = finalAliases,
                                        enabled = rule?.enabled ?: true
                                    )
                                )
                            }
                        },
                        enabled = selectedPackage != null && (aliasList.isNotEmpty() || aliases.isNotBlank())
                    ) { Text(languageManager.getString("save_button")) }
                }
            }
        }
    }
}
