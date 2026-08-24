package com.voxapps.calendarapp.domain.reminders

import android.content.Context
import android.content.Intent
import com.voxapps.calendarapp.CalendarActivity
import com.voxapps.calendarapp.data.CalendarEntry
import com.voxapps.calendarapp.data.preferences.CalendarSettings
import com.voxapps.calendarapp.data.preferences.notificationPrefs
import com.voxapps.calendarapp.domain.localization.LanguageManager
import com.voxapps.design.notifications.VoxNotifier
import java.text.DateFormat
import java.util.Date

private const val CHANNEL_ID = "entry_reminders"

/** Posts a plain local Android notification when a reminder fires. */
object ReminderNotifier {

    fun notify(
        context: Context,
        languageManager: LanguageManager,
        reminderId: Long,
        entry: CalendarEntry,
        settings: CalendarSettings
    ) {
        VoxNotifier.post(
            context = context,
            channelBaseId = CHANNEL_ID,
            channelName = languageManager.getString("entry_reminders_channel_name"),
            notificationId = reminderId.toInt(),
            title = entry.title,
            text = if (entry.allDay) {
                entry.location ?: languageManager.getString("today")
            } else {
                // Non-null: a reminder is only ever scheduled against a dated entry.
                DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(entry.startMillis!!))
            },
            contentIntent = Intent(context, CalendarActivity::class.java).apply {
                putExtra(CalendarActivity.EXTRA_EDIT_ENTRY_ID, entry.id)
            },
            prefs = settings.notificationPrefs(),
            // A time or a place is one line, and expanding it would show the same line again.
            bigText = false
        )
    }
}
