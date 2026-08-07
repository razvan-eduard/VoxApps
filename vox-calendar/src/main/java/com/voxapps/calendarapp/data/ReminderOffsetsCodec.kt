package com.voxapps.calendarapp.data

/**
 * Comma-joined string <-> `List<Int>` for [CalendarLayer.reminderOffsetsMinutes] and
 * [CalendarEntry.individualReminderOffsetsMinutes] — a small, fixed preset set (see
 * `EntryEditScreen.REMINDER_PRESETS`), not worth a normalized child table.
 */
object ReminderOffsetsCodec {
    fun encode(offsets: List<Int>): String = offsets.distinct().sorted().joinToString(",")

    fun decode(value: String?): List<Int> =
        value?.split(",")?.mapNotNull { it.trim().toIntOrNull() } ?: emptyList()
}
