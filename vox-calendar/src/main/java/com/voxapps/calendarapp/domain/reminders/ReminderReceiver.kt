package com.voxapps.calendarapp.domain.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.voxapps.calendarapp.CalendarApplication
import com.voxapps.logging.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private const val TAG = "ReminderReceiver"

/**
 * Handles two distinct triggers:
 *  - An alarm firing (explicit intent built by [ReminderScheduler], carrying reminder/entry ids):
 *    look up the entry and post a notification.
 *  - [Intent.ACTION_BOOT_COMPLETED] (implicit system broadcast, declared in the manifest): exact
 *    alarms are cleared on reboot, so every still-future reminder must be re-scheduled.
 * Uses [goAsync] + a coroutine since DB access can't happen synchronously inside [onReceive].
 */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
                    rescheduleAll(context)
                } else {
                    fireReminder(context, intent)
                }
            } catch (e: Exception) {
                Logger.w(TAG, "Failed to handle reminder broadcast", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun fireReminder(context: Context, intent: Intent) {
        val container = (context.applicationContext as CalendarApplication).container
        val reminderId = ReminderScheduler.reminderIdFrom(intent)
        val entryId = ReminderScheduler.entryIdFrom(intent)
        if (reminderId < 0 || entryId < 0) return

        val entry = container.calendarRepository.getEntryById(entryId) ?: return
        ReminderNotifier.notify(context, container.languageManager, reminderId, entry)
    }

    private suspend fun rescheduleAll(context: Context) {
        val container = (context.applicationContext as CalendarApplication).container
        val reminders = container.calendarRepository.getAllReminders()
        for (reminder in reminders) {
            val entry = container.calendarRepository.getEntryById(reminder.entryId) ?: continue
            ReminderScheduler.schedule(context, reminder, entry)
        }
    }
}
