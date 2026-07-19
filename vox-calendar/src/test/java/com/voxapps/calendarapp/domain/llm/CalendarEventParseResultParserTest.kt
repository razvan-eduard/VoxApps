package com.voxapps.calendarapp.domain.llm

import com.voxapps.calendarapp.data.CalendarEntryType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class CalendarEventParseResultParserTest {

    private val zone: ZoneId = ZoneId.of("UTC")

    @Test
    fun `parses a timed event`() {
        val json = """{"title":"Dentist","type":"EVENT","startDate":"2026-07-19","startTime":"09:30","allDay":false,"layer":null,"tags":[]}"""
        val result = CalendarEventParseResultParser.parse(json, zone)!!

        assertEquals("Dentist", result.title)
        assertEquals(CalendarEntryType.EVENT, result.type)
        assertEquals(false, result.allDay)
        val start = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(result.startMillis), zone)
        assertEquals(2026, start.year)
        assertEquals(7, start.monthValue)
        assertEquals(19, start.dayOfMonth)
        assertEquals(9, start.hour)
        assertEquals(30, start.minute)
    }

    @Test
    fun `all-day entry with no time fields defaults to midnight`() {
        val json = """{"title":"Birthday","type":"EVENT","startDate":"2026-08-01","allDay":true,"tags":[]}"""
        val result = CalendarEventParseResultParser.parse(json, zone)!!
        val start = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(result.startMillis), zone)
        assertEquals(0, start.hour)
        assertTrue(result.allDay)
    }

    @Test
    fun `parses a task with layer and tags`() {
        val json = """{"title":"Renew passport","type":"TASK","startDate":"2026-09-01","allDay":true,"layer":"Personal","tags":["Admin","Urgent"]}"""
        val result = CalendarEventParseResultParser.parse(json, zone)!!

        assertEquals(CalendarEntryType.TASK, result.type)
        assertEquals("Personal", result.layer)
        assertEquals(listOf("Admin", "Urgent"), result.tags)
    }

    @Test
    fun `missing title returns null`() {
        val json = """{"type":"EVENT","startDate":"2026-07-19","allDay":true}"""
        assertNull(CalendarEventParseResultParser.parse(json, zone))
    }

    @Test
    fun `missing startDate returns null`() {
        val json = """{"title":"Dentist","type":"EVENT","allDay":true}"""
        assertNull(CalendarEventParseResultParser.parse(json, zone))
    }

    @Test
    fun `unparseable startDate returns null`() {
        val json = """{"title":"Dentist","type":"EVENT","startDate":"not-a-date","allDay":true}"""
        assertNull(CalendarEventParseResultParser.parse(json, zone))
    }

    @Test
    fun `malformed json returns null`() {
        assertNull(CalendarEventParseResultParser.parse("{ not json", zone))
    }

    @Test
    fun `unrecognized type defaults to EVENT`() {
        val json = """{"title":"Something","startDate":"2026-07-19","allDay":true}"""
        val result = CalendarEventParseResultParser.parse(json, zone)!!
        assertEquals(CalendarEntryType.EVENT, result.type)
    }

    @Test
    fun `end date without end time on an all-day entry is midnight`() {
        val json = """{"title":"Trip","type":"EVENT","startDate":"2026-07-19","endDate":"2026-07-22","allDay":true,"tags":[]}"""
        val result = CalendarEventParseResultParser.parse(json, zone)!!
        val end = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(result.endMillis!!), zone)
        assertEquals(22, end.dayOfMonth)
        assertEquals(0, end.hour)
    }

    @Test
    fun `a genuine JSON null layer is treated as null, not the literal string null`() {
        val json = """{"title":"Dentist","type":"EVENT","startDate":"2026-07-19","allDay":true,"layer":null,"tags":[]}"""
        val result = CalendarEventParseResultParser.parse(json, zone)!!
        assertNull(result.layer)
    }

    @Test
    fun `a case-insensitive literal null layer is discarded, closing the weak local guard bug`() {
        val json = """{"title":"Dentist","type":"EVENT","startDate":"2026-07-19","allDay":true,"layer":"Null","tags":[]}"""
        val result = CalendarEventParseResultParser.parse(json, zone)!!
        assertNull(result.layer)
    }

    @Test
    fun `a case-insensitive literal NULL title is discarded and the whole parse fails`() {
        val json = """{"title":"NULL","type":"EVENT","startDate":"2026-07-19","allDay":true,"tags":[]}"""
        assertNull(CalendarEventParseResultParser.parse(json, zone))
    }

    @Test
    fun `a punctuation-only title is discarded and the whole parse fails`() {
        val json = """{"title":"...","type":"EVENT","startDate":"2026-07-19","allDay":true,"tags":[]}"""
        assertNull(CalendarEventParseResultParser.parse(json, zone))
    }
}
