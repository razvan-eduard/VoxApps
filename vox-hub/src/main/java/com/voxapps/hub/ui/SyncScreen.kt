package com.voxapps.hub.ui

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.voxapps.hub.domain.sync.BluetoothPeerResolver
import com.voxapps.hub.domain.sync.NfcPairingReader
import com.voxapps.hub.domain.sync.PairedPeer
import com.voxapps.hub.domain.sync.PairingEvent
import com.voxapps.hub.domain.sync.PairingEvents
import com.voxapps.hub.domain.sync.PairingResult
import com.voxapps.hub.domain.sync.SyncAlarmScheduler
import com.voxapps.hub.domain.sync.SyncOrchestrator
import com.voxapps.hub.domain.sync.SyncPeerStore
import com.voxapps.hub.domain.sync.SyncSessionResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private sealed interface PairingUiState {
    data object Idle : PairingUiState
    data object WaitingForTap : PairingUiState
    data object ResolvingBluetooth : PairingUiState
    data class Success(val peer: PairedPeer) : PairingUiState
    data class Error(val message: String) : PairingUiState
}

private sealed interface PeerSyncState {
    data object Syncing : PeerSyncState
    data class Done(val result: SyncSessionResult) : PeerSyncState
}

private val AUTO_SYNC_INTERVAL_OPTIONS = listOf(30, 60, 120, 240)

private fun requiredBluetoothPermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= 31) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

