package com.voxcommander.app.service

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.os.Build
import android.service.notification.NotificationListenerService
import com.voxcommander.app.utils.Logger

/**
 * NotificationListenerService that grants access to MediaSessionManager.getActiveSessions().
 * Once enabled by the user (Settings > Notification access), this service allows
 * VoxCommander to discover active media sessions (Spotify, YouTube Music, etc.)
 * and send transport controls (playFromSearch, pause, next, etc.) directly.
 *
 * This is the same mechanism Google Assistant uses to control media playback.
 */
class MediaSessionListenerService : NotificationListenerService() {

    override fun onListenerConnected() {
        Logger.log("MediaSessionListenerService connected — media session access enabled", TAG)
    }

    override fun onListenerDisconnected() {
        Logger.log("MediaSessionListenerService disconnected — media session access disabled", TAG)
    }

    companion object {
        private const val TAG = "MediaSessionListener"

        /**
         * Checks if the notification listener permission is granted.
         */
        fun isPermissionGranted(context: Context): Boolean {
            val componentName = ComponentName(context, MediaSessionListenerService::class.java)
            val enabledListeners = android.provider.Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners"
            ) ?: return false
            return enabledListeners.contains(componentName.flattenToString())
        }

        /**
         * Opens the system notification access settings so the user can grant permission.
         */
        fun requestPermission(context: Context) {
            val intent = android.content.Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS").apply {
                flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        }

        /**
         * Finds the active MediaController for a given package.
         * Requires notification listener permission to be granted.
         */
        fun getMediaController(context: Context, packageName: String): MediaController? {
            return getActiveSessions(context)?.firstOrNull { it.packageName == packageName }
        }

        /**
         * Returns the most recent (top) active media session, regardless of package.
         * This is the session that was last playing — e.g. if user paused LibreTube,
         * this returns LibreTube's session so "play" resumes it.
         */
        fun getActiveMediaController(context: Context): MediaController? {
            val sessions = getActiveSessions(context) ?: return null
            val session = sessions.firstOrNull()
            if (session == null) {
                Logger.log("No active media sessions found", TAG)
            } else {
                Logger.log("Active media session: ${session.packageName}", TAG)
            }
            return session
        }

        /**
         * Returns all active media sessions, or null if permission not granted.
         */
        fun getActiveSessions(context: Context): List<MediaController>? {
            if (!isPermissionGranted(context)) {
                Logger.log("Notification listener permission not granted — cannot access media sessions", TAG)
                return null
            }

            val sessionManager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val component = ComponentName(context, MediaSessionListenerService::class.java)

            return try {
                sessionManager.getActiveSessions(component)
            } catch (e: SecurityException) {
                Logger.log("Cannot access media sessions: ${e.message}", TAG)
                null
            }
        }
    }
}
