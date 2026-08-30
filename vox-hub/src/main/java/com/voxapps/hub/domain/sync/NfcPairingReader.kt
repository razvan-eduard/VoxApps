package com.voxapps.hub.domain.sync

import android.app.Activity
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.util.Base64
import com.voxapps.logging.Logger
import org.json.JSONObject
import java.security.SecureRandom

sealed interface PairingResult {
    data class Success(val peer: PairedPeer) : PairingResult
    data class Failure(val reason: String) : PairingResult
}

/**
 * The active ("reader"/PCD) side of an NFC pairing tap — driven from whichever phone's user opened
 * Hub's pairing screen and is holding it near the other device. See [NfcPairingProtocol] for the
 * exchange this runs, and [PairedPeer]'s doc comment for why the client (this side) is the one that
 * ends up owning the peer's identity while the MAC itself is resolved separately, afterward, by
 * [BluetoothPeerResolver] — not here.
 */
class NfcPairingReader(
    private val peerStore: SyncPeerStore,
    private val onResult: (PairingResult) -> Unit
) : NfcAdapter.ReaderCallback {

    fun enable(activity: Activity) {
        val adapter = NfcAdapter.getDefaultAdapter(activity) ?: run {
            onResult(PairingResult.Failure("NFC not available on this device"))
            return
        }
        adapter.enableReaderMode(
            activity,
            this,
            NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,
            null
        )
    }

    fun disable(activity: Activity) {
        NfcAdapter.getDefaultAdapter(activity)?.disableReaderMode(activity)
    }

    override fun onTagDiscovered(tag: Tag) {
        val isoDep = IsoDep.get(tag) ?: run {
            onResult(PairingResult.Failure("Tapped device doesn't support the required NFC mode"))
            return
        }
        runCatching {
            isoDep.connect()
            isoDep.use { runExchange(it) }
        }.onFailure {
            Logger.e("NfcPairingReader", "NFC exchange failed", it)
            onResult(PairingResult.Failure(it.message ?: "NFC exchange failed"))
        }
    }

    private fun runExchange(isoDep: IsoDep) {
        val selectResponse = isoDep.transceive(NfcPairingProtocol.buildSelectApdu())
        if (!NfcPairingProtocol.isSuccessResponse(selectResponse)) {
            onResult(PairingResult.Failure("The other device didn't respond to Vox Hub's pairing request"))
            return
        }

        val helloResponse = isoDep.transceive(NfcPairingProtocol.buildHelloCommand())
        if (!NfcPairingProtocol.isSuccessResponse(helloResponse)) {
            onResult(PairingResult.Failure("Pairing handshake was rejected"))
            return
        }
        val hello = JSONObject(String(NfcPairingProtocol.extractResponseData(helloResponse), Charsets.UTF_8))
        val remotePeerId = hello.getString("peerId")
        val remoteName = hello.optString("name").takeIf { it.isNotBlank() } ?: "Vox device"

        val sharedKey = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val sharedKeyBase64 = Base64.encodeToString(sharedKey, Base64.NO_WRAP)
        val pairPayload = JSONObject()
            .put("peerId", peerStore.localPeerId)
            .put("key", sharedKeyBase64)
            .put("name", peerStore.localDeviceName)
            .toString()
            .toByteArray(Charsets.UTF_8)

        val pairResponse = isoDep.transceive(NfcPairingProtocol.buildPairCommand(pairPayload))
        if (!NfcPairingProtocol.isSuccessResponse(pairResponse)) {
            onResult(PairingResult.Failure("The other device rejected the pairing key"))
            return
        }

        val peer = PairedPeer(
            peerId = remotePeerId,
            label = remoteName,
            isServerRole = false,
            sharedKeyBase64 = sharedKeyBase64,
            bluetoothMac = null,
            pairedAt = System.currentTimeMillis()
        )
        // A re-tap of an already-known phone rotates the key but keeps everything the relationship
        // accumulated — see SyncPeerStore.upsertPairing.
        peerStore.upsertPairing(peer)
        onResult(PairingResult.Success(peerStore.getPeer(remotePeerId) ?: peer))
    }
}
