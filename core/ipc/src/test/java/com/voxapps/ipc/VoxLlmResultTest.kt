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

    /**
     * The echoed input, for the one path where the satellite never saw what it is being answered
     * about — Commander filled a cached template from its own decomposition.
     */
    @Test
    fun `input round-trips`() {
        val result = VoxLlmResult(
            task = "EXPENSE_PARSE",
            status = VoxLlmResult.STATUS_SUCCESS,
            rawJson = "{}",
            input = "action: create\nsubject: three loaves at ten each"
        )
        val parsed = VoxLlmResult.fromJson(result.toJson())!!
        assertEquals("action: create\nsubject: three loaves at ten each", parsed.input)
    }

    /** An error reply carries it too: a failed answer is still an answer to something, and the
     *  satellite may still want to know what it asked. */
    @Test
    fun `input survives an error reply`() {
        val result = VoxLlmResult(
            task = "EXPENSE_PARSE",
            status = VoxLlmResult.STATUS_ERROR,
            error = "engine busy",
            input = "the words"
        )
        assertEquals("the words", VoxLlmResult.fromJson(result.toJson())!!.input)
    }

    /** Absent from an older Commander's reply, and absent on the ordinary path — both read as "I did
     *  not compose this", which is exactly what the satellites branch on. */
    @Test
    fun `input is null when not echoed`() {
        val json = """{"task":"EXPENSE_PARSE","status":"SUCCESS","rawJson":"{}"}"""
        assertNull(VoxLlmResult.fromJson(json)!!.input)
    }

    @Test
    fun `null and blank and malformed payloads parse to null`() {
        assertNull(VoxLlmResult.fromJson(null))
        assertNull(VoxLlmResult.fromJson(""))
        assertNull(VoxLlmResult.fromJson("{ not json"))
        assertNull(VoxLlmResult.fromJson("{}")) // missing task/status
    }
}
