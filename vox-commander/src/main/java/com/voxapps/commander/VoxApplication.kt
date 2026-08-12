package com.voxapps.commander

import android.app.Application
import com.voxapps.commander.data.remote.NativeLibManager
import com.voxapps.commander.data.remote.RemoteModelRegistry
import com.voxapps.commander.domain.model.ImportedModelId
import com.voxapps.commander.di.AppContainer
import com.voxapps.commander.service.OAuth2Manager
import com.voxapps.logging.Logger
import com.voxapps.services.SchemaCatalog
import com.voxapps.commander.utils.NetworkMonitor
import com.voxapps.commander.utils.Strings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import java.io.File

/**
 * Application class for Vox Commander.
 * Holds the AppContainer as an application-scoped singleton.
 */
class VoxApplication : Application() {

    val container: AppContainer by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        
        // Initialize Logger early — same shape as every other Vox app's Application class now.
        val snapshot = container.settingsRepository.getSettingsSnapshot()
        Logger.initialize(this, "VoxCommander")
        Logger.setEnabled(snapshot.debugLoggingEnabled)
        Logger.setToastsEnabled(snapshot.debugToastsEnabled, this)
        container.settingsRepository.settingsFlow
            .map { it.debugLoggingEnabled to it.debugToastsEnabled }
            .distinctUntilChanged()
            .onEach { (loggingEnabled, toastsEnabled) ->
                Logger.setEnabled(loggingEnabled)
                Logger.setToastsEnabled(toastsEnabled)
            }
            .launchIn(CoroutineScope(SupervisorJob() + Dispatchers.Default))

        // Initialize default model filter language if not set
        if (snapshot.modelFilterLang.isEmpty()) {
            kotlinx.coroutines.runBlocking { container.settingsRepository.setModelFilterLang(Strings.Preferences.DEFAULT_LANGUAGE) }
        }
        
        // Which folder in the schema repository is ours. Set before any registry loads, since a
        // schema resolves its URL against it.
        com.voxapps.services.SchemaRepo.appFolder = "commander"

        // Initialize RemoteModelRegistry with app context (for assets/filesDir access)
        RemoteModelRegistry.init(this)

        // Load the native libraries when they are already on disk — bundled in the APK, or fetched
        // by a previous launch. When they are not, there is nothing to do here: SplashLoadingScreen
        // calls NativeLibManager.init(), which downloads them, loads them and has a UI to report
        // failure through. This has to stay non-fatal either way. loadAll() throws on a missing
        // library on purpose (it otherwise resurfaces as an UnsatisfiedLinkError somewhere
        // unrelated), and a throw here happens in Application.onCreate, before any UI exists — the
        // whole app dies at launch with no way to retry.
        runCatching { NativeLibManager.loadAll(this) }.onFailure {
            // Left to the splash to fetch or repair, and to report through its own UI.
            Logger.d("VoxApplication", "Native libs not loadable yet: ${it.message}")
        }

        // Initialize SearchProviderRegistry with app context
        com.voxapps.commander.domain.search.SearchProviderRegistry.init(this, container.settingsRepository)

        // Initialize MediaServiceRegistry (declared video backends and their instances).
        com.voxapps.commander.domain.media.MediaServiceRegistry.init(this)

        // Initialize IntentCatalog (data-driven intent probe catalog) — must be ready
        // before the app scan in SplashLoadingScreen (AppRegistry.init probes against it).
        com.voxapps.commander.domain.intent.registry.IntentCatalog.init(this)

        // Initialize ApiIntegrationRegistry (declarative per-service API definitions, e.g. Spotify).
        com.voxapps.commander.domain.intent.registry.ApiIntegrationRegistry.init(this)

        // Initialize network monitor for realtime connectivity tracking
        NetworkMonitor.init(this)

        // A probe that fires on a fresh launch races the network stack; give ServiceProbe a way to
        // wait for a validated network first. The wait is capped inside ServiceProbe, so a phone
        // that is genuinely offline still gets its verdict.
        com.voxapps.services.ServiceProbe.awaitNetwork = {
            NetworkMonitor.onlineFlow.first { it }
        }

        // Clean up stale .downloading markers from interrupted model extractions
        val rootDir = getExternalFilesDir(null)
        rootDir?.listFiles()?.forEach { file ->
            if (file.name.endsWith(".downloading")) {
                val modelId = file.name.removeSuffix(".downloading")
                val modelDir = File(rootDir, modelId)
                Logger.log("Found stale .downloading marker for $modelId — cleaning up incomplete model", "VoxApplication")
                modelDir.deleteRecursively()
                file.delete()
                kotlinx.coroutines.runBlocking { container.settingsRepository.setModelDownloaded(modelId, false) }
            }
        }

        // Initialize the generic OAuth2 manager and load Spotify's persisted tokens
        OAuth2Manager.init(container.settingsRepository)
        OAuth2Manager.loadPersisted("spotify")

