package com.voxapps.ipc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoxLlmRequestTest {

    @Test
    fun `round-trips with data list`() {
        val req = VoxLlmRequest(
            sourcePackage = "com.voxapps.notes",
            task = "CATEGORY_DEDUPLICATION",
            promptText = "Evaluate the following category list...",
            data = listOf("Groceries", "Cumpărături", "Bills")
        )
        val parsed = VoxLlmRequest.fromJson(req.toJson())
        assertEquals(req, parsed)
    }

    @Test
    fun `round-trips with empty data list`() {
        val req = VoxLlmRequest(
            sourcePackage = "com.voxapps.notes",
            task = "SUMMARIZE_NOTE",
            promptText = "Summarize this."
        )
        val parsed = VoxLlmRequest.fromJson(req.toJson())!!
        assertEquals(req, parsed)
        assertTrue(parsed.data.isEmpty())
    }

    @Test
    fun `null and blank and malformed payloads parse to null`() {
        assertNull(VoxLlmRequest.fromJson(null))
        assertNull(VoxLlmRequest.fromJson(""))
        assertNull(VoxLlmRequest.fromJson("{ not json"))
    }

    @Test
    fun `missing required fields parse to null`() {
        assertNull(VoxLlmRequest.fromJson("""{"task":"x","promptText":"y"}""")) // no sourcePackage
        assertNull(VoxLlmRequest.fromJson("""{"sourcePackage":"x","promptText":"y"}""")) // no task
        assertNull(VoxLlmRequest.fromJson("""{"sourcePackage":"x","task":"y"}""")) // no promptText
    }
}
