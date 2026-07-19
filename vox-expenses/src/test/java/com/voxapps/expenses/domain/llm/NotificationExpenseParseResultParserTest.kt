package com.voxapps.expenses.domain.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NotificationExpenseParseResultParserTest {

    @Test
    fun `parses a genuine payment`() {
        val json = """{"isPayment":true,"title":"Card payment","totalAmount":45.9,"currency":"RON","vendor":"Lidl","category":"Mancare"}"""
        val result = NotificationExpenseParseResultParser.parse(json)!!

        assertEquals("Card payment", result.title)
        assertEquals(45.9, result.totalAmount, 0.0)
        assertEquals("RON", result.currency)
        assertEquals("Lidl", result.vendor)
        assertEquals("Mancare", result.category)
        assertNull(result.bank)
    }

    @Test
    fun `parses the bank field when the LLM echoes back a known bank name`() {
        val json = """{"isPayment":true,"title":"Card payment","totalAmount":45.9,"currency":"RON","vendor":"Lidl","category":"Mancare","bank":"Revolut"}"""
        val result = NotificationExpenseParseResultParser.parse(json)!!

        assertEquals("Revolut", result.bank)
    }

    @Test
    fun `blank bank field is treated as null, same as the other optional fields`() {
        val json = """{"isPayment":true,"totalAmount":45.9,"bank":""}"""
        val result = NotificationExpenseParseResultParser.parse(json)!!

        assertNull(result.bank)
    }

    @Test
    fun `a genuine JSON null vendor is treated as null, not the literal string null`() {
        // Regression test: org.json's JSONObject stores a JSON null as the JSONObject.NULL sentinel,
        // whose toString() is literally "null" -- a bare optString(key) call stringifies that
        // sentinel into the text "null" instead of recognizing it as absent. This is exactly what
        // corrupted a real on-device expense's Vendor field before optCleanString() was used here.
        val json = """{"isPayment":true,"totalAmount":45.9,"vendor":null}"""
        val result = NotificationExpenseParseResultParser.parse(json)!!

        assertNull(result.vendor)
    }

    @Test
    fun `the literal string null as a vendor value is also treated as null`() {
        val json = """{"isPayment":true,"totalAmount":45.9,"vendor":"null"}"""
        val result = NotificationExpenseParseResultParser.parse(json)!!

        assertNull(result.vendor)
    }

    @Test
    fun `a punctuation-only vendor value is treated as null`() {
        val json = """{"isPayment":true,"totalAmount":45.9,"vendor":"."}"""
        val result = NotificationExpenseParseResultParser.parse(json)!!

        assertNull(result.vendor)
    }

    @Test
    fun `isPayment false returns null (discard silently)`() {
        assertNull(NotificationExpenseParseResultParser.parse("""{"isPayment":false}"""))
    }

    @Test
    fun `missing isPayment key returns null`() {
        assertNull(NotificationExpenseParseResultParser.parse("""{"title":"x"}"""))
    }

    @Test
    fun `isPayment true but missing totalAmount returns null`() {
        assertNull(NotificationExpenseParseResultParser.parse("""{"isPayment":true,"title":"x"}"""))
    }

    @Test
    fun `malformed json returns null`() {
        assertNull(NotificationExpenseParseResultParser.parse("{ not json"))
    }
}
