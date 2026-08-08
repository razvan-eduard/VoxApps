package com.voxapps.commander.domain.engine

import android.content.Context
import com.voxapps.commander.data.preferences.SettingsRepository
import com.voxapps.commander.data.remote.EngineRuntime
import com.voxapps.commander.data.remote.ModelDownloader
import com.voxapps.commander.data.remote.RemoteModelRegistry
import com.voxapps.logging.Logger
import java.io.File

/**
 * Turns "this engine, this model" into what the engine needs in order to load.
 *
 * One place, for every domain. The alternative is what the engines did before: each resolved its own
 * model, in its own order, and the orders differed — which is how the download validator and the
 * engine could disagree about the same directory, and how a custom import could be honoured by one
 * caller and ignored by another.
 */
object EngineSpecs {

    private const val TAG = "EngineSpecs"

    /**
     * @param modelId the model the user selected for [engineKey], if any.
     * @param langCode the language a per-language custom path is keyed by (Vosk stores one per
     *        language); pass the same value the picker used.
     * @return null when the engine cannot run right now — no model, or nothing on disk. Callers turn
     *         that into a fallback rather than a silent failure.
     */
    fun build(
        context: Context,
        settingsRepo: SettingsRepository,
        engineKey: String,
        modelId: String?,
        language: String,
        langCode: String? = null
    ): ModelSpec? = when (RemoteModelRegistry.runtimeOf(engineKey)) {
        EngineRuntime.LOCAL_FILE -> localSpec(context, settingsRepo, engineKey, modelId, language, langCode)
        EngineRuntime.CLOUD -> ModelSpec.RemoteModel(endpoint = "", credentialRef = engineKey, language = language)
        // An engine with no declared runtime is one this schema does not describe — the cloud and
        // platform processors, until they move into the registry. Platform is the safe reading:
        // it asks the engine to check its own availability rather than to open a file.
        else -> ModelSpec.PlatformModel(language)
    }

    private fun localSpec(
        context: Context,
        settingsRepo: SettingsRepository,
        engineKey: String,
        modelId: String?,
        language: String,
        langCode: String?
    ): ModelSpec? {
        // A model the user imported themselves wins over anything the registry would resolve. It has
        // to be checked first and not merely as a fallback: the registry knows nothing about it, so
        // asking the registry first would silently load a different model — or none — for everyone
        // who has ever used the import button.
        val custom = settingsRepo.getSettingsSnapshot().getCustomModelPath(engineKey, langCode)
        if (!custom.isNullOrBlank()) {
            val file = File(custom)
            if (file.exists()) {
                Logger.log("Using custom model path for $engineKey: $custom", TAG)
                return ModelSpec.LocalModel(modelId ?: file.name, file, language)
            }
            Logger.log("Custom model path for $engineKey no longer exists: $custom", TAG)
        }

        if (modelId.isNullOrBlank()) {
            Logger.log("No model selected for $engineKey", TAG)
            return null
        }
        val entry = ModelDownloader(context).resolveEntryPoint(modelId, engineKey)
        if (entry == null) {
            Logger.log("Model '$modelId' for $engineKey is not usable on disk", TAG)
            return null
        }
        return ModelSpec.LocalModel(modelId, entry, language)
    }
}
