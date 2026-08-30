package com.voxapps.hub.domain.sync

import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import com.voxapps.hub.HubApplication
import com.voxapps.logging.Logger
import org.json.JSONObject

/**
 * The passive ("card"/PICC) side of an NFC pairing tap — the phone being tapped doesn't need Hub
 * open at all; Android wakes/binds this service automatically when it sees our AID (see
 * `res/xml/apduservice.xml`), same mechanism contactless payment apps use. The active side is
 * [NfcPairingReader]. See [NfcPairingProtocol] for the byte-level exchange this implements.
 */
class PairingHceService : HostApduService() {

    private val peerStore by lazy { (applicationContext as HubApplication).container.syncPeerStore }

    override fun processCommandApdu(commandApdu: ByteArray, extras: Bundle?): ByteArray {
        return when {
            isSelectApdu(commandApdu) -> NfcPairingProtocol.STATUS_OK

            NfcPairingProtocol.isHelloCommand(commandApdu) -> {
                val payload = JSONObject()
                    .put("peerId", peerStore.localPeerId)
                    .put("name", peerStore.localDeviceName)
                    .toString().toByteArray(Charsets.UTF_8)
                payload + NfcPairingProtocol.STATUS_OK
            }

            NfcPairingProtocol.isPairCommand(commandApdu) -> {
                runCatching {
                    val json = JSONObject(String(NfcPairingProtocol.extractPairPayload(commandApdu), Charsets.UTF_8))
                    val peer = PairedPeer(
                        peerId = json.getString("peerId"),
                        label = json.optString("name").takeIf { it.isNotBlank() } ?: "Vox device",
                        isServerRole = true,
                        sharedKeyBase64 = json.getString("key"),
                        bluetoothMac = null,
                        pairedAt = System.currentTimeMillis()
                    )
                    // A re-tap of an already-known phone rotates the key but keeps everything the
                    // relationship accumulated — see SyncPeerStore.upsertPairing.
                    peerStore.upsertPairing(peer)
                    PairingEvents.emit(PairingEvent.ReceivedAsServer(peer))
                }.onFailure { Logger.e("PairingHceService", "Malformed PAIR payload", it) }
                NfcPairingProtocol.STATUS_OK
            }

            else -> NfcPairingProtocol.STATUS_FAIL
        }
    }

    override fun onDeactivated(reason: Int) {
        // Stateless per tap — nothing to clean up between exchanges.
    }

    private fun isSelectApdu(apdu: ByteArray): Boolean =
        apdu.size >= 2 && apdu[0] == 0x00.toByte() && apdu[1] == 0xA4.toByte()
}
