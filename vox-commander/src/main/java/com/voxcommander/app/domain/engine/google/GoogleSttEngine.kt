package com.voxcommander.app.domain.engine.google

import android.content.Context
import android.speech.SpeechRecognizer
import com.voxcommander.app.utils.Logger
import com.voxcommander.app.domain.engine.SttEngine
import com.voxcommander.app.utils.Strings

/**
 * Google STT Engine using Intent-based approach to avoid rate limiting.
 * This engine delegates to MainActivity's speechLauncher for actual speech recognition.
 */
class GoogleSttEngine(private val context: Context) : SttEngine {

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

    override suspend fun transcribe(audio: ByteArray): String {
        return ""
    }

    override fun releaseHardware() {
        // No direct hardware usage as it uses system intent
    }

    override fun releaseResources() {
        // No local resources to clear
    }
}
