package com.voxcommander.app.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.voxcommander.app.domain.intent.registry.AppRegistry
import com.voxcommander.app.domain.localization.LanguageManager
import com.voxcommander.app.service.SpotifyRemoteManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Reusable app picker with inline expand (same pattern as DefaultApps domain cards).
 * Header shows selected app; tap to expand search + filter + scrollable app list.
 *
 * Single-select variant: pick one app (or none).
 */
@Composable
fun AppSelectorDropdown(
    selectedPackage: String?,
    onAppSelected: (AppRegistry.AppEntry?) -> Unit,
    modifier: Modifier = Modifier,
    domain: String? = null,
    label: String = "Select app",
    allowNone: Boolean = true,
    extraPackages: List<String> = emptyList(),
    languageManager: LanguageManager? = null,
    maxDropdownHeight: androidx.compose.ui.unit.Dp = 300.dp
) {
    val lm = languageManager
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showSpotifyOAuthDialog by remember { mutableStateOf(false) }
    var spotifyOAuthAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    val allApps = remember(domain, extraPackages) {
        val domainApps = if (domain != null) {
            AppRegistry.getInstalledAppsForDomain(domain)
        } else {
            AppRegistry.allInstalledApps()
        }
        if (extraPackages.isEmpty()) {
            domainApps
        } else {
            val existingPkgs = domainApps.map { it.packageName }.toSet()
            val extraApps = AppRegistry.allInstalledApps().filter {
                it.packageName in extraPackages && it.packageName !in existingPkgs
            }
            (domainApps + extraApps).sortedBy { it.displayName.lowercase() }
        }
    }

    val selectedApp = remember(selectedPackage, allApps) {
        allApps.find { it.packageName == selectedPackage }
    }

    var expanded by remember { mutableStateOf(false) }

    OutlinedCard(
        modifier = modifier.fillMaxWidth().animateContentSize(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = if (selectedApp != null) selectedApp.displayName
                               else if (allowNone) (lm?.getString("none_label") ?: "None")
                               else (lm?.getString("not_selected") ?: "Not selected"),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (selectedApp != null) {
                        Text(
                            text = selectedApp.packageName,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) (lm?.getString("collapse") ?: "Collapse") else (lm?.getString("expand") ?: "Expand"),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (expanded) {
                HorizontalDivider()
                AppPickerList(
                    apps = allApps,
                    selectedPackage = selectedPackage,
                    allowNone = allowNone,
                    onSelect = { app ->
                        if (app?.packageName == com.voxcommander.app.utils.PackageNames.SPOTIFY && !SpotifyRemoteManager.isConnected) {
                            spotifyOAuthAction = { onAppSelected(app); expanded = false }
                            showSpotifyOAuthDialog = true
                        } else {
                            onAppSelected(app)
                            expanded = false
                        }
                    },
                    languageManager = lm,
                    maxHeight = maxDropdownHeight
                )
            }
        }
    }

    SpotifyOAuthDialog(
        show = showSpotifyOAuthDialog,
        onDismiss = { showSpotifyOAuthDialog = false },
        onConnect = {
            showSpotifyOAuthDialog = false
            scope.launch {
                withContext(Dispatchers.IO) {
                    SpotifyRemoteManager.connect(context)
                }
                spotifyOAuthAction?.invoke()
            }
        },
        onSkip = {
            showSpotifyOAuthDialog = false
            spotifyOAuthAction?.invoke()
        },
        languageManager = lm
    )
}

/**
 * Multi-select variant with default-star support.
 * Used in DefaultAppsTab — checkboxes for selection, star for default.
 */
@Composable
fun AppSelectorDropdown(
    selectedPackages: List<String>,
    defaultPackage: String?,
    onToggleApp: (String) -> Unit,
    onSetDefault: (String) -> Unit,
    modifier: Modifier = Modifier,
    domain: String? = null,
    label: String = "Select apps",
    filterMode: String = "all",
    extraPackages: List<String> = emptyList(),
    languageManager: LanguageManager? = null
) {
    val lm = languageManager
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showSpotifyOAuthDialog by remember { mutableStateOf(false) }
    var spotifyOAuthAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    val allApps = remember(domain, extraPackages) {
        val domainApps = if (domain != null) {
            AppRegistry.getInstalledAppsForDomain(domain)
        } else {
            AppRegistry.allInstalledApps()
        }
        if (extraPackages.isEmpty()) {
            domainApps
        } else {
            val existingPkgs = domainApps.map { it.packageName }.toSet()
            val extraApps = AppRegistry.allInstalledApps().filter {
                it.packageName in extraPackages && it.packageName !in existingPkgs
            }
            (domainApps + extraApps).sortedBy { it.displayName.lowercase() }
        }
    }

    val selectedApps = allApps.filter { it.packageName in selectedPackages }
    val defaultApp = selectedApps.find { it.packageName == defaultPackage }

    var expanded by remember { mutableStateOf(false) }

    OutlinedCard(
        modifier = modifier.fillMaxWidth().animateContentSize(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (selectedApps.isEmpty()) {
                            lm?.getString("no_apps_selected") ?: "No apps selected"
                        } else if (defaultApp != null) {
                            (lm?.getString("default_app_summary") ?: "Default: %s (+%d others)").format(defaultApp.displayName, selectedApps.size - 1)
                        } else {
                            (lm?.getString("apps_selected_no_default") ?: "%d apps selected, no default").format(selectedApps.size)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) (lm?.getString("collapse") ?: "Collapse") else (lm?.getString("expand") ?: "Expand"),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (expanded) {
                HorizontalDivider()
                AppPickerListMulti(
                    apps = allApps,
                    selectedPackages = selectedPackages,
                    defaultPackage = defaultPackage,
                    filterMode = filterMode,
                    onToggleApp = { pkg ->
                        if (pkg == com.voxcommander.app.utils.PackageNames.SPOTIFY && pkg !in selectedPackages && !SpotifyRemoteManager.isConnected) {
                            spotifyOAuthAction = { onToggleApp(pkg) }
                            showSpotifyOAuthDialog = true
                        } else {
                            onToggleApp(pkg)
                        }
                    },
                    onSetDefault = onSetDefault,
                    languageManager = lm
                )
            }
        }
    }

    SpotifyOAuthDialog(
        show = showSpotifyOAuthDialog,
        onDismiss = { showSpotifyOAuthDialog = false },
        onConnect = {
            showSpotifyOAuthDialog = false
            scope.launch {
                withContext(Dispatchers.IO) {
                    SpotifyRemoteManager.connect(context)
                }
                spotifyOAuthAction?.invoke()
            }
        },
        onSkip = {
            showSpotifyOAuthDialog = false
            spotifyOAuthAction?.invoke()
        },
        languageManager = lm
    )
}

@Composable
private fun AppPickerList(
    apps: List<AppRegistry.AppEntry>,
    selectedPackage: String?,
    allowNone: Boolean,
    onSelect: (AppRegistry.AppEntry?) -> Unit,
    languageManager: LanguageManager? = null,
    maxHeight: androidx.compose.ui.unit.Dp = 300.dp
) {
    val lm = languageManager
    var searchQuery by remember { mutableStateOf("") }
    var filterMode by remember { mutableStateOf("all") }
    var filterExpanded by remember { mutableStateOf(false) }

    val filterOptions = listOf(
        "all" to (lm?.getString("show_all_apps") ?: "Show all apps"),
        "user" to (lm?.getString("show_user_apps") ?: "Show user apps"),
        "system" to (lm?.getString("show_system_apps") ?: "Show system apps")
    )
    val currentFilterLabel = filterOptions.find { it.first == filterMode }?.second ?: (lm?.getString("show_all_apps") ?: "Show all apps")

    val filteredApps = apps.filter { app ->
        val matchesSearch = searchQuery.isBlank() ||
            app.displayName.contains(searchQuery, ignoreCase = true) ||
            app.packageName.contains(searchQuery, ignoreCase = true)
        val matchesFilter = when (filterMode) {
            "user" -> !app.isSystemApp
            "system" -> app.isSystemApp
            else -> true
        }
        matchesSearch && matchesFilter
    }

    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        SearchFilterRow(
            searchQuery = searchQuery,
            onSearchChange = { searchQuery = it },
            filterMode = filterMode,
            onFilterChange = { filterMode = it },
            filterExpanded = filterExpanded,
            onFilterExpandChange = { filterExpanded = it },
            filterOptions = filterOptions,
            currentFilterLabel = currentFilterLabel,
            languageManager = lm
        )

        Column(
            modifier = Modifier.fillMaxWidth().heightIn(max = maxHeight).verticalScroll(rememberScrollState())
        ) {
            if (allowNone) {
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { onSelect(null) }.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(lm?.getString("none_system_default") ?: "None (use system default)", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (filteredApps.isNotEmpty()) HorizontalDivider()
            }

            if (filteredApps.isEmpty()) {
                Text(lm?.getString("no_apps_found") ?: "No apps found", modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            filteredApps.forEach { app ->
                val isSelected = app.packageName == selectedPackage
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { onSelect(app) }.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(app.displayName, style = MaterialTheme.typography.bodyMedium, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(app.packageName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (isSelected) {
                        Icon(Icons.Filled.Star, contentDescription = lm?.getString("selected") ?: "Selected", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun AppPickerListMulti(
    apps: List<AppRegistry.AppEntry>,
    selectedPackages: List<String>,
    defaultPackage: String?,
    filterMode: String,
    onToggleApp: (String) -> Unit,
    onSetDefault: (String) -> Unit,
    languageManager: LanguageManager? = null
) {
    val lm = languageManager
    var searchQuery by remember { mutableStateOf("") }
    var currentFilter by remember { mutableStateOf(filterMode) }
    var filterExpanded by remember { mutableStateOf(false) }

    val filterOptions = listOf(
        "all" to (lm?.getString("show_all_apps") ?: "Show all apps"),
        "user" to (lm?.getString("show_user_apps") ?: "Show user apps"),
        "system" to (lm?.getString("show_system_apps") ?: "Show system apps")
    )
    val currentFilterLabel = filterOptions.find { it.first == currentFilter }?.second ?: (lm?.getString("show_all_apps") ?: "Show all apps")

    val filteredApps = apps.filter { app ->
        val isSelected = app.packageName in selectedPackages
        val matchesSearch = searchQuery.isBlank() ||
            app.displayName.contains(searchQuery, ignoreCase = true) ||
            app.packageName.contains(searchQuery, ignoreCase = true)
        val matchesFilter = when (currentFilter) {
            "user" -> !app.isSystemApp
            "system" -> app.isSystemApp
            else -> true
        }
        (isSelected || matchesFilter) && matchesSearch
    }

    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        SearchFilterRow(
            searchQuery = searchQuery,
            onSearchChange = { searchQuery = it },
            filterMode = currentFilter,
            onFilterChange = { currentFilter = it },
            filterExpanded = filterExpanded,
            onFilterExpandChange = { filterExpanded = it },
            filterOptions = filterOptions,
            currentFilterLabel = currentFilterLabel,
            languageManager = lm
        )

        Column(
            modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp).verticalScroll(rememberScrollState())
        ) {
            if (filteredApps.isEmpty()) {
                Text(lm?.getString("no_apps_found") ?: "No apps found", modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            filteredApps.forEach { app ->
                val isSelected = app.packageName in selectedPackages
                val isDefault = app.packageName == defaultPackage
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { onToggleApp(app.packageName) }.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(checked = isSelected, onCheckedChange = { onToggleApp(app.packageName) })
                    Column(modifier = Modifier.weight(1f)) {
                        Text(app.displayName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(app.packageName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (isSelected) {
                        IconButton(onClick = { onSetDefault(app.packageName) }, enabled = !isDefault) {
                            Icon(
                                imageVector = if (isDefault) Icons.Filled.Star else Icons.Outlined.StarBorder,
                                contentDescription = lm?.getString("set_as_default") ?: "Set as default",
                                tint = if (isDefault) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchFilterRow(
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    filterMode: String,
    onFilterChange: (String) -> Unit,
    filterExpanded: Boolean,
    onFilterExpandChange: (Boolean) -> Unit,
    filterOptions: List<Pair<String, String>>,
    currentFilterLabel: String,
    languageManager: LanguageManager? = null
) {
    val lm = languageManager
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchChange,
            placeholder = { Text(lm?.getString("search_apps_placeholder") ?: "Search apps...") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { onSearchChange("") }) {
                        Icon(Icons.Default.Clear, contentDescription = lm?.getString("clear") ?: "Clear")
                    }
                }
            },
            singleLine = true,
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(24.dp)
        )
        Box {
            OutlinedButton(
                onClick = { onFilterExpandChange(true) },
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Text(currentFilterLabel, style = MaterialTheme.typography.labelSmall)
                Icon(Icons.Default.ArrowDropDown, contentDescription = null, modifier = Modifier.size(16.dp))
            }
            DropdownMenu(
                expanded = filterExpanded,
                onDismissRequest = { onFilterExpandChange(false) }
            ) {
                filterOptions.forEach { (value, lbl) ->
                    DropdownMenuItem(
                        text = { Text(lbl) },
                        onClick = { onFilterChange(value); onFilterExpandChange(false) }
                    )
                }
            }
        }
    }
}

@Composable
private fun SpotifyOAuthDialog(
    show: Boolean,
    onDismiss: () -> Unit,
    onConnect: () -> Unit,
    onSkip: () -> Unit,
    languageManager: LanguageManager?
) {
    if (show) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(languageManager?.getString("spotify_oauth_title") ?: "Spotify Login Required") },
            text = { Text(languageManager?.getString("spotify_oauth_message") ?: "Spotify requires OAuth login to enable voice-controlled playback. Connect now?") },
            confirmButton = {
                TextButton(onClick = onConnect) {
                    Text(languageManager?.getString("spotify_connect") ?: "Connect")
                }
            },
            dismissButton = {
                TextButton(onClick = onSkip) {
                    Text(languageManager?.getString("spotify_oauth_skip") ?: "Skip")
                }
            }
        )
    }
}
