package com.voxapps.hub.ui

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import com.voxapps.hub.domain.sync.SyncPeerStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private sealed interface PairingUiState {
    data object Idle : PairingUiState
    data object WaitingForTap : PairingUiState
    data object ResolvingBluetooth : PairingUiState
    data class Success(val peer: PairedPeer) : PairingUiState
    data class Error(val message: String) : PairingUiState
}

private fun requiredBluetoothPermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= 31) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

/**
 * Phase D's minimal, testable surface: pair a new device over NFC (see NfcPairingReader/
 * PairingHceService) and show what's already paired. Deliberately doesn't attempt an actual data
 * sync yet — connecting the socket and running OP_SYNC_EXPORT/OP_SYNC_MERGE over it is Phase E; the
 * per-peer autoSyncEnabled/scopeNamesByApp fields already exist on PairedPeer so that phase won't
 * need another migration, but this screen has no controls for them yet.
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

    fun refreshPeers() {
        peers = peerStore.getPeers()
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

    // The passive/HCE ("card") side of a tap: PairingHceService already persisted the peer by the
    // time this fires — all that's left is the discoverability dance so the *other* phone's discovery
    // scan can resolve our MAC, then restore our original Bluetooth name once that window closes.
    LaunchedEffect(Unit) {
        PairingEvents.events.collect { event ->
            when (event) {
                is PairingEvent.ReceivedAsServer -> {
                    uiState = PairingUiState.ResolvingBluetooth
                    discoverableLauncher.launch(BluetoothPeerResolver.buildDiscoverableIntent())
                    val originalName = BluetoothPeerResolver.advertiseAs(context, peerStore.localPeerId)
                    delay(DISCOVERY_WINDOW_MS)
                    BluetoothPeerResolver.restoreName(context, originalName)
                    refreshPeers()
                    uiState = PairingUiState.Success(event.peer)
                }
            }
        }
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
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.padding(end = 12.dp))
                        Text(languageManager.getString("sync_waiting_for_tap"))
                    }
                    TextButton(onClick = { uiState = PairingUiState.Idle }) {
                        Text(languageManager.getString("cancel"))
                    }
                }
                is PairingUiState.ResolvingBluetooth -> {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
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
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(peer.label, style = MaterialTheme.typography.bodyLarge)
                                    Text(
                                        if (peer.bluetoothMac != null) peer.bluetoothMac else languageManager.getString("sync_mac_pending"),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                TextButton(onClick = {
                                    peerStore.removePeer(peer.peerId)
                                    refreshPeers()
                                }) {
                                    Text(languageManager.getString("sync_remove_device"))
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
