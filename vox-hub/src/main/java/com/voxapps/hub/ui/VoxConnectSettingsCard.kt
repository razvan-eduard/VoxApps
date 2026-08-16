package com.voxapps.hub.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
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
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.camera.view.PreviewView
import com.voxapps.design.settings.SettingsSectionCard
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
    var pendingRenameDevice by remember { mutableStateOf<PairedDevice?>(null) }
    var pendingDeleteDevice by remember { mutableStateOf<PairedDevice?>(null) }

    var showScanner by remember { mutableStateOf(false) }
    var pairingError by remember { mutableStateOf<String?>(null) }

    fun isIgnoringBatteryOptimizations(): Boolean =
        context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) ?: true

    var batteryExempted by remember { mutableStateOf(isIgnoringBatteryOptimizations()) }
    val batteryOptimizationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        // The system dialog doesn't reliably report its own outcome — just re-read the real state.
        batteryExempted = isIgnoringBatteryOptimizations()
    }

    fun isMediaListenerGranted(): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(VoxAppsDiscovery.COMMANDER_PACKAGE)

    var mediaListenerGranted by remember { mutableStateOf(isMediaListenerGranted()) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        // The notification-listener settings screen (Vox Commander's permission, granted from
        // here since VoxConnect's media control toggle lives in Hub) is a plain startActivity with
        // no result to observe — re-check on every return to this screen instead.
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                batteryExempted = isIgnoringBatteryOptimizations()
                mediaListenerGranted = isMediaListenerGranted()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

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

    // VoxConnectForegroundService's persistent notification only actually displays if this is
    // granted (API 33+) — the service itself still runs either way, but without the notification
    // it loses the priority benefit a foreground service is meant to provide. Fire-and-forget:
    // enabling the bridge doesn't depend on the grant result, unlike camera/pairing above.
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* no-op */ }

    fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
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
        SettingsSectionCard(languageManager.getString("voxconnect_bridge_section")) {
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
                    onCheckedChange = { checked ->
                        scope.launch { settingsRepo.setVoxConnectEnabled(checked) }
                        if (checked) requestNotificationPermissionIfNeeded()
                    }
                )
            }

            if (settings.voxConnectEnabled) {
                Text(
                    String.format(languageManager.getString("voxconnect_port_label"), settings.voxConnectPort),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (!batteryExempted) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            languageManager.getString("voxconnect_battery_warning"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                        Button(onClick = {
                            val intent = Intent(
                                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                Uri.parse("package:${context.packageName}")
                            )
                            batteryOptimizationLauncher.launch(intent)
                        }) {
                            Text(languageManager.getString("voxconnect_battery_allow_button"))
                        }
                    }
                }

            }
        }

        if (settings.voxConnectEnabled) {
            SettingsSectionCard(languageManager.getString("voxconnect_paired_devices_title")) {
                Button(onClick = { requestPairing() }) {
                    Text(languageManager.getString("voxconnect_pair_new_device"))
                }

                pairingError?.let { error ->
                    Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }

                if (pairedDevices.isNotEmpty()) {
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
                            IconButton(onClick = { pendingRenameDevice = device }) {
                                Icon(Icons.Filled.Edit, contentDescription = languageManager.getString("voxconnect_rename_device"))
                            }
                            IconButton(onClick = { pendingDeleteDevice = device }) {
                                Icon(Icons.Filled.Delete, contentDescription = languageManager.getString("voxconnect_revoke_device"))
                            }
                        }
                    }
                }

                }

                SettingsSectionCard(languageManager.getString("voxconnect_media_control_label")) {
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            languageManager.getString("voxconnect_media_control_desc"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                        Switch(
                            checked = settings.voxConnectMediaControlEnabled,
                            onCheckedChange = { scope.launch { settingsRepo.setVoxConnectMediaControlEnabled(it) } }
                        )
                    }

                    if (settings.voxConnectMediaControlEnabled && !mediaListenerGranted) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                languageManager.getString("voxconnect_media_permission_warning"),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                            Button(onClick = {
                                context.startActivity(
                                    Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            }) {
                                Text(languageManager.getString("voxconnect_media_permission_allow_button"))
                            }
                        }
                    }

                }

                SettingsSectionCard(languageManager.getString("voxconnect_monitored_apps_title")) {
                    if (apps.isEmpty()) {
                        Text(languageManager.getString("hub_no_apps_found"))
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            apps.forEach { app ->
                                val domain = app.domain
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
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

    pendingRenameDevice?.let { device ->
        RenameDeviceDialog(
            currentLabel = device.label,
            onConfirm = { newLabel ->
                voxConnectDeviceStore.renameDevice(device.deviceId, newLabel)
                pendingRenameDevice = null
                refreshDevices()
            },
            onDismiss = { pendingRenameDevice = null }
        )
    }

    pendingDeleteDevice?.let { device ->
        ConfirmDeleteDeviceDialog(
            deviceLabel = device.label,
            onConfirm = {
                voxConnectDeviceStore.revokeDevice(device.deviceId)
                pendingDeleteDevice = null
                refreshDevices()
            },
            onDismiss = { pendingDeleteDevice = null }
        )
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

@Composable
private fun RenameDeviceDialog(currentLabel: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    val languageManager = LocalLanguageManager.current
    var text by remember { mutableStateOf(currentLabel) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(languageManager.getString("voxconnect_rename_device_title")) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(languageManager.getString("voxconnect_rename_device_hint")) },
                singleLine = true
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text.trim()) }, enabled = text.isNotBlank()) {
                Text(languageManager.getString("save"))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(languageManager.getString("cancel")) } }
    )
}

@Composable
private fun ConfirmDeleteDeviceDialog(deviceLabel: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val languageManager = LocalLanguageManager.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(languageManager.getString("voxconnect_confirm_revoke_title")) },
        text = { Text(String.format(languageManager.getString("voxconnect_confirm_revoke_message"), deviceLabel)) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(languageManager.getString("voxconnect_revoke_device")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(languageManager.getString("cancel")) } }
    )
}
