package com.voxapps.ipc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoxLlmResultTest {

    @Test
    fun `success round-trips with rawJson`() {
        val result = VoxLlmResult(
            task = "CATEGORY_DEDUPLICATION",
            status = VoxLlmResult.STATUS_SUCCESS,
            rawJson = """{"Groceries":"Cumpărături"}"""
        )
        val parsed = VoxLlmResult.fromJson(result.toJson())
        assertEquals(result, parsed)
    }

    @Test
    fun `error round-trips with error message`() {
        val result = VoxLlmResult(
            task = "CATEGORY_DEDUPLICATION",
            status = VoxLlmResult.STATUS_ERROR,
            error = "No LLM engine configured"
        )
        val parsed = VoxLlmResult.fromJson(result.toJson())!!
        assertEquals(VoxLlmResult.STATUS_ERROR, parsed.status)
        assertEquals("No LLM engine configured", parsed.error)
        assertNull(parsed.rawJson)
    }

    @Test
    fun `null and blank and malformed payloads parse to null`() {
        assertNull(VoxLlmResult.fromJson(null))
        assertNull(VoxLlmResult.fromJson(""))
        assertNull(VoxLlmResult.fromJson("{ not json"))
        assertNull(VoxLlmResult.fromJson("{}")) // missing task/status
    }
}
