package com.voxcommander.app.domain.intent.interpreter

import android.content.Context
import com.voxcommander.app.data.preferences.SettingsRepository
import com.voxcommander.app.domain.intent.model.NluIntent
import com.voxcommander.app.utils.Logger
import com.voxcommander.app.utils.Strings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * On-device Gemini Nano interpreter.
 * Requires AICore system service (com.google.android.aicore) on supported devices.
 *
 * Compatibility is checked via AppContainer.detectGeminiSupport() which probes
 * for the AICore package. The geminiIncompatible flag is persisted in settings.
 *
 * Note: The actual on-device inference API requires the AICore SDK
 * (androidx.aicore) which has restricted availability. Until that SDK is
 * available, this interpreter returns null and logs that on-device Gemini Nano
 * is not yet accessible through public APIs.
 */
class GeminiNanoInterpreter(
    private val context: Context,
    private val settingsRepo: SettingsRepository
) : AssistantEngine {

    private val TAG = Strings.Tags.GEMINI_NANO_INTERPRETER
    override suspend fun processCommand(spokenText: String, modelFilterLang: String?): NluIntent? = withContext(Dispatchers.IO) {
        val snapshot = settingsRepo.getSettingsSnapshot()
        if (snapshot.geminiIncompatible) {
            Logger.log("Gemini Nano not available on this device (AICore incompatible)", TAG)
            return@withContext null
        }

        // Check AICore package presence at runtime
        if (!isAicoreAvailable()) {
            Logger.log("AICore package not found — Gemini Nano not available", TAG)
            return@withContext null
        }

        // On-device Gemini Nano inference requires the AICore SDK (androidx.aicore)
        // which has restricted availability. The com.google.ai.client.generativeai SDK
        // only supports cloud API calls, not on-device inference.
        //
        // TODO: When AICore SDK becomes publicly available, implement on-device
        // inference here using the system-bound Gemini Nano model.
        Logger.log("Gemini Nano on-device inference not yet implemented (awaiting AICore SDK)", TAG)
        null
    }

    private fun isAicoreAvailable(): Boolean {
        return try {
            context.packageManager.getPackageInfo("com.google.android.aicore", 0)
            true
        } catch (e: android.content.pm.PackageManager.NameNotFoundException) {
            false
        }
    }
}
