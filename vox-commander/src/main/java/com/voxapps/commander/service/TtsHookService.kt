package com.voxapps.commander.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.voxapps.commander.VoxApplication
import com.voxapps.commander.domain.voice.TtsManager
import com.voxapps.logging.Logger
import com.voxapps.ipc.VoxIpc

/**
 * Speaks a piece of text with Commander's own TTS settings (rate/pitch/voice/language), bypassing
 * the NLU pipeline. Started by [TtsHookReceiver] (external apps) or directly by Commander after a
 * satellite read. [TtsManager] pulls all its settings from [SettingsRepository], so the caller gets
 * "all of Commander's TTS settings" for free.
 */
class TtsHookService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val text = intent?.getStringExtra(VoxIpc.EXTRA_QUERY)?.trim().orEmpty()
        if (text.isEmpty()) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        val container = (application as VoxApplication).container
        TtsManager.init(applicationContext, container.settingsRepository, container.appStateManager)
        Logger.log("TTS hook speaking ${text.length} chars", "TtsHookService")
        TtsManager.speak(text) { stopSelf(startId) }

        return START_NOT_STICKY
    }
}
