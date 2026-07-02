package com.voxcommander.app

import android.app.Application
import com.voxcommander.app.data.remote.RemoteModelRegistry
import com.voxcommander.app.di.AppContainer
import com.voxcommander.app.service.SpotifyPkceManager
import com.voxcommander.app.utils.LogLevel
import com.voxcommander.app.utils.Logger
import com.voxcommander.app.utils.LoggingFlags
import com.voxcommander.app.utils.NetworkMonitor
import com.voxcommander.app.utils.Strings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

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
        com.voxcommander.app.domain.search.SearchProviderRegistry.init(this)
        com.voxcommander.app.domain.search.SearchProviderRegistry.applyApiKeys(
            container.settingsRepository.getAllSearchProviderApiKeys()
        )

        // Initialize network monitor for realtime connectivity tracking
        NetworkMonitor.init(this)

        // Initialize Spotify PKCE manager and load persisted tokens
        SpotifyPkceManager.init(container.settingsRepository)

        // Initial fetch of the remote model registry - Force update on start to bypass CDN caching
        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            val success = RemoteModelRegistry.fetchJson(container.settingsRepository, force = true)
            if (success) {
                // Force AppStateManager to rebuild its UI state with the fresh models
                container.appStateManager.refreshAll()
            }
            // Also fetch search definitions from remote repo
            com.voxcommander.app.domain.search.SearchProviderRegistry.fetchRemote(container.settingsRepository, force = true)
            com.voxcommander.app.domain.search.SearchProviderRegistry.applyApiKeys(
                container.settingsRepository.getAllSearchProviderApiKeys()
            )
        }
    }
}
