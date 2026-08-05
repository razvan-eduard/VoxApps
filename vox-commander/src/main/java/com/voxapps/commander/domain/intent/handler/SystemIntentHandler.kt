package com.voxapps.commander.domain.intent.handler

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.location.LocationManager
import android.media.AudioManager
import android.net.Uri
import android.provider.Settings
import com.voxapps.commander.domain.intent.model.NluIntent
import com.voxapps.commander.domain.intent.registry.AppRegistry
import com.voxapps.commander.domain.intent.taxonomy.IntentTaxonomy
import com.voxapps.logging.Logger

/**
 * Handles system/settings domain intents: volume, wifi/bluetooth/gps/airplane-mode/nfc toggle,
 * flashlight, Do Not Disturb, auto-rotate, silent mode.
 * These are device-level controls that don't require launching a specific app.
 */
class SystemIntentHandler : IntentHandler {

    /** Tracks the flashlight's on/off state — Android has no query API for torch state short of
     *  registering a CameraManager.TorchCallback, so this instance-held flag (SystemIntentHandler
     *  is a single long-lived instance per IntentRouter) is the simplest source of truth; it can
     *  drift if the torch is toggled outside this handler (e.g. Quick Settings tile), same
     *  trade-off other assistants make for this exact API gap. */
    private var flashlightOn = false

    override fun canHandle(intent: NluIntent): Boolean {
        // Also accept "system"/"home" — intents.json declares those as a second, generic
        // toggle/status bucket that no handler implements, and the small on-device LLM isn't
        // perfectly consistent about which of the two "system-ish" domains it assigns to the
        // same toggle action (confirmed on-device: "turn off flashlight" landed as domain="system"
        // right after an identical "turn on flashlight" landed as domain="settings"). execute()
        // dispatches purely on intent.action, not domain, so accepting all three is free and
        // removes the dependency on the model picking one specific domain string reliably.
        return intent.domain == IntentTaxonomy.Domains.SETTINGS ||
            intent.domain == IntentTaxonomy.Domains.SYSTEM ||
            intent.domain == IntentTaxonomy.Domains.HOME
    }

