package com.voxapps.commander.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.voxapps.commander.data.preferences.SettingsRepositoryImpl
import com.voxapps.commander.state.AppStateManager
import com.voxapps.commander.utils.Logger

/**
 * BroadcastReceiver that allows external automation apps (MacroDroid, Tasker, etc.)
 * to trigger the voice assistant without a wake word.
 *
 * Send a broadcast with action [ACTION_TRIGGER_VOICE] to start listening:
 *   adb shell am broadcast -a com.voxapps.commander.TRIGGER_VOICE
 *
 * The receiver checks if WakeWordService is already running:
 * - If running: emits a wake word event via AppStateManager (same as real wake word)
 * - If not running: starts the service with ACTION_EXTERNAL_TRIGGER
 */
class VoiceTriggerReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_TRIGGER_VOICE = "com.voxapps.commander.TRIGGER_VOICE"
        private const val TAG = "VoiceTriggerReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_TRIGGER_VOICE) return

        Logger.log("External voice trigger received", TAG)

        val repo = SettingsRepositoryImpl(context)

        if (!repo.getExternalTriggerEnabledSync()) {
            Logger.log("External trigger disabled in settings — ignoring", TAG)
            return
        }

        val appStateManager = AppStateManager.getInstance(repo, context)

        // Check if WakeWordService is running by checking the listening state
        val isServiceRunning = appStateManager.uiState.value.isWakeWordServiceListening

        if (isServiceRunning) {
            // Service is active — emit wake word event, same as real detection
            Logger.log("Service running — triggering wake word event", TAG)
            appStateManager.onWakeWordDetected()
        } else {
            // Service not running — start it with external trigger action
            Logger.log("Service not running — starting with external trigger", TAG)
            val serviceIntent = Intent(context, WakeWordService::class.java).apply {
                action = WakeWordService.ACTION_EXTERNAL_TRIGGER
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        }
    }
}
