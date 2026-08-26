package com.voxapps.calendarapp.data

import androidx.room.TypeConverter
import com.voxapps.design.toEnumOr

/**
 * Room can't natively persist enums — stores each by its [Enum.name]. Reads fall back to each
 * field's declared default: a name this build doesn't know (a database written by a newer build)
 * must degrade to a plain entry, not fail the query it appears in.
 */
class CalendarConverters {
    @TypeConverter
    fun fromEntryType(type: CalendarEntryType): String = type.name

    @TypeConverter
    fun toEntryType(value: String): CalendarEntryType = value.toEnumOr(CalendarEntryType.EVENT)

    @TypeConverter
    fun fromRecurrenceFrequency(freq: RecurrenceFrequency): String = freq.name

    @TypeConverter
    fun toRecurrenceFrequency(value: String): RecurrenceFrequency = value.toEnumOr(RecurrenceFrequency.NONE)

    @TypeConverter
    fun fromLayerKind(kind: CalendarLayerKind): String = kind.name

    @TypeConverter
    fun toLayerKind(value: String): CalendarLayerKind = value.toEnumOr(CalendarLayerKind.LOCAL)
}
