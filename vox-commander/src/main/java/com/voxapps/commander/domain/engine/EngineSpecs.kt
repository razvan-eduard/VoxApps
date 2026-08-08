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

    /**
     * The model the user imported for [engineKey], if they imported one and it is still there.
     *
     * Every domain asks this *before* asking the registry, and it has to be that way round: the
     * registry knows nothing about an imported file, so asking it first silently loads a different
     * model — or none — for everyone who has ever used the import button. Shared rather than
     * written per domain, because the wake-word service is what happens otherwise: it did not ask
     * at all, and an import there was stored, displayed as configured, and never loaded.
     *
     * @param langCode for engines that keep one import per language (Vosk); null for the rest.
     */
    fun importedModel(
        settingsRepo: SettingsRepository,
        engineKey: String,
        langCode: String? = null
    ): File? {
        val path = settingsRepo.getSettingsSnapshot().getCustomModelPath(engineKey, langCode)
        if (path.isNullOrBlank()) return null

        val file = File(path)
        if (file.exists()) {
            Logger.log("Using the imported model for $engineKey: $path", TAG)
            return file
        }
        Logger.log("Imported model for $engineKey no longer exists: $path", TAG)
        return null
    }

    private fun localSpec(
        context: Context,
        settingsRepo: SettingsRepository,
        engineKey: String,
        modelId: String?,
        language: String,
        langCode: String?
    ): ModelSpec? {
        importedModel(settingsRepo, engineKey, langCode)?.let { file ->
            return ModelSpec.LocalModel(modelId ?: file.name, file, language)
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
