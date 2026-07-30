package com.voxapps.calendarapp.domain.reminders

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.voxapps.calendarapp.CalendarActivity
import com.voxapps.calendarapp.data.CalendarEntry
import com.voxapps.calendarapp.data.preferences.CalendarSettings
import com.voxapps.calendarapp.domain.localization.LanguageManager
import com.voxapps.design.notifications.NotificationChannelVersioning
import com.voxapps.design.notifications.NotificationSoundPlayer
import java.text.DateFormat
import java.util.Date

private const val CHANNEL_ID = "entry_reminders"

/** Posts a plain local Android notification when a reminder fires — mirrors vox-expenses'
 *  SpendingLimitNotifier exactly (same channel/builder/PendingIntent shape). */
object ReminderNotifier {

    fun notify(context: Context, languageManager: LanguageManager, reminderId: Long, entry: CalendarEntry, settings: CalendarSettings) {
        val channelId = if (settings.notificationsSystemDefault) {
            CHANNEL_ID
        } else {
            "${CHANNEL_ID}_v${settings.notificationsChannelVersion}"
        }

        ensureChannel(context, languageManager, settings)

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

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(entry.title)
            .setContentText(text)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        if (!settings.notificationsSystemDefault) {
            NotificationSoundPlayer.play(
                context = context,
                soundUri = settings.notificationsSoundUri,
                volume = settings.notificationsVolume,
                length = settings.notificationsLength,
                vibrationEnabled = settings.notificationsVibrationEnabled
            )
        }

        NotificationManagerCompat.from(context).notify(reminderId.toInt(), notification)
    }

    private fun ensureChannel(context: Context, languageManager: LanguageManager, settings: CalendarSettings) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        
        val channelId = if (settings.notificationsSystemDefault) {
            CHANNEL_ID
        } else {
            "${CHANNEL_ID}_v${settings.notificationsChannelVersion}"
        }

        if (manager.getNotificationChannel(channelId) == null) {
            val importance = if (settings.notificationsSystemDefault) {
                NotificationManager.IMPORTANCE_DEFAULT
            } else {
                NotificationManager.IMPORTANCE_HIGH
            }

            val channel = NotificationChannel(
                channelId,
                languageManager.getString("entry_reminders_channel_name"),
                importance
            ).apply {
                if (!settings.notificationsSystemDefault) {
                    // Sound and vibration are handled manually by NotificationSoundPlayer
                    setSound(null, null)
                    enableVibration(false)
                }
            }
            manager.createNotificationChannel(channel)
        }

        // A channel's sound/vibration/importance are immutable once created, so every settings
        // change mints a new versioned channel instead of mutating the old one — left uncleaned,
        // each change would permanently orphan the previous channel in system settings.
        val staleIds = NotificationChannelVersioning.staleChannelIds(
            manager.notificationChannels.map { it.id }, CHANNEL_ID, channelId
        )
        staleIds.forEach { manager.deleteNotificationChannel(it) }
    }
}
