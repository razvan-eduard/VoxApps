package com.voxapps.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.YearMonth

/**
 * The list-index ↔ day mapping of the agenda list, in both directions. The previous month's grayed
 * peek is an item of its own, so whether it is shown moves every day below it — and the two
 * directions have to agree about that, since one places the opening scroll and the other reads a
 * scroll back into the selected date.
 */
class DayIndexMappingTest {

    private val august = YearMonth.of(2026, 8)

    @Test
    fun `without a peek, the first day is the first item`() {
        assertEquals(0, dayIndexInList(august, august.atDay(1), hasPrevPeek = false))
        assertEquals(august.atDay(1), dayForIndexInList(august, 0, hasPrevPeek = false))
    }

    @Test
    fun `with a peek, the days start one item lower`() {
        assertEquals(1, dayIndexInList(august, august.atDay(1), hasPrevPeek = true))
        assertEquals(august.atDay(1), dayForIndexInList(august, 1, hasPrevPeek = true))
    }

    @Test
    fun `index zero is the peek itself, not a day`() {
        assertNull(dayForIndexInList(august, 0, hasPrevPeek = true))
    }

    @Test
    fun `the last day of the month maps both ways`() {
        val last = august.atEndOfMonth()

        assertEquals(30, dayIndexInList(august, last, hasPrevPeek = false))
        assertEquals(last, dayForIndexInList(august, 30, hasPrevPeek = false))
        assertEquals(31, dayIndexInList(august, last, hasPrevPeek = true))
        assertEquals(last, dayForIndexInList(august, 31, hasPrevPeek = true))
    }

    @Test
    fun `an index past the last day belongs to the next month's peek, not to this month`() {
        assertNull(dayForIndexInList(august, 31, hasPrevPeek = false))
        assertNull(dayForIndexInList(august, 32, hasPrevPeek = true))
    }

    @Test
    fun `every day of the month round-trips, with a peek and without`() {
        for (hasPrevPeek in listOf(false, true)) {
            for (day in 1..august.lengthOfMonth()) {
                val date = august.atDay(day)
                val index = dayIndexInList(august, date, hasPrevPeek)
                assertEquals(
                    "day $day round-trip with hasPrevPeek=$hasPrevPeek",
                    date,
                    dayForIndexInList(august, index, hasPrevPeek)
                )
            }
        }
    }

    @Test
    fun `a short month bounds the mapping at its own length`() {
        val february = YearMonth.of(2026, 2)

        assertEquals(february.atDay(28), dayForIndexInList(february, 27, hasPrevPeek = false))
        assertNull(dayForIndexInList(february, 28, hasPrevPeek = false))
    }
}
