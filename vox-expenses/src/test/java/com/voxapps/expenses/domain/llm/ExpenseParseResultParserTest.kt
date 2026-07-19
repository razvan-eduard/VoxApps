package com.voxapps.expenses.domain.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExpenseParseResultParserTest {

    @Test
    fun `parses a bare amount with no items`() {
        val json = """{"title":"Bread","totalAmount":10.0,"currency":"RON","vendor":"magazin","category":"Mancare","items":[]}"""
        val result = ExpenseParseResultParser.parse(json)!!

        assertEquals("Bread", result.title)
        assertEquals(10.0, result.totalAmount, 0.0)
        assertEquals("RON", result.currency)
        assertEquals("magazin", result.vendor)
        assertEquals("Mancare", result.category)
        assertEquals(emptyList<ExpenseParseResultParser.ParsedItem>(), result.items)
    }

    @Test
    fun `parses items with quantity and unit price`() {
        val json = """{"totalAmount":9.99,"items":[{"name":"paine","quantity":3,"unitPrice":3.33}]}"""
        val result = ExpenseParseResultParser.parse(json)!!

        assertEquals(1, result.items.size)
        assertEquals("paine", result.items[0].name)
        assertEquals(3.0, result.items[0].quantity, 0.0)
        assertEquals(3.33, result.items[0].unitPrice, 0.0)
    }

    @Test
    fun `defaults item quantity to 1 when missing`() {
        val json = """{"totalAmount":5.0,"items":[{"name":"paine","unitPrice":5.0}]}"""
        val result = ExpenseParseResultParser.parse(json)!!
        assertEquals(1.0, result.items[0].quantity, 0.0)
    }

    @Test
    fun `a genuine JSON null vendor is treated as null, not the literal string null`() {
        val json = """{"totalAmount":5.0,"vendor":null,"items":[]}"""
        val result = ExpenseParseResultParser.parse(json)!!
        assertNull(result.vendor)
    }

    @Test
    fun `the literal string null as a vendor value is also treated as null`() {
        val json = """{"totalAmount":5.0,"vendor":"null","items":[]}"""
        val result = ExpenseParseResultParser.parse(json)!!
        assertNull(result.vendor)
    }

    @Test
    fun `a punctuation-only bank value is treated as null`() {
        val json = """{"totalAmount":5.0,"bank":";","items":[]}"""
        val result = ExpenseParseResultParser.parse(json)!!
        assertNull(result.bank)
    }

    @Test
    fun `item missing unitPrice is dropped`() {
        val json = """{"totalAmount":5.0,"items":[{"name":"paine"},{"name":"lapte","unitPrice":5.0}]}"""
        val result = ExpenseParseResultParser.parse(json)!!
        assertEquals(1, result.items.size)
        assertEquals("lapte", result.items[0].name)
    }

    @Test
    fun `missing totalAmount returns null (discard, cannot save without one)`() {
        val json = """{"title":"Bread","currency":"RON"}"""
        assertNull(ExpenseParseResultParser.parse(json))
    }

    @Test
    fun `null totalAmount returns null`() {
        val json = """{"totalAmount":null}"""
        assertNull(ExpenseParseResultParser.parse(json))
    }

    @Test
    fun `blank optional fields become null`() {
        val json = """{"totalAmount":1.0,"title":"","vendor":"","category":""}"""
        val result = ExpenseParseResultParser.parse(json)!!
        assertNull(result.title)
        assertNull(result.vendor)
        assertNull(result.category)
    }

    @Test
    fun `malformed json returns null`() {
        assertNull(ExpenseParseResultParser.parse("{ not json"))
    }

    @Test
    fun `itemsSumMismatch is false when items roughly sum to the total`() {
        val json = """{"totalAmount":9.99,"items":[{"name":"paine","quantity":3,"unitPrice":3.33}]}"""
        val result = ExpenseParseResultParser.parse(json)!!
        assertFalse(result.itemsSumMismatch)
    }

    @Test
    fun `itemsSumMismatch is true when the total grossly exceeds the items sum`() {
        val json = """{"totalAmount":101.97,"items":[{"name":"paine","quantity":1,"unitPrice":33.99}]}"""
        val result = ExpenseParseResultParser.parse(json)!!
        assertTrue(result.itemsSumMismatch)
    }
}
