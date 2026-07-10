package com.voxapps.ipc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoxOcrRequestTest {

    @Test
    fun `round-trips with hint`() {
        val req = VoxOcrRequest(
            sourcePackage = "com.voxapps.notes",
            task = "NOTE_SCAN",
            hint = "Scanning for Notes"
        )
        val parsed = VoxOcrRequest.fromJson(req.toJson())
        assertEquals(req, parsed)
    }

    @Test
    fun `round-trips without hint`() {
        val req = VoxOcrRequest(sourcePackage = "com.voxapps.notes", task = "NOTE_SCAN")
        val parsed = VoxOcrRequest.fromJson(req.toJson())!!
        assertEquals(req, parsed)
        assertNull(parsed.hint)
    }

    @Test
    fun `null and blank and malformed payloads parse to null`() {
        assertNull(VoxOcrRequest.fromJson(null))
        assertNull(VoxOcrRequest.fromJson(""))
        assertNull(VoxOcrRequest.fromJson("{ not json"))
    }

    @Test
    fun `missing required fields parse to null`() {
        assertNull(VoxOcrRequest.fromJson("""{"task":"x"}"""))
        assertNull(VoxOcrRequest.fromJson("""{"sourcePackage":"x"}"""))
    }
}
