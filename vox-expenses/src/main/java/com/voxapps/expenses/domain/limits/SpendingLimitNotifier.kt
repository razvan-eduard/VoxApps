package com.voxapps.expenses.domain.limits

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.voxapps.expenses.ExpensesActivity
import com.voxapps.expenses.data.preferences.ExpensesSettings
import com.voxapps.expenses.domain.localization.LanguageManager
import com.voxapps.design.notifications.NotificationSoundPlayer

private const val CHANNEL_ID = "spending_limit_alerts"

/**
 * Posts a plain local Android notification when a spending limit is exceeded — deliberately NOT a Vox
 * contract broadcast (see [com.voxapps.expenses.domain.limits.SpendingLimitCheckWorker]'s doc comment);
 * this is a device-local alert, not a cross-app message.
 */
object SpendingLimitNotifier {

    fun notify(context: Context, languageManager: LanguageManager, notificationId: Int, categoryLabel: String, spent: Double, limit: Double, homeCurrency: String, settings: ExpensesSettings) {
        val channelId = if (settings.notificationsSystemDefault) {
            CHANNEL_ID
        } else {
            "${CHANNEL_ID}_v${settings.notificationsChannelVersion}"
        }

        ensureChannel(context, languageManager, settings)

        val contentIntent = Intent(context, ExpensesActivity::class.java)
        val pendingIntent = android.app.PendingIntent.getActivity(
            context, notificationId, contentIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )

        val title = languageManager.getString("spending_limit_exceeded_title")
        val text = String.format(
            languageManager.getString("spending_limit_exceeded_text"),
            categoryLabel,
            "%.2f".format(spent),
            "%.2f".format(limit),
            homeCurrency
        )

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
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

        NotificationManagerCompat.from(context).notify(notificationId, notification)
    }

    private fun ensureChannel(context: Context, languageManager: LanguageManager, settings: ExpensesSettings) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        
        val channelId = if (settings.notificationsSystemDefault) {
            CHANNEL_ID
        } else {
            "${CHANNEL_ID}_v${settings.notificationsChannelVersion}"
        }

        if (manager.getNotificationChannel(channelId) != null) return

        val importance = if (settings.notificationsSystemDefault) {
            NotificationManager.IMPORTANCE_DEFAULT
        } else {
            NotificationManager.IMPORTANCE_HIGH
        }

        val channel = NotificationChannel(
            channelId,
            languageManager.getString("spending_limit_channel_name"),
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
}
