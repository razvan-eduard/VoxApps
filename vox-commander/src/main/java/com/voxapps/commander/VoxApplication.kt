package com.voxapps.commander

import android.app.Application
import com.voxapps.commander.data.remote.RemoteModelRegistry
import com.voxapps.commander.di.AppContainer
import com.voxapps.commander.service.SpotifyPkceManager
import com.voxapps.commander.utils.LogLevel
import com.voxapps.commander.utils.Logger
import com.voxapps.commander.utils.LoggingFlags
import com.voxapps.commander.utils.NetworkMonitor
import com.voxapps.commander.utils.Strings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
        
        // Initialize Logger early
        val snapshot = container.settingsRepository.getSettingsSnapshot()
        val level = LogLevel.valueOf(snapshot.logLevel)
        Logger.initialize(this, level)
        Logger.setLoggingFlags(LoggingFlags.fromLogLevel(level))
        Logger.setVerboseLoggingEnabled(snapshot.verboseLoggingEnabled)

        // Initialize default model filter language if not set
        if (snapshot.modelFilterLang.isEmpty()) {
            kotlinx.coroutines.runBlocking { container.settingsRepository.setModelFilterLang(Strings.Preferences.DEFAULT_LANGUAGE) }
        }
        
        // Initialize RemoteModelRegistry with app context (for assets/filesDir access)
        RemoteModelRegistry.init(this)

        // Initialize SearchProviderRegistry with app context
        com.voxapps.commander.domain.search.SearchProviderRegistry.init(this)
        com.voxapps.commander.domain.search.SearchProviderRegistry.applyApiKeys(
            container.settingsRepository.getAllSearchProviderApiKeys()
        )
        com.voxapps.commander.domain.search.SearchProviderRegistry.applySharedOpenAiKey(
            container.settingsRepository.getApiKeySync()
        )

        // Initialize IntentCatalog (data-driven intent probe catalog) — must be ready
        // before the app scan in SplashLoadingScreen (AppRegistry.init probes against it).
        com.voxapps.commander.domain.intent.registry.IntentCatalog.init(this)

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

        // Initialize Spotify PKCE manager and load persisted tokens
        SpotifyPkceManager.init(container.settingsRepository)

        // Reconcile the persisted "downloaded" flags with disk truth. The green/on-device
        // indicator is a DataStore set that never gets recomputed, so it drifts when files are
        // deleted externally or an extraction was hollow. Runs in its OWN coroutine, decoupled
        // from the network fetch below (whose readText() has no timeout and could otherwise
        // block this from ever running); it loads the registry locally itself.
        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            reconcileDownloadedModels()
        }

        // Initial fetch of the remote model registry - Force update on start to bypass CDN caching
        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            val success = RemoteModelRegistry.fetchJson(container.settingsRepository, force = true)
            if (success) {
                // Force AppStateManager to rebuild its UI state with the fresh models
                container.appStateManager.refreshAll()
            }

            // Also fetch search definitions from remote repo
            com.voxapps.commander.domain.search.SearchProviderRegistry.fetchRemote(container.settingsRepository, force = true)
            com.voxapps.commander.domain.search.SearchProviderRegistry.applyApiKeys(
                container.settingsRepository.getAllSearchProviderApiKeys()
            )
            com.voxapps.commander.domain.search.SearchProviderRegistry.applySharedOpenAiKey(
                container.settingsRepository.getApiKeySync()
            )
            // Also fetch the intent catalog from the remote repo (hot-reload)
            com.voxapps.commander.domain.intent.registry.IntentCatalog.fetchRemote(container.settingsRepository, force = true)
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
            // RemoteModelRegistry.init() only stores the context — the schema is loaded lazily by
            // fetchJson(). force=false loads from filesDir/assets and returns WITHOUT any network
            // call, so this guarantees the registry is populated before we iterate it.
            RemoteModelRegistry.fetchJson(container.settingsRepository, force = false)

            val downloader = com.voxapps.commander.data.remote.ModelDownloader(this)
            val downloaded = container.settingsRepository.getSettingsSnapshot().downloadedModelIds
            if (downloaded.isEmpty()) return

            var changed = false
            for (engineKey in RemoteModelRegistry.getEngineTypes()) {
                val isArchive = RemoteModelRegistry.isZipEngine(engineKey) ||
                    RemoteModelRegistry.getExtension(engineKey).equals(".tar.bz2", ignoreCase = true)
                for (model in RemoteModelRegistry.getModels(engineKey)) {
                    if (model.id !in downloaded) continue
                    // Archive (dir-based) models use the per-engine validator (which also purges a
                    // corrupt dir); file-based models (Whisper/NLU) just need the file to exist —
                    // validateModel requires a directory and would wrongly reject them.
                    val ok = if (isArchive) {
                        downloader.validateModel(model.id, engineKey)
                    } else {
                        downloader.resolveLocalFile(model.id, engineKey)?.exists() == true
                    }
                    if (!ok) {
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
