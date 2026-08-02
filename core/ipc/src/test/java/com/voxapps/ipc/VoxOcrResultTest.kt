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
    fun `round-trips a multi-shot result with several imageUris and no rawText`() {
        val result = VoxOcrResult(
            task = "EXPENSE_ATTACHMENT_CAPTURE:42",
            status = VoxOcrResult.STATUS_SUCCESS,
            imageUris = listOf(
                "content://com.voxapps.vision.fileprovider/scan_1.jpg",
                "content://com.voxapps.vision.fileprovider/scan_2.jpg",
                "content://com.voxapps.vision.fileprovider/scan_3.jpg"
            )
        )
        val parsed = VoxOcrResult.fromJson(result.toJson())
        assertEquals(result, parsed)
        assertNull(parsed!!.rawText)
        assertEquals(3, parsed.imageUris.size)
    }

    @Test
    fun `imageUris defaults to empty list`() {
        val result = VoxOcrResult(task = "NOTE_SCAN", status = VoxOcrResult.STATUS_SUCCESS, rawText = "Lapte 3.50 lei")
        val parsed = VoxOcrResult.fromJson(result.toJson())!!
        assertEquals(emptyList<String>(), parsed.imageUris)
    }

    @Test
    fun `imageUris defaults to empty list when absent from JSON`() {
        val parsed = VoxOcrResult.fromJson("""{"task":"x","status":"SUCCESS"}""")!!
        assertEquals(emptyList<String>(), parsed.imageUris)
    }

    @Test
    fun `round-trips a stitch result with several imageUris and one combined rawText`() {
        val result = VoxOcrResult(
            task = "EXPENSE_ATTACHMENT_CAPTURE:42",
            status = VoxOcrResult.STATUS_SUCCESS,
            rawText = "--- Page 1 ---\nLapte 3.50 lei\n\n--- Page 2 ---\nPaine 2.00 lei",
            imageUris = listOf(
                "content://com.voxapps.vision.fileprovider/scan_1.jpg",
                "content://com.voxapps.vision.fileprovider/scan_2.jpg"
            )
        )
        val parsed = VoxOcrResult.fromJson(result.toJson())
        assertEquals(result, parsed)
        assertEquals(2, parsed!!.imageUris.size)
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
