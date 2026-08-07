package com.voxapps.calendarapp.data

import android.content.Context
import com.voxapps.attachments.AttachmentFileStore
import com.voxapps.calendarapp.domain.reminders.ReminderScheduler

/**
 * The two things [CalendarRepository] does that aren't database work: arming AlarmManager for an
 * entry's reminders, and deleting an attachment's file from disk.
 *
 * Both were called as objects directly from the repository, which made the data layer reach into
 * Android from inside otherwise pure write paths. The visible cost was in the tests: exercising
 * reminder precedence meant giving every fixture entry a `startMillis` in the past, purely so
 * `ReminderScheduler.schedule` would return at its own trigger-time check before touching a real
 * AlarmManager. The behaviour under test had nothing to do with past dates.
 *
 * Kept as one small interface rather than split into per-concern use cases: these are side effects
 * the repository fires as it writes, not operations a caller composes, so what was actually needed
 * was a seam to substitute — not a new layer.
 */
interface CalendarPlatformEffects {
    fun scheduleReminder(reminder: CalendarReminder, entry: CalendarEntry)
    fun cancelReminder(reminderId: Long)

    /** Best-effort; the caller treats a failure as non-fatal and never rolls back the DB delete. */
    fun deleteAttachmentFile(dirName: String, fileName: String)
}

/** The real implementation, and [CalendarRepository]'s default — production wiring is unchanged. */
class AndroidCalendarPlatformEffects(private val context: Context) : CalendarPlatformEffects {
    override fun scheduleReminder(reminder: CalendarReminder, entry: CalendarEntry) =
        ReminderScheduler.schedule(context, reminder, entry)

    override fun cancelReminder(reminderId: Long) =
        ReminderScheduler.cancel(context, reminderId)

    override fun deleteAttachmentFile(dirName: String, fileName: String) =
        AttachmentFileStore.delete(context, dirName, fileName)
}
