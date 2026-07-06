package com.voxcommander.app.domain.intent.handler

import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.media.AudioManager
import android.net.Uri
import android.provider.Settings
import com.voxcommander.app.domain.intent.model.NluIntent
import com.voxcommander.app.domain.intent.registry.AppRegistry
import com.voxcommander.app.domain.intent.taxonomy.IntentTaxonomy
import com.voxcommander.app.utils.Logger

/**
 * Handles system/settings domain intents: volume up/down, wifi toggle, bluetooth toggle, gps toggle.
 * These are device-level controls that don't require launching a specific app.
 */
class SystemIntentHandler : IntentHandler {

    override fun canHandle(intent: NluIntent): Boolean {
        return intent.domain == IntentTaxonomy.Domains.SETTINGS
    }

    override fun execute(context: Context, intent: NluIntent, resolvedApp: AppRegistry.AppEntry?): Boolean {
        return when (intent.action) {
            IntentTaxonomy.Actions.VOLUME_UP -> adjustVolume(context, AudioManager.ADJUST_RAISE)
            IntentTaxonomy.Actions.VOLUME_DOWN -> adjustVolume(context, AudioManager.ADJUST_LOWER)
            IntentTaxonomy.Actions.WIFI_TOGGLE -> toggleWifi(context)
            IntentTaxonomy.Actions.BLUETOOTH_TOGGLE -> toggleBluetooth(context)
            IntentTaxonomy.Actions.GPS_TOGGLE -> toggleGps(context)
            else -> {
                Logger.log("Unsupported system action: ${intent.action}", TAG)
                false
            }
        }
    }

    private fun adjustVolume(context: Context, direction: Int): Boolean {
        return try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager.adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                direction,
                AudioManager.FLAG_SHOW_UI
            )
            Logger.log("Volume adjusted: $direction", TAG)
            true
        } catch (e: Exception) {
            Logger.log("Failed to adjust volume: ${e.message}", TAG)
            false
        }
    }

    private fun toggleWifi(context: Context): Boolean {
        return try {
            // On Android 10+, direct wifi toggle is restricted.
            // Open the wifi settings page as the best available action.
            val intent = Intent(Settings.ACTION_WIFI_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            Logger.log("Opened WiFi settings", TAG)
            true
        } catch (e: Exception) {
            Logger.log("Failed to open WiFi settings: ${e.message}", TAG)
            false
        }
    }

    private fun toggleBluetooth(context: Context): Boolean {
        return try {
            val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            Logger.log("Opened Bluetooth settings", TAG)
            true
        } catch (e: Exception) {
            Logger.log("Failed to open Bluetooth settings: ${e.message}", TAG)
            false
        }
    }

    private fun toggleGps(context: Context): Boolean {
        return try {
            val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            Logger.log("Opened GPS/Location settings", TAG)
            true
        } catch (e: Exception) {
            Logger.log("Failed to open GPS settings: ${e.message}", TAG)
            false
        }
    }

    companion object {
        private const val TAG = "SystemIntentHandler"

        /**
         * Checks if GPS (location) is currently enabled.
         */
        fun isGpsEnabled(context: Context): Boolean {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            return lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                   lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }

        /**
         * Ensures GPS is enabled before proceeding.
         * If GPS is off, opens location settings so the user can enable it.
         * @return true if GPS is already on, false if it was off (settings opened for user to enable).
         */
        fun ensureGpsEnabled(context: Context): Boolean {
            if (isGpsEnabled(context)) {
                Logger.log("GPS already enabled", TAG)
                return true
            }
            Logger.log("GPS is off, opening location settings", TAG)
            try {
                val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                Logger.log("Failed to open GPS settings: ${e.message}", TAG)
            }
            return false
        }
    }
}
