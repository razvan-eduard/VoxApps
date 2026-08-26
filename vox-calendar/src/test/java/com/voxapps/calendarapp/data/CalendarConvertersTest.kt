package com.voxapps.calendarapp.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** A stored name this build does not know must degrade to the field default, never crash a query. */
class CalendarConvertersTest {
    private val converters = CalendarConverters()

    @Test
    fun `known names round-trip`() {
        assertEquals(CalendarEntryType.TASK, converters.toEntryType(converters.fromEntryType(CalendarEntryType.TASK)))
        assertEquals(RecurrenceFrequency.WEEKLY, converters.toRecurrenceFrequency(converters.fromRecurrenceFrequency(RecurrenceFrequency.WEEKLY)))
        assertEquals(CalendarLayerKind.SUBSCRIBED, converters.toLayerKind(converters.fromLayerKind(CalendarLayerKind.SUBSCRIBED)))
    }

    @Test
    fun `a name from a newer build falls back to the default`() {
        assertEquals(CalendarEntryType.EVENT, converters.toEntryType("HOLOGRAM"))
        assertEquals(RecurrenceFrequency.NONE, converters.toRecurrenceFrequency("FORTNIGHTLY"))
        assertEquals(CalendarLayerKind.LOCAL, converters.toLayerKind("FEDERATED"))
    }
}
