package com.voxapps.location.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.voxapps.location.HomeTown
import com.voxapps.location.LocationCacheTtl

/**
 * The shared location settings section for both vox-commander and vox-expenses. Pure
 * values-in/callbacks-out (no repo type, no DataStore access here) — the host app owns its
 * settings repo and wires reads/writes exactly as it already does for other settings sections
 * (see `DuplicateRulesSection` / `core/design`'s `ThemeSettingsScreen`).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoxLocationSettingsCard(
    state: VoxLocationUiState,
    features: VoxLocationCardFeatures = VoxLocationCardFeatures(),
    onHomeTownChange: (HomeTown?) -> Unit,
    onCacheTtlChange: (LocationCacheTtl) -> Unit,
    onAlwaysUseHomeTownChange: (Boolean) -> Unit,
    onRefreshClick: () -> Unit,
    onPickOnMapClick: (() -> Unit)? = null,
    strings: VoxLocationStrings = VoxLocationStrings(),
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(strings.sectionTitle, style = MaterialTheme.typography.titleMedium)

            // GPS-derived sections are meaningless once Home Town is forced — hide them rather
            // than show controls whose state can never actually apply.
            if (!state.alwaysUseHomeTown) {
                if (features.showLastLocationDisplay) {
                    LastLocationRow(
                        state = state,
                        showRefreshButton = features.showRefreshButton,
                        onRefreshClick = onRefreshClick,
                        strings = strings
                    )
                }
                if (features.showCacheTtlSelector) {
                    CacheTtlSelector(
                        selected = state.cacheTtl,
                        onSelect = onCacheTtlChange,
                        strings = strings
                    )
                }
            }

            if (features.showHomeTownOverride) {
                HomeTownEditor(
                    homeTown = state.homeTown,
                    onHomeTownChange = onHomeTownChange,
                    onPickOnMapClick = onPickOnMapClick,
                    showAlwaysUseToggle = features.showAlwaysUseToggle,
                    alwaysUseHomeTown = state.alwaysUseHomeTown,
                    onAlwaysUseHomeTownChange = onAlwaysUseHomeTownChange,
                    strings = strings
                )
            }
        }
    }
}

@Composable
private fun LastLocationRow(
    state: VoxLocationUiState,
    showRefreshButton: Boolean,
    onRefreshClick: () -> Unit,
    strings: VoxLocationStrings
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                strings.lastLocationLabel,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            val loc = state.lastKnownLocation
            Text(
                text = when {
                    loc == null -> strings.lastLocationUnavailable
                    loc.displayName != null -> "${loc.displayName} (%.4f, %.4f)".format(loc.lat, loc.lon)
                    else -> "%.4f, %.4f".format(loc.lat, loc.lon)
                },
                style = MaterialTheme.typography.bodyMedium
            )
        }
        if (showRefreshButton) {
            if (state.isRefreshing) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                IconButton(onClick = onRefreshClick) {
                    Icon(Icons.Default.Refresh, contentDescription = strings.refreshButton)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CacheTtlSelector(
    selected: LocationCacheTtl,
    onSelect: (LocationCacheTtl) -> Unit,
    strings: VoxLocationStrings
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            strings.cacheTtlLabel,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            LocationCacheTtl.entries.forEach { ttl ->
                FilterChip(
                    selected = ttl == selected,
                    onClick = { onSelect(ttl) },
                    label = {
                        Text(
                            strings.labelFor(ttl),
                            // Material3's FilterChip has no public content-padding override, so a
                            // touch smaller label is the only safe lever to narrow the chip itself
                            // without risking the 2-line wrap this size (maxLines/softWrap) fixed.
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                )
            }
        }
    }
}

@Composable
private fun HomeTownEditor(
    homeTown: HomeTown?,
    onHomeTownChange: (HomeTown?) -> Unit,
    onPickOnMapClick: (() -> Unit)?,
    showAlwaysUseToggle: Boolean,
    alwaysUseHomeTown: Boolean,
    onAlwaysUseHomeTownChange: (Boolean) -> Unit,
    strings: VoxLocationStrings
) {
    var latText by remember(homeTown) { mutableStateOf(homeTown?.lat?.toString() ?: "") }
    var lonText by remember(homeTown) { mutableStateOf(homeTown?.lon?.toString() ?: "") }

    // No explicit Save button — matches how every other setting in this app persists: the value
    // commits on its own. A text field can't commit per-keystroke the way a Switch/FilterChip
    // does (a half-typed number is usually invalid), so this commits once, when the card leaves
    // composition (i.e. the user navigates back), same moment "auto-save on back" means elsewhere.
    val currentOnHomeTownChange by rememberUpdatedState(onHomeTownChange)
    DisposableEffect(Unit) {
        onDispose {
            val lat = latText.toDoubleOrNull()
            val lon = lonText.toDoubleOrNull()
            if (lat != null && lon != null) currentOnHomeTownChange(HomeTown(lat, lon))
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(strings.homeTownTitle, style = MaterialTheme.typography.titleSmall)

        if (showAlwaysUseToggle) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(strings.alwaysUseToggleLabel, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        strings.alwaysUseToggleDescription,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = alwaysUseHomeTown, onCheckedChange = onAlwaysUseHomeTownChange)
            }
        }

        Text(
            strings.homeTownDescription,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        // Search-first: picking a place fills the coordinate fields below, which stay editable —
        // typed numbers remain the source of truth the card commits.
        var searchText by remember { mutableStateOf("") }
        VoxLocationPickerField(
            value = searchText,
            onValueChange = { searchText = it },
            label = strings.homeTownSearchLabel,
            onPlacePicked = { place ->
                latText = place.lat.toString()
                lonText = place.lon.toString()
                searchText = place.shortName
            }
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = latText,
                onValueChange = { latText = it },
                label = { Text(strings.latitudeLabel) },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = lonText,
                onValueChange = { lonText = it },
                label = { Text(strings.longitudeLabel) },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
        }
        if (homeTown != null || onPickOnMapClick != null) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (homeTown != null) {
                    TextButton(onClick = {
                        latText = ""
                        lonText = ""
                        onHomeTownChange(null)
                    }) {
                        Text(strings.clearButton)
                    }
                }
                if (onPickOnMapClick != null) {
                    TextButton(onClick = onPickOnMapClick) {
                        Icon(Icons.Default.Place, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(strings.pickOnMapButton)
                    }
                }
            }
        }
    }
}
