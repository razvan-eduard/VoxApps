package com.voxapps.calendarapp.data

import java.time.DayOfWeek

/**
 * A set of weekdays packed into an `Int` — bit 0 is Monday through bit 6 is Sunday, following ISO
 * [DayOfWeek.getValue]. `0` means "no explicit day choice" everywhere the mask appears: a weekly
 * recurrence falls back to its start date's weekday ([CalendarEntry.recurrenceDaysMask]), and a
 * to-do list simply isn't a routine ([ToDoList.routineDaysMask]).
 */
object WeekdayMask {
    const val ALL = 0b1111111

    fun bit(day: DayOfWeek): Int = 1 shl (day.value - 1)

    fun contains(mask: Int, day: DayOfWeek): Boolean = mask and bit(day) != 0

    fun toggled(mask: Int, day: DayOfWeek): Int = mask xor bit(day)
}
