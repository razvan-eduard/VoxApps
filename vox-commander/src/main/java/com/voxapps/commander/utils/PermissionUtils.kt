package com.voxapps.commander.utils

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * Centralized manager for Android system permissions.
 * Handles Microphone, Notifications, and System Overlay.
 */
object PermissionUtils {

    /**
     * Checks if the System Overlay permission is granted.
     */
    fun canDrawOverlays(context: Context): Boolean {
        return Settings.canDrawOverlays(context)
    }

    /**
     * Returns an intent to open the system settings for Overlay permission.
     */
    fun getOverlayPermissionIntent(context: Context): Intent {
        return Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:" + context.packageName)
        )
    }

    /**
     * Checks if the Microphone permission is granted.
     */
    fun hasMicrophonePermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Checks if the Notification permission is granted (Android 13+).
     */
    fun hasNotificationPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true // Automatic on older versions
        }
    }

    /**
     * Checks whether the app is already exempt from battery optimization — some OEMs' aggressive
     * background-process killers unbind WakeWordService while backgrounded, silencing wake-word
     * detection until the OS gets around to rebinding it (confirmed on-device on Honor's "iAware"
     * background management, which logs "Service starting has been prevented by iaware or trustsbase").
     */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        // Context.POWER_SERVICE + `as?`, not the generic getSystemService(Class<T>) overload — the
        // generic overload's type-erased return trips up MockK's relaxed-mock auto-value generation
        // in JVM unit tests (surfaces as a ClassCastException, not a clean null), where `as?` just
        // yields null safely either way.
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
        return powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * Returns an intent that shows the system's "Ignore battery optimizations?" dialog directly for
     * this app (needs `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` in the manifest).
     */
    fun getIgnoreBatteryOptimizationsIntent(context: Context): Intent {
        return Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:" + context.packageName)
        )
    }

    /**
     * Returns the list of runtime permissions needed by the app.
     */
    fun getRequiredRuntimePermissions(): List<String> {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        return permissions
    }
}
