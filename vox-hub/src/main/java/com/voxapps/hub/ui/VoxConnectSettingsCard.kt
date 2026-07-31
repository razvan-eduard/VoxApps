package com.voxapps.hub.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.camera.view.PreviewView
import com.voxapps.hub.data.preferences.HubSettings
import com.voxapps.hub.data.preferences.HubSettingsRepository
import com.voxapps.ipc.VoxAppInfo
import com.voxapps.ipc.VoxAppsDiscovery
import com.voxapps.voxconnect.PairedDevice
import com.voxapps.voxconnect.PairedDeviceStore
import com.voxapps.voxconnect.VoxConnectPairing
import com.voxapps.voxconnect.VoxConnectQrScanner
import com.voxapps.voxconnect.VoxConnectServer
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Hub's "VoxConnect" settings subscreen: enable/disable the embedded bridge server, pair a new
 * desktop device (scanning a QR the desktop app shows), manage already-paired devices, and pick
 * which discovered domains a paired device may read/command — same discovery-driven toggle-list
 * shape as [HubScreen]'s backup config section, since both need live [VoxAppsDiscovery] data
 * rather than installed-app metadata.
 */
@Composable
fun VoxConnectSettingsCard(
    settings: HubSettings,
    settingsRepo: HubSettingsRepository,
    voxConnectServer: VoxConnectServer,
    voxConnectPairing: VoxConnectPairing,
    voxConnectDeviceStore: PairedDeviceStore,
    modifier: Modifier = Modifier
) {
    val languageManager = LocalLanguageManager.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var apps by remember { mutableStateOf<List<VoxAppInfo>>(emptyList()) }
    LaunchedEffect(Unit) {
        apps = VoxAppsDiscovery.discover(context)
    }

    var pairedDevices by remember { mutableStateOf<List<PairedDevice>>(voxConnectDeviceStore.getDevices()) }
    fun refreshDevices() { pairedDevices = voxConnectDeviceStore.getDevices() }

    var showScanner by remember { mutableStateOf(false) }
    var pairingError by remember { mutableStateOf<String?>(null) }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            pairingError = null
            showScanner = true
        } else {
            pairingError = languageManager.getString("voxconnect_camera_permission_denied")
        }
    }

    fun requestPairing() {
        val hasPermission = ContextCompat.checkSelfPermission(
            context, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        if (hasPermission) {
            pairingError = null
            showScanner = true
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(languageManager.getString("voxconnect_enable_label"), style = MaterialTheme.typography.bodyMedium)
                Text(
                    languageManager.getString("voxconnect_enable_desc"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = settings.voxConnectEnabled,
                onCheckedChange = { scope.launch { settingsRepo.setVoxConnectEnabled(it) } }
            )
        }

        if (settings.voxConnectEnabled) {
            Text(
                String.format(languageManager.getString("voxconnect_port_label"), settings.voxConnectPort),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Button(onClick = { requestPairing() }) {
                Text(languageManager.getString("voxconnect_pair_new_device"))
            }

            pairingError?.let { error ->
                Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }

            if (pairedDevices.isNotEmpty()) {
                Text(languageManager.getString("voxconnect_paired_devices_title"), style = MaterialTheme.typography.bodyMedium)
                val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }
                pairedDevices.forEach { device ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(device.label, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                dateFormat.format(device.pairedAt),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = {
                            voxConnectDeviceStore.revokeDevice(device.deviceId)
                            refreshDevices()
                        }) {
                            Icon(Icons.Filled.Delete, contentDescription = languageManager.getString("voxconnect_revoke_device"))
                        }
                    }
                }
            }

            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(languageManager.getString("voxconnect_media_control_label"), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        languageManager.getString("voxconnect_media_control_desc"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = settings.voxConnectMediaControlEnabled,
                    onCheckedChange = { scope.launch { settingsRepo.setVoxConnectMediaControlEnabled(it) } }
                )
            }

            if (apps.isEmpty()) {
                Text(languageManager.getString("hub_no_apps_found"))
            } else {
                Text(languageManager.getString("voxconnect_monitored_apps_title"), style = MaterialTheme.typography.bodyMedium)
                LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
                    items(apps, key = { it.packageName }) { app ->
                        val domain = app.domain
                        Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(app.label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                Switch(
                                    checked = domain != null && settings.voxConnectMonitoredApps[domain] == true,
                                    enabled = domain != null,
                                    onCheckedChange = { checked ->
                                        if (domain != null) {
                                            scope.launch { settingsRepo.setVoxConnectMonitoredApp(domain, checked) }
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showScanner) {
        QrScannerDialog(
            onDismiss = { showScanner = false },
            onScanned = { qrPayload ->
                showScanner = false
                scope.launch {
                    val device = voxConnectPairing.completeScannedPairing(
                        qrPayload, settings.voxConnectPort, Build.MODEL
                    )
                    if (device != null) {
                        voxConnectDeviceStore.upsertDevice(device)
                        pairingError = null
                        refreshDevices()
                    } else {
                        pairingError = languageManager.getString("voxconnect_pairing_failed")
                    }
                }
            }
        )
    }
}

@Composable
private fun QrScannerDialog(onDismiss: () -> Unit, onScanned: (String) -> Unit) {
    val languageManager = LocalLanguageManager.current
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scanner = remember { VoxConnectQrScanner(onDecoded = onScanned) }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                languageManager.getString("voxconnect_scan_qr_hint"),
                style = MaterialTheme.typography.bodyMedium
            )
            Box(modifier = Modifier.fillMaxWidth().aspectRatio(1f)) {
                AndroidView(
                    factory = { ctx ->
                        PreviewView(ctx).also { previewView ->
                            scanner.start(ctx, lifecycleOwner, previewView)
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
            Button(onClick = onDismiss) {
                Text(languageManager.getString("voxconnect_pairing_cancel"))
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose { scanner.stop() }
    }
}
