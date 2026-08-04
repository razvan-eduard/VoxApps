package com.voxapps.calendarapp.domain.reminders

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.voxapps.calendarapp.data.CalendarEntry
import com.voxapps.calendarapp.data.CalendarReminder
import com.voxapps.calendarapp.data.RecurrenceExpander
import com.voxapps.calendarapp.data.RecurrenceFrequency

private const val EXTRA_REMINDER_ID = "com.voxapps.calendarapp.EXTRA_REMINDER_ID"
private const val EXTRA_ENTRY_ID = "com.voxapps.calendarapp.EXTRA_ENTRY_ID"

/**
 * Schedules/cancels the exact alarm backing a single [CalendarReminder]. First use of AlarmManager
 * in this monorepo — mirrors no existing pattern (vox-expenses' SpendingLimitScheduler is a daily
 * WorkManager periodic check, not exact-time). For a recurring entry, [schedule] targets the next
 * upcoming occurrence rather than the series' original start; [ReminderReceiver] re-arms against the
 * following occurrence each time the alarm fires, since AlarmManager has no monthly/interval-repeat
 * primitive of its own.
 */
object ReminderScheduler {

    /** Pure trigger-time computation, split out from [schedule] so it's unit-testable without
     *  touching Context/AlarmManager (this module has no Robolectric/Room-testing setup — see
     *  ReminderSchedulerTest). [fromMillis] is the point in time to search forward from for a
     *  recurring entry's next occurrence (ignored for non-recurring entries, which only ever have
     *  the one `startMillis`). Returns `null` once recurrence is exhausted
     *  ([CalendarEntry.recurrenceUntilMillis] passed) or a non-recurring entry's occurrence has
     *  nothing left to search from. */
    internal fun triggerAtMillis(entry: CalendarEntry, reminder: CalendarReminder, fromMillis: Long): Long? {
        val occurrenceStart = if (entry.recurrenceFrequency == RecurrenceFrequency.NONE) {
            // Non-null: a reminder is only ever scheduled against a dated entry.
            entry.startMillis ?: return null
        } else {
            // Search from just after the reminder offset so we land on an occurrence whose reminder
            // (not just whose event start) is still in the future.
            RecurrenceExpander.nextOccurrenceOnOrAfter(entry, fromMillis + reminder.offsetMinutesBefore * 60_000L)
                ?.startMillis ?: return null
        }
        return occurrenceStart - reminder.offsetMinutesBefore * 60_000L
    }

    /** [fromMillis] defaults to now; callers re-arming a recurring reminder right after it fired pass
     *  a value a little past now so the occurrence search (inclusive of [fromMillis]) moves on to the
     *  FOLLOWING occurrence instead of re-matching the one that just fired — see
     *  [ReminderReceiver.fireReminder]. */
    fun schedule(context: Context, reminder: CalendarReminder, entry: CalendarEntry, fromMillis: Long = System.currentTimeMillis()) {
        val triggerAt = triggerAtMillis(entry, reminder, fromMillis) ?: return
        if (triggerAt <= System.currentTimeMillis()) return

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pendingIntent = pendingIntentFor(context, reminder.id, entry.id)

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
        } else {
            // Exact-alarm permission not granted — still schedule, just without timing guarantees,
            // rather than silently dropping the reminder.
            alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
        }
    }

    fun cancel(context: Context, reminderId: Long) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pendingIntent = PendingIntent.getBroadcast(
            context, reminderId.toInt(), Intent(context, ReminderReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pendingIntent)
        pendingIntent.cancel()
    }

    fun canScheduleExactAlarms(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).canScheduleExactAlarms()
    }

    private fun pendingIntentFor(context: Context, reminderId: Long, entryId: Long): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            putExtra(EXTRA_REMINDER_ID, reminderId)
            putExtra(EXTRA_ENTRY_ID, entryId)
        }
        return PendingIntent.getBroadcast(
            context, reminderId.toInt(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    internal fun reminderIdFrom(intent: Intent): Long = intent.getLongExtra(EXTRA_REMINDER_ID, -1L)
    internal fun entryIdFrom(intent: Intent): Long = intent.getLongExtra(EXTRA_ENTRY_ID, -1L)
}
