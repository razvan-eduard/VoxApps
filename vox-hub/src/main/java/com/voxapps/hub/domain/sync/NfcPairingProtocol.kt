package com.voxapps.hub.domain.sync

/**
 * The tiny custom APDU exchange run over NFC (IsoDep) between [PairingHceService] (the tapped,
 * passive side — plays ISO 7816 "card") and [NfcPairingReader] (the initiating side — plays "reader",
 * via `NfcAdapter.enableReaderMode`). Two round trips after SELECT:
 *
 * 1. Reader sends [buildHelloCommand] (no data). Card replies with its own `{"peerId"}` JSON.
 * 2. Reader generates a fresh AES-256 key, sends [buildPairCommand] with `{"peerId","key"}` JSON
 *    (its own identity + the new shared key). Card stores it and acks.
 *
 * Deliberately carries no Bluetooth MAC — Android forbids an app from reading its own adapter address
 * (see [PairedPeer]'s doc comment), so MAC resolution happens afterward via [BluetoothPeerResolver],
 * not over NFC.
 */
object NfcPairingProtocol {
    /** Proprietary AID (starts with F0 per ISO 7816-5 §8.2.1.2) — must match `res/xml/apduservice.xml`
     *  byte-for-byte (that file is the source of truth Android uses for AID routing; this constant is
     *  just what we send in the SELECT command). */
    const val AID_HEX = "F0564F5841505053"

    private const val CLA_PROPRIETARY: Byte = 0x80.toByte()
    private const val INS_HELLO: Byte = 0xC0.toByte()
    private const val INS_PAIR: Byte = 0xC1.toByte()

    val STATUS_OK: ByteArray = byteArrayOf(0x90.toByte(), 0x00)
    val STATUS_FAIL: ByteArray = byteArrayOf(0x6F.toByte(), 0x00)

    fun buildSelectApdu(): ByteArray {
        val aid = hexToBytes(AID_HEX)
        return byteArrayOf(0x00, 0xA4.toByte(), 0x04, 0x00, aid.size.toByte()) + aid + byteArrayOf(0x00)
    }

    fun buildHelloCommand(): ByteArray = byteArrayOf(CLA_PROPRIETARY, INS_HELLO, 0x00, 0x00, 0x00)

    fun buildPairCommand(payload: ByteArray): ByteArray =
        byteArrayOf(CLA_PROPRIETARY, INS_PAIR, 0x00, 0x00, payload.size.toByte()) + payload + byteArrayOf(0x00)

    fun isHelloCommand(apdu: ByteArray): Boolean =
        apdu.size >= 2 && apdu[0] == CLA_PROPRIETARY && apdu[1] == INS_HELLO

    fun isPairCommand(apdu: ByteArray): Boolean =
        apdu.size >= 2 && apdu[0] == CLA_PROPRIETARY && apdu[1] == INS_PAIR

    /** Strips the 5-byte CLA/INS/P1/P2/Lc header off a [buildPairCommand]-shaped command APDU. */
    fun extractPairPayload(apdu: ByteArray): ByteArray {
        if (apdu.size < 5) return ByteArray(0)
        val lc = apdu[4].toInt() and 0xFF
        return apdu.copyOfRange(5, minOf(5 + lc, apdu.size))
    }

    /** Strips the 2-byte SW1/SW2 trailer a [transceive] response carries, leaving just the data. */
    fun extractResponseData(response: ByteArray): ByteArray =
        if (response.size >= 2) response.copyOfRange(0, response.size - 2) else ByteArray(0)

    fun isSuccessResponse(response: ByteArray): Boolean =
        response.size >= 2 &&
            response[response.size - 2] == STATUS_OK[0] &&
            response[response.size - 1] == STATUS_OK[1]

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { i -> ((Character.digit(hex[i * 2], 16) shl 4) + Character.digit(hex[i * 2 + 1], 16)).toByte() }
}
