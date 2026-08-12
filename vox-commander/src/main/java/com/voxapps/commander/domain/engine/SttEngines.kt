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
 * would silently lose.
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
        // CPU vs GPU is no longer a construction-time fact: the engine reads the shared
        // per-engine GPU state at load, so one branch serves both.
        WhisperCppSttEngine.ENGINE_KEY ->
            WhisperCppSttEngine(context, settingsRepo, onVulkanIncompatible)

        VoskSttEngine.ENGINE_KEY -> VoskSttEngine(context)

        GoogleSttEngine.ENGINE_KEY -> GoogleSttEngine(context, settingsRepo)

        WhisperSttEngine.ENGINE_KEY -> {
            val apiKey = settingsRepo.getCredentialsSnapshot().forEngine(WhisperSttEngine.ENGINE_KEY)
            if (apiKey.isNullOrBlank()) {
                Logger.log("Whisper API selected but no credential is configured", TAG)
                null
            } else {
                WhisperSttEngine(apiKey, settingsRepo)
            }
        }

        else -> {
            Logger.log("No STT implementation for processor '$processorKey'", TAG)
            null
        }
    }
}
