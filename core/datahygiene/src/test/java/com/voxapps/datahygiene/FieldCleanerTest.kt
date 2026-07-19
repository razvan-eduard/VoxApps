package com.voxapps.datahygiene

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FieldCleanerTest {

    @Test
    fun `a clean value passes through unchanged`() {
        assertEquals("eMAG", FieldCleaner.clean("eMAG"))
    }

    @Test
    fun `a value is trimmed`() {
        assertEquals("eMAG", FieldCleaner.clean("  eMAG  "))
    }

    @Test
    fun `null is null`() {
        assertNull(FieldCleaner.clean(null))
    }

    @Test
    fun `blank or whitespace-only becomes null`() {
        assertNull(FieldCleaner.clean(""))
        assertNull(FieldCleaner.clean("   "))
    }

    @Test
    fun `the literal string null (any case) becomes null`() {
        assertNull(FieldCleaner.clean("null"))
        assertNull(FieldCleaner.clean("Null"))
        assertNull(FieldCleaner.clean("NULL"))
        assertNull(FieldCleaner.clean("  null  "))
    }

    @Test
    fun `pure punctuation with no letters or digits becomes null`() {
        assertNull(FieldCleaner.clean("."))
        assertNull(FieldCleaner.clean(";"))
        assertNull(FieldCleaner.clean("-"))
        assertNull(FieldCleaner.clean("..."))
    }

    @Test
    fun `a value with at least one letter or digit is kept`() {
        assertEquals("N/A Corp", FieldCleaner.clean("N/A Corp"))
        assertEquals("123", FieldCleaner.clean("123"))
    }

    @Test
    fun `cleanRequired falls back when the value is garbage`() {
        assertEquals("Untitled", FieldCleaner.cleanRequired("null", "Untitled"))
        assertEquals("Untitled", FieldCleaner.cleanRequired(".", "Untitled"))
        assertEquals("Untitled", FieldCleaner.cleanRequired("", "Untitled"))
    }

    @Test
    fun `cleanRequired keeps a real value`() {
        assertEquals("Groceries", FieldCleaner.cleanRequired("Groceries", "Untitled"))
    }

    @Test
    fun `isDirty is false for null or blank`() {
        assertFalse(FieldCleaner.isDirty(null))
        assertFalse(FieldCleaner.isDirty(""))
        assertFalse(FieldCleaner.isDirty("   "))
    }

    @Test
    fun `isDirty is false for a clean value, even with surrounding whitespace`() {
        assertFalse(FieldCleaner.isDirty("eMAG"))
        assertFalse(FieldCleaner.isDirty("  eMAG  "))
    }

    @Test
    fun `isDirty is true for garbage content`() {
        assertTrue(FieldCleaner.isDirty("null"))
        assertTrue(FieldCleaner.isDirty("."))
        assertTrue(FieldCleaner.isDirty(";"))
    }
}
