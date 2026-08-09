package com.voxapps.design.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * A labeled divider between groups of settings-menu entries — a full-width band, slightly darker
 * than the surrounding menu background ([MaterialTheme.colorScheme.surfaceVariant], the same token
 * already used elsewhere across every app for subtle background differentiation), so a flat
 * ListItem menu reads as distinct sections instead of one undifferentiated list.
 */
@Composable
fun SettingsSectionHeader(label: String, modifier: Modifier = Modifier) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    )
}
