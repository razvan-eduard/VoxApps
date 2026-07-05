package com.voxcommander.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.voxcommander.app.domain.localization.LanguageManager
import com.voxcommander.app.utils.Logger

data class DropdownGroup<T>(
    val header: String,
    val items: List<T>
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> GroupedDropdownContent(
    title: String?,
    groups: List<DropdownGroup<T>>,
    itemLabel: (T) -> String,
    isDownloaded: @Composable (T) -> Boolean, // UPDATED TO @Composable
    isDefault: @Composable (T) -> Boolean = { false },
    isBuiltIn: (T) -> Boolean = { false },
    onDeviceLabel: String,
    onItemSelected: (T, Boolean) -> Unit, // Include downloaded state
    onDownloadRequest: ((T) -> Unit)? = null,
    onDeleteRequest: ((T) -> Unit)? = null,
    onCancelDownload: (() -> Unit)? = null,
    downloadProgress: Float? = null,
    downloadingItem: Any? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        if (title != null) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 16.dp)
            )
        }

        LazyColumn(
            modifier = Modifier.weight(1f, fill = false),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            groups.forEach { group ->
                item {
                    Text(
                        text = group.header,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                    )
                }

                items(group.items) { item ->
                    val label = itemLabel(item)
                    val builtIn = isBuiltIn(item)
                    val downloaded = builtIn || isDownloaded(item) // Now reactive @Composable
                    val isDefault = isDefault(item)
                    val isDownloading = downloadingItem == item

                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                Logger.log("Item clicked: label=$label, downloaded=$downloaded", "GroupedDropdown")
                                onItemSelected(item, downloaded)
                            },
                        shape = RoundedCornerShape(8.dp),
                        color = if (downloaded) Color(0xFFE8F5E9) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                    if (isDefault) {
                                        Icon(
                                            Icons.Default.Check,
                                            contentDescription = "Default",
                                            tint = Color(0xFF2E7D32),
                                            modifier = Modifier.padding(end = 8.dp)
                                        )
                                    }
                                    Column {
                                        Text(
                                            text = label,
                                            style = MaterialTheme.typography.bodyLarge,
                                            fontWeight = if (downloaded) FontWeight.Bold else FontWeight.Normal
                                        )
                                        if (downloaded) {
                                            Text(
                                                text = onDeviceLabel,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = Color(0xFF2E7D32)
                                            )
                                        }
                                    }
                                }

                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (isDownloading) {
                                        IconButton(onClick = { onCancelDownload?.invoke() }) {
                                            Icon(
                                                Icons.Default.Close,
                                                contentDescription = "Cancel",
                                                tint = MaterialTheme.colorScheme.error
                                            )
                                        }
                                    } else if (builtIn) {
                                        // Built-in models: no download/delete buttons
                                    } else if (downloaded) {
                                        if (onDeleteRequest != null) {
                                            IconButton(onClick = { onDeleteRequest(item) }) {
                                                Icon(
                                                    Icons.Default.Delete,
                                                    contentDescription = "Delete",
                                                    tint = MaterialTheme.colorScheme.error
                                                )
                                            }
                                        }
                                    } else if (onDownloadRequest != null) {
                                        IconButton(onClick = {
                                            // Click on arrow: trigger download but DO NOT close the sheet
                                            onDownloadRequest(item)
                                        }) {
                                            Icon(
                                                Icons.Default.Download,
                                                contentDescription = "Download",
                                                tint = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    }
                                }
                            }

                            if (isDownloading) {
                                Spacer(modifier = Modifier.height(8.dp))
                                if (downloadProgress != null) {
                                    LinearProgressIndicator(
                                        progress = { downloadProgress },
                                        modifier = Modifier.fillMaxWidth(),
                                        color = MaterialTheme.colorScheme.primary,
                                        trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                                    )
                                    Text(
                                        text = "${(downloadProgress * 100).toInt()}%",
                                        style = MaterialTheme.typography.labelSmall,
                                        modifier = Modifier.align(Alignment.End)
                                    )
                                } else {
                                    LinearProgressIndicator(
                                        modifier = Modifier.fillMaxWidth(),
                                        color = MaterialTheme.colorScheme.primary,
                                        trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> GroupedDropdownMenu(
    selectedItem: T?,
    groups: List<DropdownGroup<T>>,
    itemLabel: (T) -> String,
    isDownloaded: @Composable (T) -> Boolean, // UPDATED TO @Composable
    isDefault: @Composable (T) -> Boolean = { false },
    isBuiltIn: (T) -> Boolean = { false },
    onDeviceLabel: String,
    onItemSelected: (T, Boolean) -> Unit, // Include downloaded state
    onDownloadRequest: ((T) -> Unit)? = null,
    onDeleteRequest: ((T) -> Unit)? = null,
    onCancelDownload: (() -> Unit)? = null,
    downloadProgress: Float? = null,
    downloadingItem: Any? = null,
    modifier: Modifier = Modifier,
    onExpandedChange: ((Boolean) -> Unit)? = null,
    placeholder: String? = null
) {
    val builtIn = selectedItem?.let { isBuiltIn(it) } ?: false
    val downloaded = builtIn || (selectedItem?.let { isDownloaded(it) } ?: false)
    val isDefault = selectedItem?.let { isDefault(it) } ?: false
    val isDownloading = downloadingItem != null && downloadingItem == selectedItem

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onExpandedChange?.invoke(true) },
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(
            1.dp, 
            if (downloaded) Color(0xFF2E7D32).copy(alpha = 0.5f) else MaterialTheme.colorScheme.outline
        ),
        color = if (downloaded) Color(0xFFE8F5E9) else Color.Transparent
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    if (isDefault) {
                        Icon(Icons.Default.Check, contentDescription = "Default", tint = Color(0xFF2E7D32), modifier = Modifier.padding(end = 8.dp))
                    }
                    Column {
                        Text(
                            text = selectedItem?.let { itemLabel(it) } ?: placeholder ?: "Select...",
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (selectedItem == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
                        )
                        if (downloaded) {
                            Text(
                                text = onDeviceLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF2E7D32)
                            )
                        }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isDownloading) {
                        IconButton(onClick = { onCancelDownload?.invoke() }) {
                            Icon(Icons.Default.Close, contentDescription = "Cancel", tint = MaterialTheme.colorScheme.error)
                        }
                    } else if (builtIn) {
                        // Built-in models: no download/delete buttons
                    } else if (downloaded) {
                        if (onDeleteRequest != null) {
                            IconButton(onClick = { if (selectedItem != null) onDeleteRequest(selectedItem) }) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    } else if (selectedItem != null && onDownloadRequest != null) {
                        IconButton(onClick = { onDownloadRequest(selectedItem) }) {
                            Icon(Icons.Default.Download, contentDescription = "Download", tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                    Icon(Icons.Default.ArrowDropDown, contentDescription = "Expand")
                }
            }

            if (isDownloading) {
                Spacer(modifier = Modifier.height(8.dp))
                if (downloadProgress != null) {
                    LinearProgressIndicator(
                        progress = { downloadProgress },
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                    )
                }
            }
        }
    }
}
