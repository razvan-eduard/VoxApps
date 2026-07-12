package com.voxapps.calendarapp.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A user-defined, colored calendar "layer" (Personal, Work, Moon Calendar, ...) — the flat organizing
 * dimension every [CalendarEntry] belongs to, one per user-chosen ICS-calendar-equivalent. [visible]
 * toggles whether its entries render across the Year/Month/Week/Day views without deleting anything.
 * [isDefault] marks the layer new entries fall back to when no other layer is specified (voice/LLM
 * creation, or the very first entry in a fresh install) — exactly one layer should carry this flag at
 * a time, enforced at the [CalendarRepository] level, not by the schema.
 */
@Entity(tableName = "calendar_layers")
data class CalendarLayer(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val colorArgb: Long,
    val visible: Boolean = true,
    val isDefault: Boolean = false,
    val position: Int = 0,
    val createdAt: Long
)
