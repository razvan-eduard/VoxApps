package com.voxapps.expenses.domain.recurring

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.voxapps.design.notifications.NotificationChannelVersioning
import com.voxapps.design.notifications.NotificationSoundPlayer
import com.voxapps.expenses.ExpensesActivity
import com.voxapps.expenses.data.RecurringPayment
import com.voxapps.expenses.data.preferences.ExpensesSettings
import com.voxapps.expenses.domain.localization.LanguageManager
import java.text.DateFormat
import java.util.Date

private const val CHANNEL_ID = "recurring_payment_reminders"

/**
 * Tells you a bill is nearly due.
 *
 * A device-local alert and nothing more — no Vox broadcast, no calendar entry, no expense. This app
 * does not depend on any other being installed, so the reminder it owes you is one it posts itself.
 *
 * The figure is named as last month's rather than as what you owe, because that is all it is. A
 * reminder that states an amount with confidence is a reminder that will eventually state the wrong
 * one, and being confidently wrong about money is worse than being vague about it.
 */
object RecurringPaymentNotifier {

    /**
     * Notification ids are offset from the payment id so a reminder and a spending-limit alert for
     * the same row cannot overwrite each other — both call `notify` on the same manager.
     */
    private const val ID_OFFSET = 900_000

    fun notify(
        context: Context,
        languageManager: LanguageManager,
        payment: RecurringPayment,
        dueAtMillis: Long,
        settings: ExpensesSettings
    ) {
        val channelId = if (settings.notificationsSystemDefault) CHANNEL_ID
        else "${CHANNEL_ID}_v${settings.notificationsChannelVersion}"
        ensureChannel(context, languageManager, settings, channelId)

        val notificationId = ID_OFFSET + payment.id.toInt()
        val pendingIntent = android.app.PendingIntent.getActivity(
            context, notificationId, Intent(context, ExpensesActivity::class.java),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )

        val due = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(dueAtMillis))
        val text = payment.expectedAmount
            ?.let {
                languageManager.getString("recurring_reminder_text_amount")
                    .format(payment.vendorLabel, due, "%.2f".format(it), payment.currency.orEmpty())
            }
            ?: languageManager.getString("recurring_reminder_text").format(payment.vendorLabel, due)

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(languageManager.getString("recurring_reminder_title"))
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

    private fun ensureChannel(
        context: Context,
        languageManager: LanguageManager,
        settings: ExpensesSettings,
        channelId: String
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (manager.getNotificationChannel(channelId) == null) {
            val channel = NotificationChannel(
                channelId,
                languageManager.getString("recurring_reminder_channel_name"),
                if (settings.notificationsSystemDefault) NotificationManager.IMPORTANCE_DEFAULT
                else NotificationManager.IMPORTANCE_HIGH
            ).apply {
                if (!settings.notificationsSystemDefault) {
                    // Sound and vibration are played by NotificationSoundPlayer, as everywhere else
                    // the app posts its own alerts.
                    setSound(null, null)
                    enableVibration(false)
                }
            }
            manager.createNotificationChannel(channel)
        }

        // A channel's sound and importance are fixed once created, so a settings change mints a new
        // versioned channel; the old ones would otherwise pile up in system settings forever.
        NotificationChannelVersioning
            .staleChannelIds(manager.notificationChannels.map { it.id }, CHANNEL_ID, channelId)
            .forEach { manager.deleteNotificationChannel(it) }
    }
}
