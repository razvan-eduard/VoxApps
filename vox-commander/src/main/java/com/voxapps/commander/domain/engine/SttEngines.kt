package com.voxapps.commander.domain.engine

import android.content.Context
import com.voxapps.commander.data.preferences.SettingsRepository
import com.voxapps.commander.domain.engine.google.GoogleSttEngine
import com.voxapps.commander.domain.engine.vosk.VoskSttEngine
import com.voxapps.commander.domain.engine.whisper.WhisperCppSttEngine
import com.voxapps.commander.domain.engine.whisper.WhisperSttEngine
import com.voxapps.logging.Logger
import com.voxapps.commander.utils.Strings

/**
 * The one place a speech-to-text processor key is connected to a class.
 *
 * Replaces `VoiceManager.selectEngine`, which built all four engines on every processor change and
 * then picked one with a `when` over processor names falling through to a `when` over file
 * extensions. That second `when` assumed exactly one engine per extension, so a second `.bin` engine
 * would silently lose — and `WHISPER_VULKAN` is exactly that: the same implementation with GPU
 * inference requested, which here is simply a second entry pointing at the same constructor.
 *
 * Returning null means "this processor cannot be built as configured" — the Whisper API without a
 * credential — which is the honest version of the old `?:` chain that quietly substituted a
 * different engine. The caller decides what to fall back to, and says so in the log.
 */
object SttEngines {

    private const val TAG = "SttEngines"

    fun create(
        processorKey: String,
        context: Context,
        settingsRepo: SettingsRepository,
        onVulkanIncompatible: () -> Unit = {}
    ): SttEngine? = when (processorKey) {
        WhisperCppSttEngine.ENGINE_KEY ->
            WhisperCppSttEngine(context, settingsRepo, forceGpu = false, onVulkanIncompatible)

        Strings.Processors.WHISPER_VULKAN ->
            WhisperCppSttEngine(context, settingsRepo, forceGpu = true, onVulkanIncompatible)

        VoskSttEngine.ENGINE_KEY -> VoskSttEngine(context)

        GoogleSttEngine.ENGINE_KEY -> GoogleSttEngine(context)

        WhisperSttEngine.ENGINE_KEY -> {
            val apiKey = settingsRepo.getSettingsSnapshot().apiKey
            if (apiKey.isNullOrBlank()) {
                Logger.log("Whisper API selected but no credential is configured", TAG)
                null
            } else {
                WhisperSttEngine(apiKey)
            }
        }

        else -> {
            Logger.log("No STT implementation for processor '$processorKey'", TAG)
            null
        }
    }
}
