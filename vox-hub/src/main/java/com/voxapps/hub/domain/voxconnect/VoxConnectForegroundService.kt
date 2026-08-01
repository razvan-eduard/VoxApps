package com.voxapps.hub.domain.voxconnect

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.voxapps.hub.HubActivity
import com.voxapps.hub.HubApplication
import com.voxapps.hub.R

/**
 * Keeps [com.voxapps.voxconnect.VoxConnectServer]'s embedded HTTP+WebSocket bridge running inside
 * a genuine Android foreground service instead of a plain background component. A persistent
 * notification is the standard Android contract for "the user can see this is running, don't kill
 * it" — it puts this process in a materially higher priority tier against both the stock LMK and,
 * to whatever extent an OEM battery manager honors it at all, proprietary freezers like Honor's
 * "Fast App Hibernation" (the confirmed root cause of the bridge going unreachable while backgrounded
 * — see VoxConnect desktop's discovery-retry logic for the other half of that mitigation). This is
 * the same mechanism KDE Connect's Android app relies on for the identical problem.
 *
 * Lifecycle is still driven from [HubApplication]'s settings-flow collector (voxConnectEnabled /
 * voxConnectPort) exactly as before — only the entry point changed from calling
 * `VoxConnectServer.start()/stop()` directly to `start()/stop()` here, which do so from inside a
 * foreground-service context.
 */
class VoxConnectForegroundService : Service() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val port = intent?.getIntExtra(EXTRA_PORT, -1)?.takeIf { it > 0 } ?: run {
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, buildNotification(port))
        val server = (application as HubApplication).container.voxConnectServer
        // Mirrors the restart-on-port-change behavior the settings-flow collector previously
        // implemented directly — every start() call here (toggle-on, port change, or process
        // restart with the toggle already on) should reflect the requested port.
        if (server.isRunning()) server.stop()
        server.start(port)
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        (application as HubApplication).container.voxConnectServer.stop()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                "VoxConnect Bridge",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Keeps the VoxConnect desktop bridge reachable in the background" }
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(port: Int): Notification {
        val contentIntent = Intent(this, HubActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, contentIntent, PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("VoxConnect Bridge")
            .setContentText("Listening on port $port for your paired desktop")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "voxconnect_bridge_channel"
        private const val NOTIFICATION_ID = 501
        private const val EXTRA_PORT = "port"

        fun start(context: Context, port: Int) {
            val intent = Intent(context, VoxConnectForegroundService::class.java)
                .putExtra(EXTRA_PORT, port)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, VoxConnectForegroundService::class.java))
        }
    }
}
