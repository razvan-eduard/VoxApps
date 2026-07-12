package com.voxapps.apppicker

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Reusable app picker: expandable card whose header shows the current selection, tap to expand a
 * search box + all/user/system filter + scrollable checkbox/radio list. Ported from vox-commander's
 * original `AppSelectorDropdown` — this module carries only the generic rendering; app-specific
 * behavior (e.g. vox-commander's Spotify-OAuth interception or satellite-domain candidate
 * filtering) stays in each app's own thin wrapper around this composable.
 *
 * Single-select variant: pick one app (or none).
 */
@Composable
fun AppPickerCard(
    apps: List<AppPickerEntry>,
    selectedPackage: String?,
    onAppSelected: (AppPickerEntry?) -> Unit,
    strings: AppPickerStrings,
    modifier: Modifier = Modifier,
    label: String = "Select app",
    allowNone: Boolean = true,
    maxDropdownHeight: Dp = 300.dp
) {
    val selectedApp = remember(selectedPackage, apps) {
        apps.find { it.packageName == selectedPackage }
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
                        text = selectedApp?.displayName
                            ?: if (allowNone) strings.noneLabel else strings.notSelected,
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
                    contentDescription = if (expanded) strings.collapse else strings.expand,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (expanded) {
                HorizontalDivider()
                AppPickerList(
                    apps = apps,
                    selectedPackage = selectedPackage,
                    allowNone = allowNone,
                    strings = strings,
                    onSelect = { app ->
                        onAppSelected(app)
                        expanded = false
                    },
                    maxHeight = maxDropdownHeight
                )
            }
        }
    }
}

/**
 * Multi-select variant with optional star support — either a single "default" app
 * ([defaultPackage]/[onSetDefault]) or a set of independently-toggled starred apps
 * ([starredPackages]/[onToggleStar], e.g. "which of these payment apps are banks"). At most one of
 * the two star modes should be wired up per call site; leaving both null omits the star column
 * entirely (the right choice when the selection has no meaningful "starred" concept at all).
 */
@Composable
fun AppPickerCard(
    apps: List<AppPickerEntry>,
    selectedPackages: List<String>,
    onToggleApp: (String) -> Unit,
    strings: AppPickerStrings,
    modifier: Modifier = Modifier,
    label: String = "Select apps",
    initialFilterMode: String = "all",
    defaultPackage: String? = null,
    onSetDefault: ((String?) -> Unit)? = null,
    starredPackages: Set<String> = emptySet(),
    onToggleStar: ((String) -> Unit)? = null
) {
    val selectedApps = apps.filter { it.packageName in selectedPackages }
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
                        text = when {
                            selectedApps.isEmpty() -> strings.noAppsSelected
                            defaultApp != null ->
                                strings.defaultAppSummaryFormat.format(defaultApp.displayName, selectedApps.size - 1)
                            else -> strings.appsSelectedNoDefaultFormat.format(selectedApps.size)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) strings.collapse else strings.expand,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (expanded) {
                HorizontalDivider()
                AppPickerListMulti(
                    apps = apps,
                    selectedPackages = selectedPackages,
                    defaultPackage = defaultPackage,
                    initialFilterMode = initialFilterMode,
                    strings = strings,
                    onToggleApp = onToggleApp,
                    onSetDefault = onSetDefault,
                    starredPackages = starredPackages,
                    onToggleStar = onToggleStar
                )
            }
        }
    }
}

@Composable
private fun AppPickerList(
    apps: List<AppPickerEntry>,
    selectedPackage: String?,
    allowNone: Boolean,
    strings: AppPickerStrings,
    onSelect: (AppPickerEntry?) -> Unit,
    maxHeight: Dp = 300.dp
) {
    var searchQuery by remember { mutableStateOf("") }
    var filterMode by remember { mutableStateOf("all") }
    var filterExpanded by remember { mutableStateOf(false) }

    val filterOptions = listOf(
        "all" to strings.showAllApps,
        "user" to strings.showUserApps,
        "system" to strings.showSystemApps
    )
    val currentFilterLabel = filterOptions.find { it.first == filterMode }?.second ?: strings.showAllApps

    val filteredApps = apps.filter { app ->
        val matchesSearch = searchQuery.isBlank() ||
            app.displayName.contains(searchQuery.trim(), ignoreCase = true) ||
            app.packageName.contains(searchQuery.trim(), ignoreCase = true)
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
            strings = strings
        )

        Column(
            modifier = Modifier.fillMaxWidth().heightIn(max = maxHeight).verticalScroll(rememberScrollState())
        ) {
            if (allowNone) {
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { onSelect(null) }.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(strings.noneLabel, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (filteredApps.isNotEmpty()) HorizontalDivider()
            }

            if (filteredApps.isEmpty()) {
                Text(strings.noAppsFound, modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                        Icon(Icons.Filled.Star, contentDescription = strings.selected, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun AppPickerListMulti(
    apps: List<AppPickerEntry>,
    selectedPackages: List<String>,
    defaultPackage: String?,
    initialFilterMode: String,
    strings: AppPickerStrings,
    onToggleApp: (String) -> Unit,
    onSetDefault: ((String?) -> Unit)?,
    starredPackages: Set<String> = emptySet(),
    onToggleStar: ((String) -> Unit)? = null
) {
    var searchQuery by remember { mutableStateOf("") }
    var currentFilter by remember { mutableStateOf(initialFilterMode) }
    var filterExpanded by remember { mutableStateOf(false) }

    val filterOptions = listOf(
        "all" to strings.showAllApps,
        "user" to strings.showUserApps,
        "system" to strings.showSystemApps
    )
    val currentFilterLabel = filterOptions.find { it.first == currentFilter }?.second ?: strings.showAllApps

    val filteredApps = apps.filter { app ->
        val isSelected = app.packageName in selectedPackages
        val matchesSearch = searchQuery.isBlank() ||
            app.displayName.contains(searchQuery.trim(), ignoreCase = true) ||
            app.packageName.contains(searchQuery.trim(), ignoreCase = true)
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
            strings = strings
        )

        Column(
            modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp).verticalScroll(rememberScrollState())
        ) {
            if (filteredApps.isEmpty()) {
                Text(strings.noAppsFound, modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            filteredApps.forEach { app ->
                val isSelected = app.packageName in selectedPackages
                val isDefault = app.packageName == defaultPackage
                val isStarred = app.packageName in starredPackages
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { onToggleApp(app.packageName) }.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(checked = isSelected, onCheckedChange = { onToggleApp(app.packageName) })
                    Column(modifier = Modifier.weight(1f)) {
                        Text(app.displayName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(app.packageName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (isSelected && onToggleStar != null) {
                        IconButton(onClick = { onToggleStar(app.packageName) }) {
                            Icon(
                                imageVector = if (isStarred) Icons.Filled.Star else Icons.Outlined.StarBorder,
                                contentDescription = if (isStarred) strings.removeDefault else strings.setAsDefault,
                                tint = if (isStarred) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else if (isSelected && onSetDefault != null) {
                        IconButton(onClick = { onSetDefault(if (isDefault) null else app.packageName) }) {
                            Icon(
                                imageVector = if (isDefault) Icons.Filled.Star else Icons.Outlined.StarBorder,
                                contentDescription = if (isDefault) strings.removeDefault else strings.setAsDefault,
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
    strings: AppPickerStrings
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchChange,
            placeholder = { Text(strings.searchPlaceholder) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { onSearchChange("") }) {
                        Icon(Icons.Default.Clear, contentDescription = strings.clear)
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
