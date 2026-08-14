package com.voxapps.location.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.voxapps.location.VOX_NOMINATIM_USER_AGENT
import com.voxapps.location.VoxPlace

/**
 * THE suite location field — one component for every screen that takes a place, so the pattern is
 * learned once: a single location glyph (gray while empty, tinted once set) that expands on tap
 * into a chrome-free inline [VoxLocationPickerField] (OSM search + optional GPS lock) with an ✕ to
 * clear; collapsed with a value it shows the text beside the glyph, tappable as a link when
 * [onOpenLocation] is given (calendar/expenses open it in a maps app; commander's Home Town filler
 * passes null). Caching policy stays entirely in [gpsLock] — the host wires its own store.
 */
@Composable
fun VoxLocationField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    clearContentDescription: String,
    modifier: Modifier = Modifier,
    onPlacePicked: ((VoxPlace) -> Unit)? = null,
    gpsLock: (suspend () -> String?)? = null,
    onOpenLocation: ((String) -> Unit)? = null,
    userAgent: String = VOX_NOMINATIM_USER_AGENT
) {
    var expanded by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier.fillMaxWidth()) {
        IconButton(onClick = { expanded = !expanded }) {
            Icon(
                Icons.Filled.LocationOn,
                contentDescription = label,
                tint = if (value.isNotBlank()) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
        when {
            expanded -> {
                VoxLocationPickerField(
                    value = value,
                    onValueChange = onValueChange,
                    label = label,
                    inline = true,
                    onPlacePicked = onPlacePicked,
                    gpsLock = gpsLock,
                    userAgent = userAgent,
                    modifier = Modifier.weight(1f)
                )
                if (value.isNotEmpty()) {
                    IconButton(onClick = { onValueChange("") }) {
                        Icon(Icons.Filled.Close, contentDescription = clearContentDescription)
                    }
                }
            }
            value.isNotBlank() -> Text(
                value,
                maxLines = 1,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .weight(1f)
                    .then(
                        if (onOpenLocation != null) {
                            Modifier.clickable { onOpenLocation(value) }
                        } else {
                            Modifier.clickable { expanded = true }
                        }
                    )
            )
        }
    }
}
