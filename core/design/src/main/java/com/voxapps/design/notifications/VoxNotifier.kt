package com.voxapps.design.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * How an app was told to sound its own alerts — the settings behind [NotificationSettingsCard],
 * handed over as a value so this module needs no knowledge of any app's preference store.
 *
 * [systemDefault] is the fork the rest depends on: with it, the channel is left at system importance
 * with its own sound and nothing is played by hand. Without it, the channel is muted and every alert
 * is sounded by [NotificationSoundPlayer] instead, which is what makes volume and length mean
 * anything at all — Android grants none of that to a channel after it is created.
 */
data class VoxNotificationPrefs(
    val systemDefault: Boolean,
    val channelVersion: Int,
    val soundUri: String?,
    val volume: Int,
    val length: String,
    val vibrationEnabled: Boolean
)

/**
 * A device-local alert, posted the same way in every app.
 *
 * Not a Vox broadcast: this is the app telling the person at the phone something, not one app
 * telling another. Every alert an app posts for itself — a reminder, a limit passed, a rule that
 * recognised a payment — is this, differing only in what it says and where tapping it goes.
 *
 * The channel rotation is the reason this is worth sharing rather than copying: sound, vibration and
 * importance are fixed at the moment a channel is created, so honouring changed settings means
 * minting a new versioned channel and deleting the ones left behind. Three apps had that written out
 * three times, and a rotation that is subtly wrong in one of them shows up as a notification nobody
 * can hear, months later, in a setting nobody thinks to look at.
 */
object VoxNotifier {

    fun post(
        context: Context,
        channelBaseId: String,
        channelName: String,
        notificationId: Int,
        title: String,
        text: String,
        contentIntent: Intent,
        prefs: VoxNotificationPrefs,
        smallIcon: Int = android.R.drawable.ic_dialog_alert,
        /** False keeps the alert to one line — for text short enough that expanding it shows the
         *  same thing twice. */
        bigText: Boolean = true
    ) {
        val channelId = channelIdOf(channelBaseId, prefs)
        ensureChannel(context, channelBaseId, channelId, channelName, prefs)

        val pendingIntent = PendingIntent.getActivity(
            context, notificationId, contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(smallIcon)
            .setContentTitle(title)
            .setContentText(text)
            .apply { if (bigText) setStyle(NotificationCompat.BigTextStyle().bigText(text)) }
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        if (!prefs.systemDefault) {
            NotificationSoundPlayer.play(
                context = context,
                soundUri = prefs.soundUri,
                volume = prefs.volume,
                length = prefs.length,
                vibrationEnabled = prefs.vibrationEnabled
            )
        }

        NotificationManagerCompat.from(context).notify(notificationId, notification)
    }

    private fun channelIdOf(baseId: String, prefs: VoxNotificationPrefs): String =
        if (prefs.systemDefault) baseId else "${baseId}_v${prefs.channelVersion}"

    private fun ensureChannel(
        context: Context,
        baseId: String,
        channelId: String,
        channelName: String,
        prefs: VoxNotificationPrefs
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (manager.getNotificationChannel(channelId) == null) {
            val channel = NotificationChannel(
                channelId,
                channelName,
                if (prefs.systemDefault) NotificationManager.IMPORTANCE_DEFAULT
                else NotificationManager.IMPORTANCE_HIGH
            ).apply {
                if (!prefs.systemDefault) {
                    setSound(null, null)
                    enableVibration(false)
                }
            }
            manager.createNotificationChannel(channel)
        }

        NotificationChannelVersioning
            .staleChannelIds(manager.notificationChannels.map { it.id }, baseId, channelId)
            .forEach { manager.deleteNotificationChannel(it) }
    }
}
