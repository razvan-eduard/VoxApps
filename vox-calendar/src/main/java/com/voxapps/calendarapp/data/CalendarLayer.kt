package com.voxapps.calendarapp.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** [CalendarLayer.kind] — a plain local calendar vs. one synced from a remote ICS URL. */
enum class CalendarLayerKind { LOCAL, SUBSCRIBED }

/**
 * A user-defined, colored calendar "layer" (Personal, Work, Moon Calendar, ...) — the flat organizing
 * dimension every [CalendarEntry] belongs to, one per user-chosen ICS-calendar-equivalent. [visible]
 * toggles whether its entries render across the Year/Month/Week/Day views without deleting anything.
 * [isDefault] marks the layer new entries fall back to when no other layer is specified (voice/LLM
 * creation, or the very first entry in a fresh install) — exactly one layer should carry this flag at
 * a time, enforced at the [CalendarRepository] level, not by the schema. It is also the app's "Main
 * calendar", the only one that can never be deleted.
 *
 * [kind]/[subscriptionUrl]/[lastSyncedAt]/[lastSyncError] back online-subscribed calendars (see
 * `domain/subscription/CalendarSubscriptionSyncEngine`) — null/unused for [CalendarLayerKind.LOCAL].
 *
 * [reminderOffsetsMinutes] is a comma-joined list of minutes-before offsets (see
 * [ReminderOffsetsCodec]), empty meaning "no calendar-level override" — when non-empty, every entry
 * under this layer is scheduled with these offsets instead of its own individually-chosen ones (see
 * [CalendarRepository.effectiveOffsetsFor]), even for a [CalendarLayerKind.SUBSCRIBED] layer.
 */
@Entity(tableName = "calendar_layers")
data class CalendarLayer(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val colorArgb: Long,
    val visible: Boolean = true,
    val isDefault: Boolean = false,
    val position: Int = 0,
    val createdAt: Long,
    val kind: CalendarLayerKind = CalendarLayerKind.LOCAL,
    val subscriptionUrl: String? = null,
    val lastSyncedAt: Long? = null,
    val lastSyncError: String? = null,
    val reminderOffsetsMinutes: String = ""
)
