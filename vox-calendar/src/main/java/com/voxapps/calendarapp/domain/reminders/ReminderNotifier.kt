package com.voxapps.calendarapp.domain.reminders

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.voxapps.calendarapp.CalendarActivity
import com.voxapps.calendarapp.data.CalendarEntry
import com.voxapps.calendarapp.domain.localization.LanguageManager
import java.text.DateFormat
import java.util.Date

private const val CHANNEL_ID = "entry_reminders"

/** Posts a plain local Android notification when a reminder fires — mirrors vox-expenses'
 *  SpendingLimitNotifier exactly (same channel/builder/PendingIntent shape). */
object ReminderNotifier {

    fun notify(context: Context, languageManager: LanguageManager, reminderId: Long, entry: CalendarEntry) {
        ensureChannel(context, languageManager)

        val contentIntent = Intent(context, CalendarActivity::class.java).apply {
            putExtra(CalendarActivity.EXTRA_EDIT_ENTRY_ID, entry.id)
        }
        val pendingIntent = PendingIntent.getActivity(
            context, reminderId.toInt(), contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val text = if (entry.allDay) {
            entry.location ?: languageManager.getString("today")
        } else {
            DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(entry.startMillis))
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(entry.title)
            .setContentText(text)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat.from(context).notify(reminderId.toInt(), notification)
    }

    private fun ensureChannel(context: Context, languageManager: LanguageManager) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            languageManager.getString("entry_reminders_channel_name"),
            NotificationManager.IMPORTANCE_DEFAULT
        )
        manager.createNotificationChannel(channel)
    }
}
