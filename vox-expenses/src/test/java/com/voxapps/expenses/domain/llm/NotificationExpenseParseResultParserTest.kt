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
