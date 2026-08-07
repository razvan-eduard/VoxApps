package com.voxapps.calendarapp.data

import androidx.room.TypeConverter

/** Room can't natively persist enums — stores each by its [Enum.name]. */
class CalendarConverters {
    @TypeConverter
    fun fromEntryType(type: CalendarEntryType): String = type.name

    @TypeConverter
    fun toEntryType(value: String): CalendarEntryType = CalendarEntryType.valueOf(value)

    @TypeConverter
    fun fromRecurrenceFrequency(freq: RecurrenceFrequency): String = freq.name

    @TypeConverter
    fun toRecurrenceFrequency(value: String): RecurrenceFrequency = RecurrenceFrequency.valueOf(value)

    @TypeConverter
    fun fromLayerKind(kind: CalendarLayerKind): String = kind.name

    @TypeConverter
    fun toLayerKind(value: String): CalendarLayerKind = CalendarLayerKind.valueOf(value)
}
