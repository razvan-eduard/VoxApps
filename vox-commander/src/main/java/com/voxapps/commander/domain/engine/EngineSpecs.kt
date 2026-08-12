package com.voxapps.commander.domain.engine

import android.content.Context
import com.voxapps.commander.data.preferences.SettingsRepository
import com.voxapps.commander.data.remote.EngineRuntime
import com.voxapps.commander.data.remote.ModelDownloader
import com.voxapps.commander.data.remote.RemoteModelRegistry
import com.voxapps.commander.domain.model.ImportedModel
import com.voxapps.commander.domain.model.ImportedModelId
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
        langCode: String? = null,
        importId: String? = null
    ): File? {
        val settings = settingsRepo.getSettingsSnapshot()
        // A slugged id names its import directly; a legacy id (or none) falls back to the engine's
        // single slot, which is what pre-migration selections mean.
        val path = if (importId != null && ImportedModelId.slugOf(importId) != null) {
            settings.customModelPaths[importId]
        } else {
            settings.getCustomModelPath(engineKey, langCode)
        }
        if (path.isNullOrBlank()) return null

        val file = File(path)
        if (!file.exists()) {
            Logger.log("Imported model for $engineKey no longer exists: $path", TAG)
            return null
        }

        // Resolved through the engine's own declaration, exactly like a downloaded model. For the
        // single-file engines that is the file itself (`self`); for a directory one it finds the
        // marker — so an import that carries a wrapper folder, which is what picking the parent of
        // a model directory produces, loads instead of failing inside the native library.
        val entry = RemoteModelRegistry.getEntryPoint(engineKey)
            ?: return file.also { Logger.log("Using the imported model for $engineKey: $path", TAG) }

        val resolved = ModelDownloader.resolveEntry(file, entry)
        if (resolved == null) {
            Logger.log("Imported model for $engineKey does not look like one: $path", TAG)
            return null
        }
        Logger.log("Using the imported model for $engineKey: $resolved", TAG)
        return resolved
    }

    /**
     * The imported model for [engineKey] as a row for the model list, or null if there is none.
     *
     * Built from the file rather than from the stored string, so a path left behind by a file the
     * user deleted from outside the app does not become a row that cannot load.
     */
    fun importedRow(
        settingsRepo: SettingsRepository,
        engineKey: String,
        langCode: String? = null
    ): ImportedModel? =
        importedModel(settingsRepo, engineKey, langCode)
            ?.let { ImportedModel.of(ImportedModelId.of(engineKey, langCode), it, engineKey, langCode) }

    /**
     * Every import stored for [engineKey] (and [langCode], where the engine keeps one per
     * language) as rows for the model list — slugged entries plus, until the migration has
     * rewritten it, the legacy single slot. Built from the files rather than the stored strings,
     * so a path whose file the user deleted from outside the app never becomes a row.
     */
    fun importedRows(
        settingsRepo: SettingsRepository,
        engineKey: String,
        langCode: String? = null
    ): List<ImportedModel> {
        val slugged = settingsRepo.getSettingsSnapshot().importsFor(engineKey, langCode)
            .mapNotNull { (id, _) ->
                importedModel(settingsRepo, engineKey, langCode, importId = id)
                    ?.let { ImportedModel.of(id, it, engineKey, langCode) }
            }
        val legacy = importedRow(settingsRepo, engineKey, langCode)
        return slugged + listOfNotNull(legacy)
    }

    private fun localSpec(
        context: Context,
        settingsRepo: SettingsRepository,
        engineKey: String,
        modelId: String?,
        language: String,
        langCode: String?
    ): ModelSpec? {
        if (modelId.isNullOrBlank()) {
            Logger.log("No model selected for $engineKey", TAG)
            return null
        }

        /*
         * An import is chosen, not preferred.
         *
         * It used to win over whatever the user had selected — so the model list could mark one
         * model as chosen while a different file was loaded, and there was no way to go back to a
         * downloaded model without discarding the import. Selecting it is now what loads it, which
         * is the same rule every other model follows.
         *
         * Selected but missing is null rather than a fall-through: quietly loading something else
         * under the name of the model the user picked is how an engine ends up transcribing with a
         * model nobody chose.
         */
        if (ImportedModelId.isImported(modelId)) {
            val imported = importedModel(settingsRepo, engineKey, langCode, importId = modelId)
            if (imported == null) {
                Logger.log("Imported model selected for $engineKey but its file is gone", TAG)
                return null
            }
            return ModelSpec.LocalModel(modelId, imported, language)
        }
        val entry = ModelDownloader(context).resolveEntryPoint(modelId, engineKey)
        if (entry == null) {
            Logger.log("Model '$modelId' for $engineKey is not usable on disk", TAG)
            return null
        }
        return ModelSpec.LocalModel(modelId, entry, language)
    }
}
