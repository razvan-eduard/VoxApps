package com.voxapps.calendarapp.domain.reminders

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.voxapps.calendarapp.data.CalendarEntry
import com.voxapps.calendarapp.data.CalendarReminder

private const val EXTRA_REMINDER_ID = "com.voxapps.calendarapp.EXTRA_REMINDER_ID"
private const val EXTRA_ENTRY_ID = "com.voxapps.calendarapp.EXTRA_ENTRY_ID"

/**
 * Schedules/cancels the exact alarm backing a single [CalendarReminder]. First use of AlarmManager
 * in this monorepo — mirrors no existing pattern (vox-expenses' SpendingLimitScheduler is a daily
 * WorkManager periodic check, not exact-time). v1 only ever calls this for non-recurring entries.
 */
object ReminderScheduler {

    /** Pure trigger-time computation, split out from [schedule] so it's unit-testable without
     *  touching Context/AlarmManager (this module has no Robolectric/Room-testing setup — see
     *  ReminderSchedulerTest). */
    internal fun triggerAtMillis(entry: CalendarEntry, reminder: CalendarReminder): Long =
        entry.startMillis - reminder.offsetMinutesBefore * 60_000L

    fun schedule(context: Context, reminder: CalendarReminder, entry: CalendarEntry) {
        val triggerAt = triggerAtMillis(entry, reminder)
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
