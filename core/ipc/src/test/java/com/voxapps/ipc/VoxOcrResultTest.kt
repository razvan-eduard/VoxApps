package com.voxapps.ipc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoxOcrResultTest {

    @Test
    fun `round-trips a success result`() {
        val result = VoxOcrResult(task = "NOTE_SCAN", status = VoxOcrResult.STATUS_SUCCESS, rawText = "Lapte 3.50 lei")
        val parsed = VoxOcrResult.fromJson(result.toJson())
        assertEquals(result, parsed)
    }

    @Test
    fun `round-trips an error result`() {
        val result = VoxOcrResult(task = "NOTE_SCAN", status = VoxOcrResult.STATUS_ERROR, error = "Camera unavailable")
        val parsed = VoxOcrResult.fromJson(result.toJson())
        assertEquals(result, parsed)
    }

    @Test
    fun `null and blank and malformed payloads parse to null`() {
        assertNull(VoxOcrResult.fromJson(null))
        assertNull(VoxOcrResult.fromJson(""))
        assertNull(VoxOcrResult.fromJson("{ not json"))
    }

    @Test
    fun `missing required fields parse to null`() {
        assertNull(VoxOcrResult.fromJson("""{"status":"SUCCESS"}"""))
        assertNull(VoxOcrResult.fromJson("""{"task":"x"}"""))
    }
}
