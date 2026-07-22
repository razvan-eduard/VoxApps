package com.voxapps.hub.domain.sync

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NfcPairingProtocolTest {

    @Test
    fun `select apdu carries the AID bytes with a trailing Le`() {
        val apdu = NfcPairingProtocol.buildSelectApdu()

        // 00 A4 04 00 <Lc> <AID> 00
        assertEquals(0x00.toByte(), apdu[0])
        assertEquals(0xA4.toByte(), apdu[1])
        val aidLength = apdu[4].toInt() and 0xFF
        assertEquals(aidLength, (NfcPairingProtocol.AID_HEX.length / 2))
        assertEquals(0x00.toByte(), apdu.last())
    }

    @Test
    fun `hello command is recognized by isHelloCommand and not isPairCommand`() {
        val hello = NfcPairingProtocol.buildHelloCommand()

        assertTrue(NfcPairingProtocol.isHelloCommand(hello))
        assertFalse(NfcPairingProtocol.isPairCommand(hello))
    }

    @Test
    fun `pair command is recognized by isPairCommand and not isHelloCommand`() {
        val pair = NfcPairingProtocol.buildPairCommand("{}".toByteArray())

        assertFalse(NfcPairingProtocol.isHelloCommand(pair))
        assertTrue(NfcPairingProtocol.isPairCommand(pair))
    }

    @Test
    fun `extractPairPayload round-trips the original payload bytes`() {
        val payload = """{"peerId":"abc-123","key":"deadbeef"}""".toByteArray(Charsets.UTF_8)
        val command = NfcPairingProtocol.buildPairCommand(payload)

        assertArrayEquals(payload, NfcPairingProtocol.extractPairPayload(command))
    }

    @Test
    fun `a status-OK response is recognized as success and its data is extracted`() {
        val data = "hello".toByteArray(Charsets.UTF_8)
        val response = data + NfcPairingProtocol.STATUS_OK

        assertTrue(NfcPairingProtocol.isSuccessResponse(response))
        assertArrayEquals(data, NfcPairingProtocol.extractResponseData(response))
    }

    @Test
    fun `a status-FAIL response is not recognized as success`() {
        val response = "oops".toByteArray(Charsets.UTF_8) + NfcPairingProtocol.STATUS_FAIL

        assertFalse(NfcPairingProtocol.isSuccessResponse(response))
    }

    @Test
    fun `an empty or truncated response is never mistaken for success`() {
        assertFalse(NfcPairingProtocol.isSuccessResponse(ByteArray(0)))
        assertFalse(NfcPairingProtocol.isSuccessResponse(byteArrayOf(0x90.toByte())))
    }
}
