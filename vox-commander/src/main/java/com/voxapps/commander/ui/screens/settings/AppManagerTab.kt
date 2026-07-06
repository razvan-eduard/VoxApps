package com.voxapps.commander.ui.screens.settings

import com.voxapps.commander.ui.LocalLanguageManager

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import com.voxapps.commander.data.preferences.AppAliasRule
import com.voxapps.commander.data.preferences.SettingsRepository
import com.voxapps.commander.domain.intent.registry.AppRegistry
import com.voxapps.commander.domain.localization.LanguageManager
import com.voxapps.commander.service.MediaSessionListenerService
import com.voxapps.commander.state.AppStateManager
import com.voxapps.commander.ui.components.AppSelectorDropdown
import kotlinx.coroutines.launch
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppManagerTab(

    settingsRepo: SettingsRepository,
    appStateManager: AppStateManager
) {
        val languageManager = LocalLanguageManager.current
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
            }

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

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        // --- SECTION 4: Return to previous app after action ---
        var returnApps by remember { mutableStateOf(settingsRepo.getReturnAfterActionAppsSync()) }

        Text(
            text = "Return to previous app after action",
            style = MaterialTheme.typography.titleMedium
        )
        Text(
            text = "Select apps that should automatically return you to the previous app after executing a command.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp)
        )
        AppSelectorDropdown(
            selectedPackages = returnApps,
            defaultPackage = null,
            onToggleApp = { pkg ->
                val updated = if (pkg in returnApps) returnApps - pkg else returnApps + pkg
                returnApps = updated
                scope.launch { settingsRepo.setReturnAfterActionApps(updated) }
            },
            onSetDefault = {},
            label = "Apps (${returnApps.size} selected)"

        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        // --- SECTION 5: External Voice Trigger (MacroDroid, Tasker, etc.) ---
        var externalTriggerEnabled by remember { mutableStateOf(settingsRepo.getExternalTriggerEnabledSync()) }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "External voice trigger",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "Allow automation apps (MacroDroid, Tasker) to trigger voice assistant via broadcast intent: com.voxapps.commander.TRIGGER_VOICE",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(8.dp))
            Switch(
                checked = externalTriggerEnabled,
                onCheckedChange = {
                    externalTriggerEnabled = it
                    scope.launch { settingsRepo.setExternalTriggerEnabled(it) }
                }
            )
        }
    }
}

// --- DEFAULT APPS CONTENT (moved from DefaultAppsTab) ---

@Composable
private fun DefaultAppsContent(

    settingsRepo: SettingsRepository,
    appStateManager: AppStateManager
) {
        val languageManager = LocalLanguageManager.current
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
    val isScanning = appScanState is com.voxapps.commander.state.AppScanState.Scanning

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
                    extraPackages = emptyList()

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
            if (appScanState is com.voxapps.commander.state.AppScanState.Done) {
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
    onDeleteRule: (String) -> Unit
) {
        val languageManager = LocalLanguageManager.current
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
            }

        )
    }

    if (editingRule != null) {
        AppAliasEditDialog(
            rule = editingRule,
            onDismiss = { editingRule = null },
            onSave = { updated ->
                onUpdateRule(updated)
                editingRule = null
            }

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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AppAliasRuleCard(
    rule: AppAliasRule,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onToggle: (Boolean) -> Unit
) {
    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onEdit() },
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
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

                // Alias chips
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    rule.aliases.forEach { alias ->
                        AssistChip(
                            onClick = {},
                            label = { Text(alias, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }

                Switch(
                    checked = rule.enabled,
                    onCheckedChange = onToggle,
                    modifier = Modifier.scale(0.8f)
                )

                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Delete",
                        tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                        modifier = Modifier.size(18.dp)
                    )
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
    onSave: (AppAliasRule) -> Unit
) {
        val languageManager = LocalLanguageManager.current
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
                            val pkg = selectedPackage
                            if (pkg != null && finalAliases.isNotEmpty()) {
                                val displayName = selectedApp?.displayName ?: pkg
                                onSave(
                                    AppAliasRule(
                                        id = rule?.id ?: UUID.randomUUID().toString(),
                                        packageName = pkg,
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