        // Hydrate the satellite schema cache from disk so it survives process death — must happen
        // before any voice command can be routed, so the collapsed-path cache is trusted immediately
        // rather than looking cold for the app's first command after every restart.
        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            com.voxapps.commander.domain.integration.VoxSatelliteRegistry.loadSchemaCacheFromDisk(this@VoxApplication)
        }

        // Reconcile the persisted "downloaded" flags with disk truth. The green/on-device
        // indicator is a DataStore set that never gets recomputed, so it drifts when files are
        // deleted externally or an extraction was hollow. Runs in its OWN coroutine, decoupled
        // from the network fetch below (whose readText() has no timeout and could otherwise
        // block this from ever running); it loads the registry locally itself.
        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            reconcileDownloadedModels()
        }

        // Warm the local on-device LLM in the background (model load + the system prompt's
        // prefill), so the first spoken/typed command doesn't pay the cold-start cost. Never
        // triggers a download — only fires if a local LLM engine is selected AND its model file
        // is already on disk; otherwise setupLlm() would just no-op anyway on first real use.
        // Fire-and-forget like every other startup task here: the splash screen isn't gated on
        // anything in this file (no setKeepOnScreenCondition), so this never delays first paint.
        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            // The registry loads on a coroutine of its own, so the capability check below can
            // race an empty schema cache — in which case isLlmEngine() answers false and the
            // preload silently never happens (observed on a cold start: the first command then
            // pays the full model load + prefill, and on a slow device that alone can exhaust
            // the interpreter's 90s budget). Waiting — bounded — for the registry to hold an
            // LLM engine is what makes this predicate answer from data.
            kotlinx.coroutines.withTimeoutOrNull(10_000) {
                while (RemoteModelRegistry.getLlmEngineKeys().isEmpty()) kotlinx.coroutines.delay(250)
            }
            val s = container.settingsRepository.getSettingsSnapshot()
            val modelId = s.activeIntentModelId
            val llmModelOnDisk = modelId != null && if (ImportedModelId.isImported(modelId)) {
                com.voxapps.commander.domain.engine.EngineSpecs.importedModel(
                    container.settingsRepository, s.aiProcessor, null, importId = modelId
                ) != null
            } else {
                container.modelDownloader.resolveLocalFile(modelId, s.aiProcessor)?.exists() == true
            }
            if (modelId != null && RemoteModelRegistry.isLlmEngine(s.aiProcessor) && llmModelOnDisk) {
                Logger.log("Preloading local LLM engine ($modelId / ${s.aiProcessor})", "VoxApplication")
                container.localLlmInterpreter.preload(s.modelFilterLang.ifEmpty { null })
            } else {
                // Said out loud because the failure mode of a warm-up is silence: nothing breaks,
                // the first command just pays the whole cold start.
                Logger.log(
                    "LLM preload skipped (model=$modelId, processor=${s.aiProcessor}, " +
                        "isLlm=${RemoteModelRegistry.isLlmEngine(s.aiProcessor)})",
                    "VoxApplication"
                )
            }
        }

        /*
         * An import used to load because it existed; it now loads because it is selected.
         *
         * Anyone who imported a model before that change has a stored path and a selection naming
         * some registry model — which was decorative then and would start loading now, quietly
         * replacing the model they chose. Their import selects itself once, the same thing an
         * import does for itself from here on. A value, not a key: nothing is renamed.
         */
        if (!snapshot.importSelectionMigrated) {
            CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
                val repo = container.settingsRepository
                val settings = repo.getSettingsSnapshot()

                suspend fun adopt(engineKey: String, langCode: String?, isWakeWord: Boolean) {
                    if (engineKey.isBlank()) return
                    val path = settings.getCustomModelPath(engineKey, langCode)
                    if (path.isNullOrBlank()) return
                    val importedId = ImportedModelId.of(engineKey, langCode)
                    val current = if (isWakeWord) settings.activeWakeModelId else settings.activeVoiceModelId
                    if (current == importedId) return
                    Logger.log("Adopting the imported model for $engineKey as its selection", "VoxApplication")
                    repo.setEngineModelSelection(engineKey, importedId)
                    if (isWakeWord) repo.setActiveWakeModelId(importedId)
                    else repo.setActiveVoiceModelId(importedId)
                }

                val voiceEngine = settings.voiceProcessor
                adopt(
                    voiceEngine,
                    settings.modelFilterLang.takeIf { RemoteModelRegistry.isPerLanguage(voiceEngine) },
                    isWakeWord = false
                )

                val wakeEngine = settings.wakeWordEngineType
                adopt(
                    wakeEngine,
                    settings.modelFilterLang.takeIf { RemoteModelRegistry.isPerLanguage(wakeEngine) },
                    isWakeWord = true
                )

                repo.setImportSelectionMigrated(true)
            }
        }

        // Single-slot custom-model entries become named imports: the map key was
        // `engineKey[_lang]`, it is now the slugged ImportedModelId — same values, no file moves.
        // Selections naming the legacy slugless id are rewritten to the slugged one so the row a
        // user had chosen stays chosen. One-shot, same shape as importSelectionMigrated above.
        if (!snapshot.multiImportMigrated) {
            CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
                val repo = container.settingsRepository
                val settings = repo.getSettingsSnapshot()
                val rewrites = mutableMapOf<String, String>() // legacy selection id -> slugged id

                settings.customModelPaths
                    .filterKeys { !it.startsWith("custom:") }
                    .forEach { (legacyKey, path) ->
                        val engineKey: String
                        val lang: String?
                        // A legacy key is `engineKey` or `engineKey_lang`; engine keys themselves
                        // contain '_', so the engine is whichever known engine the key extends.
                        val known = RemoteModelRegistry.getEngineTypes()
                            .filter { legacyKey == it || legacyKey.startsWith("${'$'}{it}_") }
                            .maxByOrNull { it.length }
                        if (known == null) return@forEach
                        engineKey = known
                        lang = legacyKey.removePrefix(engineKey).removePrefix("_").takeIf { it.isNotBlank() }
                        val slug = ImportedModelId.slugFrom(java.io.File(path).name)
                        val newId = ImportedModelId.of(engineKey, lang, slug)
                        repo.putImport(newId, path)
                        repo.setCustomModelPath(engineKey, "", lang)
                        rewrites[ImportedModelId.of(engineKey, lang)] = newId
                        Logger.log("Import for ${'$'}engineKey now named '${'$'}slug'", "VoxApplication")
                    }

                if (rewrites.isNotEmpty()) {
                    settings.activeVoiceModelId?.let { rewrites[it] }?.let { repo.setActiveVoiceModelId(it) }
                    settings.activeWakeModelId?.let { rewrites[it] }?.let { repo.setActiveWakeModelId(it) }
                    settings.engineModelSelections.forEach { (engine, sel) ->
                        rewrites[sel]?.let { repo.setEngineModelSelection(engine, it) }
                    }
                }
                repo.setMultiImportMigrated(true)
            }
        }

        // Copies written by the previous loader cannot say whether they came from the repository or
        // from assets, so they are discarded once and the app starts from what it shipped with.
        SchemaCatalog.discardCopiesFromOlderScheme(
            alreadyDone = snapshot.schemaStoreMigrated,
            markDone = {
                CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
                    container.settingsRepository.setSchemaStoreMigrated(true)
                }
            }
        )

        // The repository is the source of truth unless the user said otherwise, so every launch
        // asks it. Every schema the app loaded takes part — the catalog is the list, so a new one is
        // covered by existing.
        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            if (!container.settingsRepository.getSettingsSnapshot().useRemoteSchemas) {
                Logger.log("Running the schemas this build shipped with, by choice", "VoxApplication")
                return@launch
            }
            val results = SchemaCatalog.refreshAll(
                container.settingsRepository.getSettingsSnapshot().modelRepoBaseUrl
            )
            val updated = results.filterValues { it is com.voxapps.services.RemoteSchema.Refreshed.Updated }
            if (updated.isNotEmpty()) {
                Logger.log("Updated schemas: ${updated.keys}", "VoxApplication")
                container.appStateManager.refreshAll()
            }
        }
    }

    /**
     * Validates every model currently flagged as downloaded against what is actually on disk,
     * clearing the flag for anything missing or corrupt. Runs at startup so the on-device
     * indicator reflects reality (external deletions, hollow extractions) instead of a stale
     * persisted flag. Conservative: only touches models known to the registry, so custom-imported
     * or stale-schema ids are left alone rather than wrongly cleared.
     */
    private suspend fun reconcileDownloadedModels() {
        try {
            // init() has already loaded the copies in force, so the registry is populated here.
            val downloader = com.voxapps.commander.data.remote.ModelDownloader(this)
            val downloaded = container.settingsRepository.getSettingsSnapshot().downloadedModelIds
            if (downloaded.isEmpty()) return

            var changed = false
            for (engineKey in RemoteModelRegistry.getEngineTypes()) {
                for (model in RemoteModelRegistry.getModels(engineKey)) {
                    if (model.id !in downloaded) continue
                    if (!downloader.isModelUsable(model.id, engineKey)) {
                        Logger.log("Reconcile: ${model.id} ($engineKey) not valid on disk — clearing downloaded flag", "VoxApplication")
                        container.settingsRepository.setModelDownloaded(model.id, false)
                        changed = true
                    }
                }
            }
            if (changed) container.appStateManager.refreshAll()
        } catch (e: Exception) {
            Logger.log("reconcileDownloadedModels failed: ${e.message}", "VoxApplication")
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        when (level) {
            TRIM_MEMORY_BACKGROUND, TRIM_MEMORY_MODERATE,
            TRIM_MEMORY_RUNNING_LOW, TRIM_MEMORY_RUNNING_CRITICAL,
            TRIM_MEMORY_UI_HIDDEN -> {
                Logger.log("App-level memory pressure ($level) — releasing heavy native models", "VoxApplication")
                com.voxapps.commander.domain.voice.VoiceManager.releaseForMemoryPressure()
                com.voxapps.commander.domain.voice.TtsManager.releaseForMemoryPressure()
                container.localLlmInterpreter.releaseForMemoryPressure()
            }
        }
    }
}
