package com.voxapps.calendarapp.ui

import com.voxapps.calendarapp.data.CalendarEntryType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EntryEditScreenTest {

    @Test
    fun `event with end at or before start is invalid`() {
        assertTrue(isTimeRangeInvalid(CalendarEntryType.EVENT, startMillis = 1000L, endMillis = 1000L))
        assertTrue(isTimeRangeInvalid(CalendarEntryType.EVENT, startMillis = 1000L, endMillis = 500L))
    }

    @Test
    fun `event with end after start is valid`() {
        assertFalse(isTimeRangeInvalid(CalendarEntryType.EVENT, startMillis = 1000L, endMillis = 2000L))
    }

    @Test
    fun `event with no end time set is valid`() {
        assertFalse(isTimeRangeInvalid(CalendarEntryType.EVENT, startMillis = 1000L, endMillis = null))
    }

    @Test
    fun `task is never invalid regardless of a stray end time`() {
        assertFalse(isTimeRangeInvalid(CalendarEntryType.TASK, startMillis = 1000L, endMillis = 500L))
        assertFalse(isTimeRangeInvalid(CalendarEntryType.TASK, startMillis = 1000L, endMillis = null))
    }
}
