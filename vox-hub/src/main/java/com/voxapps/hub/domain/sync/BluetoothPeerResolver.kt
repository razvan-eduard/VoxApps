package com.voxapps.hub.domain.sync

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper

/**
 * Bridges an NFC-exchanged [PairedPeer.peerId] to an actual, connectable Bluetooth MAC address —
 * necessary because Android forbids an app from reading its own adapter's address (see
 * [PairedPeer]'s doc comment), so the CLIENT side has to *discover* the SERVER side instead of being
 * told its MAC directly. Both halves are one-time, pairing-only steps: once [resolveMac] succeeds,
 * the MAC is cached on [PairedPeer.bluetoothMac] and every later sync (menu-triggered or scheduled)
 * connects to it directly — no repeat discovery, no repeat discoverability prompt.
 *
 * Every call here assumes [SyncScreen] already obtained BLUETOOTH_SCAN/CONNECT/ADVERTISE (API 31+) or
 * ACCESS_FINE_LOCATION (below) before invoking it — but each Bluetooth API call is still wrapped in a
 * literal try/catch for [SecurityException], both because a permission can be revoked between the
 * check and the call, and because lint's static MissingPermission check specifically requires a
 * syntactic catch block here (it doesn't trust `runCatching`, or a caller-side check it can't trace).
 */
object BluetoothPeerResolver {
    private const val NAME_PREFIX = "VoxHub-"
    private const val NAME_TAG_LENGTH = 12
    private const val DEFAULT_DISCOVERABLE_SECONDS = 120
    private const val DEFAULT_RESOLVE_TIMEOUT_MS = 20_000L

    private fun advertisedNameFor(peerId: String): String =
        NAME_PREFIX + peerId.replace("-", "").take(NAME_TAG_LENGTH)

    fun buildDiscoverableIntent(durationSeconds: Int = DEFAULT_DISCOVERABLE_SECONDS): Intent =
        Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
            .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, durationSeconds)

    private fun adapterOf(context: Context): BluetoothAdapter? =
        (context.applicationContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    /**
     * Server-role step: called once the user has granted the [buildDiscoverableIntent] request, sets
     * this device's classic Bluetooth name to something [resolveMac] on the peer can recognize.
     * Returns the previous name so the caller can restore it via [restoreName] once the discovery
     * window has closed. Requires BLUETOOTH_CONNECT (API 31+) to already be granted.
     */
    fun advertiseAs(context: Context, localPeerId: String): String? {
        val adapter = adapterOf(context) ?: return null
        return try {
            val originalName = adapter.name
            adapter.name = advertisedNameFor(localPeerId)
            originalName
        } catch (e: SecurityException) {
            null
        }
    }

    fun restoreName(context: Context, originalName: String?) {
        if (originalName == null) return
        val adapter = adapterOf(context) ?: return
        try {
            adapter.name = originalName
        } catch (e: SecurityException) {
            // Best-effort cosmetic restore — nothing depends on this succeeding.
        }
    }

    /**
     * Client-role step: scans for [remotePeerId]'s [advertiseAs]-set device name and resolves its
     * MAC. Requires BLUETOOTH_SCAN (API 31+) / ACCESS_FINE_LOCATION (below) to already be granted.
     * [onResult] fires on the main thread with the resolved MAC, or null on timeout/no match.
     */
    fun resolveMac(
        context: Context,
        remotePeerId: String,
        timeoutMs: Long = DEFAULT_RESOLVE_TIMEOUT_MS,
        onResult: (String?) -> Unit
    ) {
        val adapter = adapterOf(context)
        if (adapter == null) {
            onResult(null)
            return
        }
        val targetName = advertisedNameFor(remotePeerId)
        val handler = Handler(Looper.getMainLooper())
        // AtomicBoolean rather than a captured `var`: finish() is reached from the discovery
        // receiver, the timeout post, and the startDiscovery SecurityException path, and it must
        // run its teardown (cancelDiscovery / unregisterReceiver / onResult) exactly once —
        // unregistering twice throws. compareAndSet states that directly instead of relying on
        // every caller happening to land on the main thread.
        val resolved = java.util.concurrent.atomic.AtomicBoolean(false)

        lateinit var receiver: BroadcastReceiver
        fun finish(mac: String?) {
            if (!resolved.compareAndSet(false, true)) return
            try {
                adapter.cancelDiscovery()
            } catch (e: SecurityException) {
                // Already stopped, or permission was revoked mid-scan — either way, nothing to do.
            }
            runCatching { context.unregisterReceiver(receiver) }
            handler.removeCallbacksAndMessages(null)
            onResult(mac)
        }

        receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context, intent: Intent) {
                when (intent.action) {
                    BluetoothDevice.ACTION_FOUND -> {
                        val device = intent.parcelableExtraCompat(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                            ?: return
                        try {
                            if (device.name == targetName) finish(device.address)
                        } catch (e: SecurityException) {
                            // Can't read this candidate's name/address — just skip it.
                        }
                    }
                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> finish(null)
                }
            }
        }
        context.registerReceiver(
            receiver,
            IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_FOUND)
                addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            }
        )
        handler.postDelayed({ finish(null) }, timeoutMs)
        try {
            adapter.startDiscovery()
        } catch (e: SecurityException) {
            finish(null)
        }
    }
}

@Suppress("DEPRECATION")
private fun <T> Intent.parcelableExtraCompat(name: String, clazz: Class<T>): T? =
    if (Build.VERSION.SDK_INT >= 33) getParcelableExtra(name, clazz) else getParcelableExtra(name)
