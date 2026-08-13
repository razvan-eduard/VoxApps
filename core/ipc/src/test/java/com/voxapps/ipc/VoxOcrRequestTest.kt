package com.voxapps.ipc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    fun `round-trips with imageUri`() {
        val req = VoxOcrRequest(
            sourcePackage = "com.voxapps.expenses",
            task = "EXPENSE_LINEITEMS_RESCAN:42",
            imageUri = "content://com.voxapps.expenses.fileprovider/attachments/att_1.jpg"
        )
        val parsed = VoxOcrRequest.fromJson(req.toJson())
        assertEquals(req, parsed)
    }

    @Test
    fun `imageUri defaults to null`() {
        val req = VoxOcrRequest(sourcePackage = "com.voxapps.notes", task = "NOTE_SCAN")
        val parsed = VoxOcrRequest.fromJson(req.toJson())!!
        assertNull(parsed.imageUri)
    }

    @Test
    fun `round-trips with produceOCR false`() {
        val req = VoxOcrRequest(
            sourcePackage = "com.voxapps.calendarapp",
            task = "CALENDAR_ATTACHMENT_CAPTURE:7",
            produceOCR = false
        )
        val parsed = VoxOcrRequest.fromJson(req.toJson())
        assertEquals(req, parsed)
        assertFalse(parsed!!.produceOCR)
    }

    @Test
    fun `produceOCR defaults to true`() {
        val req = VoxOcrRequest(sourcePackage = "com.voxapps.notes", task = "NOTE_SCAN")
        val parsed = VoxOcrRequest.fromJson(req.toJson())!!
        assertTrue(parsed.produceOCR)
    }

    @Test
    fun `produceOCR defaults to true when absent from JSON`() {
        val parsed = VoxOcrRequest.fromJson("""{"sourcePackage":"com.voxapps.notes","task":"NOTE_SCAN"}""")!!
        assertTrue(parsed.produceOCR)
    }

    @Test
    fun `round-trips with captureMode batch`() {
        val req = VoxOcrRequest(
            sourcePackage = "com.voxapps.expenses",
            task = "EXPENSE_SCAN_CLEANUP:pending-batch",
            captureMode = VoxOcrRequest.CAPTURE_MODE_BATCH
        )
        val parsed = VoxOcrRequest.fromJson(req.toJson())
        assertEquals(req, parsed)
        assertEquals(VoxOcrRequest.CAPTURE_MODE_BATCH, parsed!!.captureMode)
    }

    @Test
    fun `round-trips with captureMode stitch`() {
        val req = VoxOcrRequest(
            sourcePackage = "com.voxapps.expenses",
            task = "EXPENSE_ATTACHMENT_CAPTURE:42",
            captureMode = VoxOcrRequest.CAPTURE_MODE_STITCH
        )
        val parsed = VoxOcrRequest.fromJson(req.toJson())
        assertEquals(req, parsed)
        assertEquals(VoxOcrRequest.CAPTURE_MODE_STITCH, parsed!!.captureMode)
    }

    @Test
    fun `captureMode defaults to single`() {
        val req = VoxOcrRequest(sourcePackage = "com.voxapps.notes", task = "NOTE_SCAN")
        val parsed = VoxOcrRequest.fromJson(req.toJson())!!
        assertEquals(VoxOcrRequest.CAPTURE_MODE_SINGLE, parsed.captureMode)
    }

    @Test
    fun `captureMode defaults to single when absent from JSON`() {
        val parsed = VoxOcrRequest.fromJson("""{"sourcePackage":"com.voxapps.notes","task":"NOTE_SCAN"}""")!!
        assertEquals(VoxOcrRequest.CAPTURE_MODE_SINGLE, parsed.captureMode)
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

    @Test
    fun `tableMode survives the round trip`() {
        val req = VoxOcrRequest(
            sourcePackage = "com.voxapps.expenses",
            task = "EXPENSE_SCAN_CLEANUP:pending-create",
            tableMode = true
        )
        assertEquals(true, VoxOcrRequest.fromJson(req.toJson())!!.tableMode)
    }

    @Test
    fun `a payload from before the field reads as tableMode false`() {
        // Backward compatibility: an older satellite's JSON simply lacks the key.
        val legacy = """{"sourcePackage":"com.voxapps.notes","task":"NOTE_SCAN"}"""
        assertEquals(false, VoxOcrRequest.fromJson(legacy)!!.tableMode)
    }

}
