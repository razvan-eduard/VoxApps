package com.voxapps.calendar

/**
 * Anything placeable on [CalendarView]'s day-list must expose a stable id and an epoch-millis
 * timestamp (device default time zone is used for day-bucketing — see [CalendarDateUtils]).
 * Callers wrap their own model (e.g. a Room relation) in a small adapter implementing this rather
 * than this module knowing about any specific app's data classes.
 */
interface CalendarItem {
    val id: Any
    val dateTimeMillis: Long
}
