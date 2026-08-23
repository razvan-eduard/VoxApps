package com.voxapps.apppicker

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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
 * Reusable app picker: a card whose header shows the current selection, tap to open a full-screen
 * modal sheet with a search box + all/user/system filter + scrollable checkbox/radio list, plus a
 * Cancel/Done bar at the bottom. Ported from vox-commander's original `AppSelectorDropdown` — this
 * module carries only the generic rendering; app-specific behavior (e.g. vox-commander's Spotify-OAuth
 * interception or satellite-domain candidate filtering) stays in each app's own thin wrapper.
 *
 * A modal sheet (rather than expanding inline) is deliberate: callers place this card inside their
 * own scrollable screen, at whatever position it lands — often well below the fold. Expanding inline
 * left the search box and list fighting the parent screen's scroll position for visibility (confirmed
 * on-device in vox-expenses' Notification Capture settings: at best only the tail end of the list was
 * reachable). A full-screen modal sheet always opens fully visible regardless of where the card sits,
 * and a Cancel/Done bar means selections only commit when the user explicitly confirms, rather than
 * live-applying every tap (which also made "changed my mind" impossible without re-toggling).
 *
 * Single-select variant: pick one app (or none).
 */
@OptIn(ExperimentalMaterial3Api::class)
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

    var sheetOpen by remember { mutableStateOf(false) }

    OutlinedCard(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { sheetOpen = true }
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
                imageVector = Icons.Default.ExpandMore,
                contentDescription = strings.expand,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    if (sheetOpen) {
        var pendingSelection by remember { mutableStateOf(selectedApp) }
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = { sheetOpen = false }, sheetState = sheetState) {
            Column(modifier = Modifier.fillMaxSize()) {
                AppPickerList(
                    apps = apps,
                    selectedPackage = pendingSelection?.packageName,
                    allowNone = allowNone,
                    strings = strings,
                    onSelect = { app -> pendingSelection = app },
                    modifier = Modifier.weight(1f)
                )
                ConfirmBar(
                    strings = strings,
                    onCancel = { sheetOpen = false },
                    onDone = {
                        onAppSelected(pendingSelection)
                        sheetOpen = false
                    }
                )
            }
        }
    }
}

/**
 * Multi-select variant with optional star support — either a single "default" app
 * ([defaultPackage]/[onApplyDefault]) or a set of independently-toggled starred apps
 * ([starredPackages]/[onApplyStarred], e.g. "which of these payment apps are banks"). At most one of
 * the two star modes should be wired up per call site; leaving both null omits the star column
 * entirely (the right choice when the selection has no meaningful "starred" concept at all).
 *
 * Unlike the old per-tap `onToggleApp`, all changes are staged locally while the sheet is open and
 * only reach the caller once, in full, when Done is tapped ([onApply] gets the complete final list —
 * never called incrementally). This is deliberate, not just a style choice: replaying several
 * incremental toggle calls in a loop at commit time would each close over the same stale
 * `selectedPackages` snapshot (Compose doesn't recompose mid-callback), so only the last call would
 * "win" and earlier toggles in the same batch would silently vanish. A single bulk callback with the
 * full final list sidesteps that entirely — every real caller already persists via a "set the whole
 * list" method (`setDomainApps`, `setPaymentSourcePackages`, etc.), so this is also simpler at the
 * call site than the old manual diffing was.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppPickerCard(
    apps: List<AppPickerEntry>,
    selectedPackages: List<String>,
    onApply: (List<String>) -> Unit,
    strings: AppPickerStrings,
    modifier: Modifier = Modifier,
    label: String = "Select apps",
    initialFilterMode: String = "all",
    defaultPackage: String? = null,
    onApplyDefault: ((String?) -> Unit)? = null,
    starredPackages: Set<String> = emptySet(),
    onApplyStarred: ((Set<String>) -> Unit)? = null
) {
    val selectedApps = apps.filter { it.packageName in selectedPackages }
    val defaultApp = selectedApps.find { it.packageName == defaultPackage }

    var sheetOpen by remember { mutableStateOf(false) }

    OutlinedCard(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { sheetOpen = true }
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
                        // Star mode (starredPackages/onApplyStarred wired instead of a single default) —
                        // show how many of the selected apps are actually starred, rather than the
                        // single-default fallback text (which talks about "no default", nonsensical
                        // here since this call site never had a "default" concept to begin with).
                        onApplyStarred != null ->
                            strings.starredCountSummaryFormat.format(selectedApps.size, starredPackages.size)
                        else -> strings.appsSelectedNoDefaultFormat.format(selectedApps.size)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector = Icons.Default.ExpandMore,
                contentDescription = strings.expand,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    if (sheetOpen) {
        var pendingSelected by remember { mutableStateOf(selectedPackages.toSet()) }
        var pendingDefault by remember { mutableStateOf(defaultPackage) }
        var pendingStarred by remember { mutableStateOf(starredPackages) }
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

        ModalBottomSheet(onDismissRequest = { sheetOpen = false }, sheetState = sheetState) {
            Column(modifier = Modifier.fillMaxSize()) {
                AppPickerListMulti(
                    apps = apps,
                    selectedPackages = pendingSelected.toList(),
                    onToggleApp = { pkg ->
                        pendingSelected = if (pkg in pendingSelected) pendingSelected - pkg else pendingSelected + pkg
                    },
                    defaultPackage = pendingDefault,
                    onSetDefault = onApplyDefault?.let { { pkg: String? -> pendingDefault = pkg } },
                    starredPackages = pendingStarred,
                    onToggleStar = onApplyStarred?.let { { pkg: String ->
                        pendingStarred = if (pkg in pendingStarred) pendingStarred - pkg else pendingStarred + pkg
                    } },
                    initialFilterMode = initialFilterMode,
                    strings = strings,
                    modifier = Modifier.weight(1f)
                )
                ConfirmBar(
                    strings = strings,
                    onCancel = { sheetOpen = false },
                    onDone = {
                        onApply(pendingSelected.toList())
                        onApplyDefault?.invoke(pendingDefault)
                        onApplyStarred?.invoke(pendingStarred)
                        sheetOpen = false
                    }
                )
            }
        }
    }
}

@Composable
private fun ConfirmBar(strings: AppPickerStrings, onCancel: () -> Unit, onDone: () -> Unit) {
    HorizontalDivider()
    Row(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) {
            Text(strings.cancel)
        }
        Button(onClick = onDone, modifier = Modifier.weight(1f)) {
            Text(strings.done)
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
    modifier: Modifier = Modifier
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

    val ordered = remember(apps) { AppPickerOrder.of(apps, setOfNotNull(selectedPackage)) }

    val filteredApps = ordered.filter { app ->
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

    Column(modifier = modifier.padding(vertical = 8.dp)) {
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
            modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())
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
    onToggleStar: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier
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

    // Ordered as the list opens rather than as it is used: a row that leaps to the top the moment
    // it is ticked takes the row underneath it — the one you were about to tick — along with it.
    // Re-keying on `apps` alone means the order settles again the next time the picker is opened.
    val ordered = remember(apps) { AppPickerOrder.of(apps, selectedPackages.toSet(), starredPackages) }

    val filteredApps = ordered.filter { app ->
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

    Column(modifier = modifier.padding(vertical = 8.dp)) {
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
            modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())
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
