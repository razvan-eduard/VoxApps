package com.voxapps.commander

import android.app.Application
import com.voxapps.commander.data.remote.NativeLibManager
import com.voxapps.commander.data.remote.RemoteModelRegistry
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

        // Load essential native libraries (if already downloaded)
        NativeLibManager.loadAll(this)

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

        // Warm the local on-device LLM in the background (model load + XNNPACK weight-cache
        // compile), so the first spoken/typed command doesn't pay a 15-25s cold-start cost. Never
        // triggers a download — only fires if a local LLM engine is selected AND its model file
        // is already on disk; otherwise setupLlm() would just no-op anyway on first real use.
        // Fire-and-forget like every other startup task here: the splash screen isn't gated on
        // anything in this file (no setKeepOnScreenCondition), so this never delays first paint.
        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            // The registry is already populated: init() above loads the copies in force, with no
            // network and nothing async, so the capability checks below cannot race an empty one.
            val s = container.settingsRepository.getSettingsSnapshot()
            val modelId = s.activeIntentModelId
            if (modelId != null && RemoteModelRegistry.isLlmEngine(s.aiProcessor) &&
                container.modelDownloader.resolveLocalFile(modelId, s.aiProcessor)?.exists() == true
            ) {
                Logger.log("Preloading local LLM engine ($modelId / ${s.aiProcessor})", "VoxApplication")
                container.localLlmInterpreter.preload(s.modelFilterLang.ifEmpty { null })
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

        // Ask the repository for newer schemas, if the user leaves that on. Every schema the app
        // loaded takes part — the catalog is the list, so a new one is covered by existing.
        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            if (!container.settingsRepository.getSettingsSnapshot().schemaAutoUpdate) {
                Logger.log("Schema updates are off — staying on the copies in force", "VoxApplication")
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
