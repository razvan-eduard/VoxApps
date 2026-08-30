package com.voxapps.design.picklist

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** A paired device offered as a push target — id is Hub's peer identity, label the device's
 *  self-declared name. */
data class VoxSyncDevice(val peerId: String, val label: String)

/** The `{peerId,label}` array Hub answers a peer listing with, as picker rows — rows without an id
 *  are dropped rather than offered as untappable targets, and malformed JSON reads as "nothing
 *  paired". One parser for every app's picker, since the wire shape is one. */
fun parseVoxSyncDevices(json: String?): List<VoxSyncDevice> = try {
    val array = org.json.JSONArray(json.orEmpty())
    (0 until array.length()).mapNotNull { i ->
        array.optJSONObject(i)?.let { VoxSyncDevice(it.optString("peerId"), it.optString("label")) }
            ?.takeIf { it.peerId.isNotBlank() }
    }
} catch (e: Exception) {
    emptyList()
}

/**
 * "Send these to which device?" — the target picker behind every app's multi-select "sync with
 * device" action. Deliberately a short sheet, not a [com.voxapps.design.VoxFullscreenSheet]: one
 * tap on one of a handful of devices, nothing to configure. The caller fetches [devices] from Hub
 * (they live there, not in the app) and supplies its own localized [title]/[emptyText], so this
 * stays free of both IPC and any app's string table.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoxDevicePickerSheet(
    title: String,
    devices: List<VoxSyncDevice>,
    emptyText: String,
    onPick: (VoxSyncDevice) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))
            if (devices.isEmpty()) {
                Text(
                    emptyText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 16.dp)
                )
            } else {
                devices.forEach { device ->
                    Text(
                        device.label,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(device) }
                            .padding(vertical = 14.dp)
                    )
                }
            }
        }
    }
}