/**
 * Pair a new device over NFC (see NfcPairingReader/PairingHceService), name this device (what the
 * peer's lists show as provenance), trigger an on-demand sync with an already-paired one (see
 * SyncOrchestrator), toggle per-peer aligned auto-sync (SyncAlarmScheduler; both phones must enable
 * it, and the same interval on both is what makes their slots coincide), and open the per-peer
 * shared-containers checklist (SyncScopeScreen). Each peer card also carries the recovery actions
 * for a lost Bluetooth address — re-advertise on the listening side, re-scan on the connecting side.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncScreen(
    peerStore: SyncPeerStore,
    onBack: () -> Unit
) {
    val languageManager = LocalLanguageManager.current
    val context = LocalContext.current
    val activity = context as? Activity
    val scope = rememberCoroutineScope()

    var uiState by remember { mutableStateOf<PairingUiState>(PairingUiState.Idle) }
    var peers by remember { mutableStateOf(peerStore.getPeers()) }
    var peerSyncStates by remember { mutableStateOf<Map<String, PeerSyncState>>(emptyMap()) }
    var pendingSyncPeer by remember { mutableStateOf<PairedPeer?>(null) }
    var editingScopeForPeer by remember { mutableStateOf<PairedPeer?>(null) }

    // Without this, the system back gesture/button falls through to the Activity's default
    // behavior (no back stack, single Activity) and closes the app instead of returning to the
    // main Hub screen — the TopAppBar's back IconButton already calls onBack, but only the
    // gesture/hardware-button path was missing it (same fix already applied to HubSettingsScreen).
    BackHandler(onBack = onBack)

    fun refreshPeers() {
        peers = peerStore.getPeers()
    }

    fun runSyncNow(peer: PairedPeer) {
        peerSyncStates = peerSyncStates + (peer.peerId to PeerSyncState.Syncing)
        scope.launch {
            val result = SyncOrchestrator(context, peerStore).syncNow(peer)
            peerSyncStates = peerSyncStates + (peer.peerId to PeerSyncState.Done(result))
            refreshPeers()
        }
    }

    val enableBluetoothLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        // Whether the user granted it or not, attempt the sync — a decline just means
        // SyncOrchestrator fails fast with a clear "couldn't establish a connection" result.
        pendingSyncPeer?.let { runSyncNow(it) }
        pendingSyncPeer = null
    }

    val syncPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        val peer = pendingSyncPeer
        if (granted.values.all { it } && peer != null) {
            val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
            if (adapter != null && !adapter.isEnabled) {
                enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            } else {
                pendingSyncPeer = null
                runSyncNow(peer)
            }
        } else {
            pendingSyncPeer = null
            peerSyncStates = peerSyncStates + (
                (peer?.peerId ?: "") to PeerSyncState.Done(SyncSessionResult.Failure(languageManager.getString("sync_permission_required")))
            )
        }
    }

    fun requestSyncNow(peer: PairedPeer) {
        val missing = requiredBluetoothPermissions().filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            pendingSyncPeer = peer
            syncPermissionLauncher.launch(missing.toTypedArray())
            return
        }
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        if (adapter != null && !adapter.isEnabled) {
            pendingSyncPeer = peer
            enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        } else {
            runSyncNow(peer)
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.all { it }) {
            uiState = PairingUiState.WaitingForTap
        } else {
            uiState = PairingUiState.Error(languageManager.getString("sync_permission_required"))
        }
    }

    val discoverableLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        // Result code is irrelevant here — ACTION_REQUEST_DISCOVERABLE returns the granted duration
        // (or RESULT_CANCELED if the user declined); either way we just proceed to advertise, and a
        // decline simply means resolveMac() on the peer's side will time out with no match.
    }

    val reader = remember {
        NfcPairingReader(peerStore) { result ->
            // onTagDiscovered/onResult fire on a Binder thread — hop back to the main thread before
            // touching Compose state.
            scope.launch {
                when (result) {
                    is PairingResult.Success -> {
                        uiState = PairingUiState.ResolvingBluetooth
                        BluetoothPeerResolver.resolveMac(context, result.peer.peerId) { mac ->
                            val finalPeer = if (mac != null) {
                                result.peer.copy(bluetoothMac = mac).also { peerStore.upsertPeer(it) }
                            } else {
                                result.peer
                            }
                            refreshPeers()
                            uiState = if (mac != null) {
                                PairingUiState.Success(finalPeer)
                            } else {
                                PairingUiState.Error(languageManager.getString("sync_bluetooth_not_found"))
                            }
                        }
                    }
                    is PairingResult.Failure -> uiState = PairingUiState.Error(result.reason)
                }
            }
        }
    }

    // Enable NFC reader mode for as long as this screen is showing and we're actively waiting for a
    // tap — disabled again on Dispose (screen closed) or once we leave the WaitingForTap state.
    DisposableEffect(uiState, activity) {
        if (uiState is PairingUiState.WaitingForTap && activity != null) {
            reader.enable(activity)
        }
        onDispose {
            activity?.let { reader.disable(it) }
        }
    }

    // The discoverability dance: become discoverable under the advertised name long enough for the
    // other phone's scan to resolve this adapter's MAC, then restore the original Bluetooth name.
    // Shared between the tap event below and the per-peer "advertise" button (the recovery path for
    // a pairing whose window was missed — e.g. this screen wasn't open on the tapped phone).
    suspend fun runAdvertiseWindow() {
        discoverableLauncher.launch(BluetoothPeerResolver.buildDiscoverableIntent())
        val originalName = BluetoothPeerResolver.advertiseAs(context, peerStore.localPeerId)
        delay(DISCOVERY_WINDOW_MS)
        BluetoothPeerResolver.restoreName(context, originalName)
    }

    // The client-role recovery twin: re-scan for the peer's advertised name and cache its MAC —
    // the same resolution pairing runs, offered again for a peer stuck without an address.
    fun runFindDevice(peer: PairedPeer) {
        uiState = PairingUiState.ResolvingBluetooth
        BluetoothPeerResolver.resolveMac(context, peer.peerId) { mac ->
            if (mac != null) {
                peerStore.getPeer(peer.peerId)?.let { peerStore.upsertPeer(it.copy(bluetoothMac = mac)) }
            }
            refreshPeers()
            uiState = if (mac != null) {
                PairingUiState.Success(peerStore.getPeer(peer.peerId) ?: peer)
            } else {
                PairingUiState.Error(languageManager.getString("sync_bluetooth_not_found"))
            }
        }
    }

    // The passive/HCE ("card") side of a tap: PairingHceService already persisted the peer by the
    // time this fires — all that's left is the discoverability dance so the *other* phone's discovery
    // scan can resolve our MAC.
    LaunchedEffect(Unit) {
        PairingEvents.events.collect { event ->
            when (event) {
                is PairingEvent.ReceivedAsServer -> {
                    uiState = PairingUiState.ResolvingBluetooth
                    runAdvertiseWindow()
                    refreshPeers()
                    uiState = PairingUiState.Success(event.peer)
                }
            }
        }
    }

    editingScopeForPeer?.let { peer ->
        SyncScopeScreen(
            peer = peer,
            peerStore = peerStore,
            onBack = {
                editingScopeForPeer = null
                refreshPeers()
            }
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(languageManager.getString("sync_title")) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = languageManager.getString("back"))
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            // The name this phone introduces itself by — what the other device's lists show as a
            // record's provenance. Saved as typed; reaches the peer at the next tap or session.
            var deviceName by remember { mutableStateOf(peerStore.localDeviceName) }
            OutlinedTextField(
                value = deviceName,
                onValueChange = {
                    deviceName = it
                    peerStore.localDeviceName = it
                },
                label = { Text(languageManager.getString("sync_device_name_label")) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
            )

            when (val state = uiState) {
                is PairingUiState.Idle -> {
                    Button(onClick = {
                        val missing = requiredBluetoothPermissions().filter {
                            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
                        }
                        if (missing.isEmpty()) {
                            uiState = PairingUiState.WaitingForTap
                        } else {
                            permissionLauncher.launch(missing.toTypedArray())
                        }
                    }) {
                        Icon(Icons.Filled.Nfc, contentDescription = null)
                        Text(languageManager.getString("sync_pair_button"), modifier = Modifier.padding(start = 8.dp))
                    }
                }
                is PairingUiState.WaitingForTap -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.padding(end = 12.dp))
                        Text(languageManager.getString("sync_waiting_for_tap"))
                    }
                    TextButton(onClick = { uiState = PairingUiState.Idle }) {
                        Text(languageManager.getString("cancel"))
                    }
                }
                is PairingUiState.ResolvingBluetooth -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.padding(end = 12.dp))
                        Text(languageManager.getString("sync_resolving_bluetooth"))
                    }
                }
                is PairingUiState.Success -> {
                    Text(
                        String.format(languageManager.getString("sync_paired_success"), state.peer.label),
                        color = MaterialTheme.colorScheme.primary
                    )
                    TextButton(onClick = { uiState = PairingUiState.Idle }) {
                        Text(languageManager.getString("cancel"))
                    }
                }
                is PairingUiState.Error -> {
                    Text(state.message, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { uiState = PairingUiState.Idle }) {
                        Text(languageManager.getString("cancel"))
                    }
                }
            }

            Text(
                languageManager.getString("sync_paired_devices_section"),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 24.dp, bottom = 8.dp)
            )
            if (peers.isEmpty()) {
                Text(languageManager.getString("sync_no_paired_devices"))
            } else {
                LazyColumn {
                    items(peers, key = { it.peerId }) { peer ->
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            colors = CardDefaults.cardColors()
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Text(peer.label, style = MaterialTheme.typography.bodyLarge)
                                        Text(
                                            peer.bluetoothMac ?: languageManager.getString("sync_mac_pending"),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        // The manual choreography has an order: the listening side
                                        // must start first — say which side this phone is.
                                        Text(
                                            languageManager.getString(
                                                if (peer.isServerRole) "sync_role_server_hint" else "sync_role_client_hint"
                                            ),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        IconButton(onClick = { requestSyncNow(peer) }) {
                                            Icon(Icons.Filled.Sync, contentDescription = languageManager.getString("sync_now_button"))
                                        }
                                        IconButton(onClick = { editingScopeForPeer = peer }) {
                                            Icon(Icons.Filled.Tune, contentDescription = languageManager.getString("sync_scope_button"))
                                        }
                                        TextButton(onClick = {
                                            peerStore.removePeer(peer.peerId)
                                            refreshPeers()
                                        }) {
                                            Text(languageManager.getString("sync_remove_device"))
                                        }
                                    }
                                }

                                when (val syncState = peerSyncStates[peer.peerId]) {
                                    is PeerSyncState.Syncing -> Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(top = 8.dp)
                                    ) {
                                        CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp).size(16.dp))
                                        Text(languageManager.getString("sync_in_progress"), style = MaterialTheme.typography.bodySmall)
                                    }
                                    is PeerSyncState.Done -> when (val result = syncState.result) {
                                        is SyncSessionResult.Success -> {
                                            val successCount = result.appResults.count { it.success }
                                            Text(
                                                String.format(
                                                    languageManager.getString("sync_apps_synced"),
                                                    successCount, result.appResults.size
                                                ),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = if (successCount == result.appResults.size) {
                                                    MaterialTheme.colorScheme.primary
                                                } else {
                                                    MaterialTheme.colorScheme.error
                                                },
                                                modifier = Modifier.padding(top = 8.dp)
                                            )
                                            // The count alone can't say WHICH app failed or why —
                                            // an app locked behind biometrics reads completely
                                            // differently from a transport error, and the per-app
                                            // summary is where that difference lives.
                                            result.appResults.forEach { app ->
                                                Text(
                                                    "${app.label}: ${app.summary}",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = if (app.success) {
                                                        MaterialTheme.colorScheme.onSurfaceVariant
                                                    } else {
                                                        MaterialTheme.colorScheme.error
                                                    },
                                                    modifier = Modifier.padding(top = 2.dp)
                                                )
                                            }
                                        }
                                        is SyncSessionResult.Failure -> Text(
                                            result.reason,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.padding(top = 8.dp)
                                        )
                                    }
                                    null -> {}
                                }

                                // Recovery for a pairing whose discovery window was missed, and for
                                // an address gone stale: the listening side re-advertises, the
                                // connecting side re-scans — the same resolution pairing runs.
                                Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                                    if (peer.isServerRole) {
                                        TextButton(onClick = { scope.launch { runAdvertiseWindow() } }) {
                                            Text(languageManager.getString("sync_advertise_button"))
                                        }
                                    } else {
                                        TextButton(onClick = { runFindDevice(peer) }) {
                                            Text(languageManager.getString("sync_find_device_button"))
                                        }
                                    }
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(languageManager.getString("sync_auto_label"), style = MaterialTheme.typography.bodySmall)
                                    Switch(
                                        checked = peer.autoSyncEnabled,
                                        onCheckedChange = { enabled ->
                                            peerStore.upsertPeer(peer.copy(autoSyncEnabled = enabled))
                                            // The aligned alarm chain follows the enabled set —
                                            // re-arm (or cancel) it the moment that set changes.
                                            SyncAlarmScheduler.ensureScheduled(context)
                                            refreshPeers()
                                        }
                                    )
                                }
                                if (peer.autoSyncEnabled) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        AUTO_SYNC_INTERVAL_OPTIONS.forEach { minutes ->
                                            FilterChip(
                                                selected = peer.autoSyncIntervalMinutes == minutes,
                                                onClick = {
                                                    peerStore.upsertPeer(peer.copy(autoSyncIntervalMinutes = minutes))
                                                    SyncAlarmScheduler.ensureScheduled(context)
                                                    refreshPeers()
                                                },
                                                label = { Text(String.format(languageManager.getString("sync_interval_minutes"), minutes)) }
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
    }
}

private const val DISCOVERY_WINDOW_MS = 30_000L
