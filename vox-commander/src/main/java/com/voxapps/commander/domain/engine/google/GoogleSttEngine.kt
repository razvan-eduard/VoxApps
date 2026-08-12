package com.voxapps.commander.domain.engine.google

import android.content.Context
import android.speech.SpeechRecognizer
import com.voxapps.logging.Logger
import com.voxapps.commander.domain.engine.BaseVirtualEngine
import com.voxapps.commander.domain.engine.ModelSpec
import com.voxapps.commander.domain.engine.SttEngine
import com.voxapps.commander.utils.Strings

/**
 * Google STT Engine using Intent-based approach to avoid rate limiting.
 * This engine delegates to MainActivity's speechLauncher for actual speech recognition.
 */
class GoogleSttEngine(
    private val context: Context,
    private val settingsRepo: com.voxapps.commander.data.preferences.SettingsRepository
) : BaseVirtualEngine(), SttEngine {

    override val engineKey: String = ENGINE_KEY

    var isAvailable = false
    private val TAG = Strings.Tags.GOOGLE_STT_ENGINE

    init {
        isAvailable = checkAvailability()
        Logger.log("GoogleSttEngine initialized, isAvailable: $isAvailable", TAG)
    }

    private fun checkAvailability(): Boolean {
        return try {
            SpeechRecognizer.isRecognitionAvailable(context)
        } catch (e: Exception) {
            Logger.log("Error checking availability: ${e.message}", TAG)
            false
        }
    }

    override suspend fun transcribe(audio: ByteArray, langCode: String?): String {
        return ""
    }

    /**
     * There is no model to load — "loading" is asking the OS whether it can recognise speech at all.
     * The platform service can be absent on a device, which is exactly what this reports, and a
     * caller can now see that through [state] instead of reaching for a public flag.
     */
    override suspend fun unavailableReason(spec: ModelSpec): String? {
        // Schema-driven: the "google_service" capability is what the Google-services toggle
        // restricts — a Google-operated service that may run partly on-device but is opaque
        // about what leaves the phone.
        if (com.voxapps.commander.data.remote.RemoteModelRegistry.hasCapability(ENGINE_KEY, "google_service") &&
            !settingsRepo.getSettingsSnapshot().googleServicesEnabled
        ) {
            return "Google services are disabled in settings"
        }
        isAvailable = checkAvailability()
        return if (isAvailable) null else "speech recognition is not available on this device"
    }

    companion object {
        const val ENGINE_KEY = "GOOGLE"
    }
}
