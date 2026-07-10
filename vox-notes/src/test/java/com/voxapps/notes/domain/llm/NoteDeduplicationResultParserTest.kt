package com.voxapps.notes.domain.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NoteDeduplicationResultParserTest {

    @Test
    fun `parses a single group`() {
        val json = """{"groups":[{"keep":12,"duplicates":[7,9]}]}"""
        val result = NoteDeduplicationResultParser.parse(json)
        assertEquals(listOf(DuplicateGroup(12, listOf(7, 9))), result)
    }

    @Test
    fun `parses multiple groups`() {
        val json = """{"groups":[{"keep":1,"duplicates":[2]},{"keep":10,"duplicates":[11,12]}]}"""
        val result = NoteDeduplicationResultParser.parse(json)
        assertEquals(
            listOf(DuplicateGroup(1, listOf(2)), DuplicateGroup(10, listOf(11, 12))),
            result
        )
    }

    @Test
    fun `no groups key returns empty list`() {
        assertEquals(emptyList<DuplicateGroup>(), NoteDeduplicationResultParser.parse("{}"))
    }

    @Test
    fun `empty groups array returns empty list`() {
        assertEquals(emptyList<DuplicateGroup>(), NoteDeduplicationResultParser.parse("""{"groups":[]}"""))
    }

    @Test
    fun `group missing keep is skipped`() {
        val json = """{"groups":[{"duplicates":[1,2]},{"keep":5,"duplicates":[6]}]}"""
        val result = NoteDeduplicationResultParser.parse(json)
        assertEquals(listOf(DuplicateGroup(5, listOf(6))), result)
    }

    @Test
    fun `group with empty duplicates is skipped`() {
        val json = """{"groups":[{"keep":1,"duplicates":[]},{"keep":5,"duplicates":[6]}]}"""
        val result = NoteDeduplicationResultParser.parse(json)
        assertEquals(listOf(DuplicateGroup(5, listOf(6))), result)
    }

    @Test
    fun `malformed json returns null`() {
        assertNull(NoteDeduplicationResultParser.parse("{ not json"))
    }
}