    override fun execute(context: Context, intent: NluIntent, resolvedApp: AppRegistry.AppEntry?): Boolean {
        return when (intent.action) {
            IntentTaxonomy.Actions.VOLUME_UP -> adjustVolume(context, AudioManager.ADJUST_RAISE)
            IntentTaxonomy.Actions.VOLUME_DOWN -> adjustVolume(context, AudioManager.ADJUST_LOWER)
            IntentTaxonomy.Actions.WIFI_TOGGLE -> toggleWifi(context)
            IntentTaxonomy.Actions.BLUETOOTH_TOGGLE -> toggleBluetooth(context)
            IntentTaxonomy.Actions.GPS_TOGGLE -> toggleGps(context)
            IntentTaxonomy.Actions.FLASHLIGHT_ON -> setFlashlight(context, true)
            IntentTaxonomy.Actions.FLASHLIGHT_OFF -> setFlashlight(context, false)
            IntentTaxonomy.Actions.FLASHLIGHT_TOGGLE -> setFlashlight(context, null)
            IntentTaxonomy.Actions.AIRPLANE_MODE_TOGGLE -> toggleAirplaneMode(context)
            IntentTaxonomy.Actions.DND_ON -> setDnd(context, true)
            IntentTaxonomy.Actions.DND_OFF -> setDnd(context, false)
            IntentTaxonomy.Actions.DND_TOGGLE -> setDnd(context, null)
            IntentTaxonomy.Actions.NFC_TOGGLE -> toggleNfc(context)
            IntentTaxonomy.Actions.AUTO_ROTATE_ON -> setAutoRotate(context, true)
            IntentTaxonomy.Actions.AUTO_ROTATE_OFF -> setAutoRotate(context, false)
            IntentTaxonomy.Actions.AUTO_ROTATE_TOGGLE -> setAutoRotate(context, null)
            IntentTaxonomy.Actions.SILENT_MODE_ON -> setSilentMode(context, true)
            IntentTaxonomy.Actions.SILENT_MODE_OFF -> setSilentMode(context, false)
            IntentTaxonomy.Actions.SILENT_MODE_TOGGLE -> setSilentMode(context, null)
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

    /** [desiredOn] null means "flip current state" — used only for the explicit `_toggle` action;
     *  `_on`/`_off` pass a fixed target state directly, no guessing involved. */
    private fun setFlashlight(context: Context, desiredOn: Boolean?): Boolean {
        return try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
                cameraManager.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
            if (cameraId == null) {
                Logger.log("No camera with a flash unit found", TAG)
                return false
            }
            val target = desiredOn ?: !flashlightOn
            cameraManager.setTorchMode(cameraId, target)
            flashlightOn = target
            Logger.log("Flashlight set to: $target", TAG)
            true
        } catch (e: CameraAccessException) {
            // Thrown if another app (e.g. the camera app) already holds the camera.
            Logger.log("Failed to set flashlight (camera busy?): ${e.message}", TAG)
            false
        } catch (e: Exception) {
            Logger.log("Failed to set flashlight: ${e.message}", TAG)
            false
        }
    }

    private fun toggleAirplaneMode(context: Context): Boolean {
        return try {
            // Directly flipping AIRPLANE_MODE_ON requires WRITE_SECURE_SETTINGS, a signature-level
            // permission not grantable to a normal third-party app — open the settings panel instead,
            // same redirect pattern as wifi/bluetooth above.
            val intent = Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            Logger.log("Opened Airplane mode settings", TAG)
            true
        } catch (e: Exception) {
            Logger.log("Failed to open Airplane mode settings: ${e.message}", TAG)
            false
        }
    }

    private fun toggleNfc(context: Context): Boolean {
        return try {
            // No public toggle API for third-party apps on modern Android — open the settings panel.
            val intent = Intent(Settings.ACTION_NFC_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            Logger.log("Opened NFC settings", TAG)
            true
        } catch (e: Exception) {
            Logger.log("Failed to open NFC settings: ${e.message}", TAG)
            false
        }
    }

    private fun setDnd(context: Context, desiredOn: Boolean?): Boolean {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!notificationManager.isNotificationPolicyAccessGranted) {
            Logger.log("DND toggle needs notification policy access — opening grant screen", TAG)
            return try {
                context.startActivity(
                    Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                )
                false // access was only just requested, not yet granted — this call didn't apply DND
            } catch (e: Exception) {
                Logger.log("Failed to open notification policy access settings: ${e.message}", TAG)
                false
            }
        }
        return try {
            val currentlyOn = notificationManager.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
            val target = desiredOn ?: !currentlyOn
            notificationManager.setInterruptionFilter(
                if (target) NotificationManager.INTERRUPTION_FILTER_PRIORITY
                else NotificationManager.INTERRUPTION_FILTER_ALL
            )
            Logger.log("DND set to: $target", TAG)
            true
        } catch (e: Exception) {
            Logger.log("Failed to set DND: ${e.message}", TAG)
            false
        }
    }

    private fun setAutoRotate(context: Context, desiredOn: Boolean?): Boolean {
        if (!Settings.System.canWrite(context)) {
            Logger.log("Auto-rotate toggle needs WRITE_SETTINGS — opening grant screen", TAG)
            return try {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${context.packageName}")).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                )
                false
            } catch (e: Exception) {
                Logger.log("Failed to open write-settings grant screen: ${e.message}", TAG)
                false
            }
        }
        return try {
            val currentlyOn = Settings.System.getInt(
                context.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 0
            ) == 1
            val target = desiredOn ?: !currentlyOn
            Settings.System.putInt(
                context.contentResolver, Settings.System.ACCELEROMETER_ROTATION, if (target) 1 else 0
            )
            Logger.log("Auto-rotate set to: $target", TAG)
            true
        } catch (e: Exception) {
            Logger.log("Failed to set auto-rotate: ${e.message}", TAG)
            false
        }
    }

    private fun setSilentMode(context: Context, desiredOn: Boolean?): Boolean {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // Since Android N, entering SILENT/VIBRATE via setRingerMode also requires notification
        // policy access (DND integration) — same gate as setDnd above.
        if (!notificationManager.isNotificationPolicyAccessGranted) {
            Logger.log("Silent mode toggle needs notification policy access — opening grant screen", TAG)
            return try {
                context.startActivity(
                    Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                )
                false
            } catch (e: Exception) {
                Logger.log("Failed to open notification policy access settings: ${e.message}", TAG)
                false
            }
        }
        return try {
            val currentlyOn = audioManager.ringerMode != AudioManager.RINGER_MODE_NORMAL
            val target = desiredOn ?: !currentlyOn
            audioManager.ringerMode = if (target) AudioManager.RINGER_MODE_SILENT else AudioManager.RINGER_MODE_NORMAL
            Logger.log("Silent mode set to: $target", TAG)
            true
        } catch (e: Exception) {
            Logger.log("Failed to set silent mode: ${e.message}", TAG)
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
