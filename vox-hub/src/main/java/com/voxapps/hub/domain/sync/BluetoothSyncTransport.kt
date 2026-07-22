package com.voxapps.hub.domain.sync

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import java.io.IOException
import java.util.UUID

/**
 * Opens the actual data-transfer socket for a sync session. Deliberately **insecure** RFCOMM (no OS
 * bonding/PIN dialog) — see [PairedPeer]/[BluetoothPeerResolver]'s doc comments for why: the
 * NFC-exchanged AES key ([SyncCrypto]/[SecureSyncChannel]) is what actually secures the payload, so
 * there's nothing for OS-level pairing to add except a confirmation dialog neither side wants.
 *
 * Role is fixed at NFC-pairing time ([PairedPeer.isServerRole]) — the server always listens, the
 * client always connects to the server's already-resolved [PairedPeer.bluetoothMac].
 */
object BluetoothSyncTransport {
    /** Fixed app-level UUID for the RFCOMM SDP service record — arbitrary but must be identical on
     *  every device running this app; unrelated to [NfcPairingProtocol.AID_HEX] (different protocol
     *  layer entirely). */
    private val SYNC_SERVICE_UUID: UUID = UUID.fromString("7b3b6e0a-3f0a-4c8a-9c1e-2f5a6d7c8e9f")
    private const val SERVICE_NAME = "VoxHubSync"

    private fun adapterOf(context: Context): BluetoothAdapter? =
        (context.applicationContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    /**
     * Server-role step: opens a listening socket and blocks (up to [timeoutMs]) for the peer's
     * [connect] to arrive. Requires BLUETOOTH_CONNECT (API 31+) to already be granted.
     */
    fun listenAndAccept(context: Context, timeoutMs: Int): BluetoothSocket? {
        val adapter = adapterOf(context) ?: return null
        var serverSocket: BluetoothServerSocket? = null
        return try {
            serverSocket = adapter.listenUsingInsecureRfcommWithServiceRecord(SERVICE_NAME, SYNC_SERVICE_UUID)
            serverSocket.accept(timeoutMs)
        } catch (e: IOException) {
            null
        } catch (e: SecurityException) {
            null
        } finally {
            runCatching { serverSocket?.close() }
        }
    }

    /**
     * Client-role step: connects directly to [mac] — a previously NFC-paired-then-discovered address
     * (see [BluetoothPeerResolver]), never re-discovered here. Requires BLUETOOTH_CONNECT (API 31+)
     * to already be granted.
     */
    fun connect(context: Context, mac: String): BluetoothSocket? {
        val adapter = adapterOf(context) ?: return null
        return try {
            // Discovery (if still running from an unrelated flow) drastically slows a connect attempt
            // and isn't needed here — we already know the address.
            adapter.cancelDiscovery()
            val device = adapter.getRemoteDevice(mac)
            val socket = device.createInsecureRfcommSocketToServiceRecord(SYNC_SERVICE_UUID)
            socket.connect()
            socket
        } catch (e: IOException) {
            null
        } catch (e: SecurityException) {
            null
        } catch (e: IllegalArgumentException) {
            // getRemoteDevice throws this for a malformed address string.
            null
        }
    }
}
