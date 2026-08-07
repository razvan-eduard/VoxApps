package com.voxapps.commander.di

import android.content.Context
import androidx.room.Room
import com.voxapps.commander.data.local.db.VoxDatabase
import com.voxapps.commander.data.preferences.SettingsRepository
import com.voxapps.commander.data.preferences.SettingsRepositoryImpl
import com.voxapps.commander.data.remote.ModelDownloader
import com.voxapps.commander.data.remote.WhisperEngineManager
import com.voxapps.commander.domain.intent.IntentDecisionMap
import com.voxapps.commander.domain.intent.interpreter.FastMapEngine
import com.voxapps.commander.domain.intent.interpreter.LocalLlmInterpreter
import com.voxapps.commander.domain.intent.interpreter.OpenAiInterpreter
import com.voxapps.commander.domain.intent.interpreter.GeminiNanoInterpreter
import com.voxapps.commander.domain.intent.interpreter.GeminiCloudInterpreter
import com.voxapps.commander.domain.intent.registry.AppRegistry
import com.voxapps.commander.domain.intent.router.IntentRouter
import com.voxapps.commander.domain.localization.LanguageManager
import com.voxapps.commander.domain.voice.VoiceManager
import com.voxapps.commander.domain.voice.TtsManager
import com.voxapps.commander.domain.conversation.ConversationHandler
import com.voxapps.commander.state.AppStateManager
import com.voxapps.commander.utils.AppScope
import com.voxapps.logging.Logger
import kotlinx.coroutines.launch
import com.voxapps.commander.ui.viewmodels.MainViewModel
import com.voxapps.commander.ui.viewmodels.ModelManagementViewModel
import com.whispercpp.whisper.WhisperLib

/**
 * Dependency Injection Container for Vox Commander.
 * Centralizes initialization of all application components.
 */
class AppContainer(context: Context) {

    // Always use the application context to avoid leaking an Activity.
    private val appContext = context.applicationContext

    // --- SETTINGS REPOSITORY (DataStore-backed, singleton) ---
    /** Held at the concrete type because the one-time SharedPreferences migration below is an
     *  implementation detail that deliberately isn't on [SettingsRepository] — every other consumer
     *  gets the interface via [settingsRepository], which is the same object. */
    private val settingsRepositoryImpl = SettingsRepositoryImpl(appContext)
    val settingsRepository: SettingsRepository = settingsRepositoryImpl

    // --- SINGLETON MANAGERS ---
    val appStateManager = AppStateManager.getInstance(settingsRepository, appContext)
    val modelDownloader = ModelDownloader(appContext)
    val whisperEngineManager = WhisperEngineManager(appContext, settingsRepository)
    val languageManager = LanguageManager(appContext)
    val voiceOverlayManager = com.voxapps.commander.ui.components.VoiceOverlayManager(appContext, languageManager, appStateManager)

    // --- DATABASE ---
    val database = Room.databaseBuilder(
        appContext,
        VoxDatabase::class.java,
        DB_NAME
    ).fallbackToDestructiveMigration().build()

    val fastMapDao = database.fastMapDao()

    // --- INTENT ENGINES ---
    private val l1Engine = FastMapEngine(fastMapDao)
    private val l2Engine = OpenAiInterpreter(appContext, settingsRepository, fastMapDao)
    val localLlmInterpreter = LocalLlmInterpreter(appContext, settingsRepository, modelDownloader, fastMapDao)
    val geminiNanoInterpreter = GeminiNanoInterpreter(appContext, settingsRepository)
    val geminiCloudInterpreter = GeminiCloudInterpreter(appContext, settingsRepository, fastMapDao)
    val masterIntentEngine = IntentDecisionMap(l1Engine, l2Engine, localLlmInterpreter, geminiNanoInterpreter, geminiCloudInterpreter, settingsRepository)
    val llmHookEngineSelector = com.voxapps.commander.domain.intent.LlmHookEngineSelector(
        openAiEngine = l2Engine,
        geminiCloudEngine = geminiCloudInterpreter,
        localLlmEngine = localLlmInterpreter,
        geminiNanoEngine = geminiNanoInterpreter,
        settingsRepo = settingsRepository
    )
    val intentRouter = IntentRouter(appContext, settingsRepository)

    // --- VIEW MODELS ---
    // Named "ViewModel" but deliberately app-scoped, not Activity-scoped: neither class extends
    // androidx.lifecycle.ViewModel, and MainViewModel is driven by WakeWordService (an OS-created
    // Service that outlives every Activity — see its enqueueVoiceCommand/processVoiceCommand calls),
    // so a voice command arriving with no Activity on screen still has to reach a live instance.
    // Holding them here is what makes the service and the UI talk to the same object; scoping them
    // to an Activity would give the service a second, dead one.
    val mainViewModel = MainViewModel(masterIntentEngine, intentRouter, appStateManager, languageManager)
    val modelManagementViewModel = ModelManagementViewModel(
        settingsRepository,
        appStateManager,
        modelDownloader,
        languageManager,
        appContext
    )

    init {
        // Deliberately still blocking, and kept as small as possible: the migration must finish
        // before ANY settings read (otherwise pre-migration values are read and then overwritten),
        // and AppRegistry's cache has to be in place before the splash screen decides whether it
        // needs to run a full app scan. Both are ordering constraints, not just slow work.
        kotlinx.coroutines.runBlocking {
            settingsRepositoryImpl.migrateFromSharedPreferencesIfNeeded()
            // Try loading from cache (fast path). If cache empty, splash screen will scan.
            // One read, reused below — this used to call getSettingsSnapshot() twice in a row, and
            // on a cold start (empty cache) each call is its own blocking DataStore round-trip.
            val snapshot = settingsRepository.getSettingsSnapshot()
            AppRegistry.initFromCache(snapshot.appCacheJson)
        }

        // Media-service config moved off the blocking path: these are plain setters whose values
        // aren't read until the user actually triggers playback/search, so nothing downstream needs
        // them to be set by the time this constructor returns. (NewPipe's warmUp() already dispatches
        // to AppScope.io internally, so it was never the expensive part here.)
        AppScope.io.launch {
            val snapshot = settingsRepository.getSettingsSnapshot()
            com.voxapps.commander.service.SpotifyRemoteManager.setClientId(snapshot.spotifyClientId)
            com.voxapps.commander.domain.intent.handler.PipedSearchHelper.setPipedApiUrl(snapshot.pipedApiUrl)
            com.voxapps.commander.domain.intent.handler.PipedSearchHelper.setPipedRegion(snapshot.pipedRegion)
            com.voxapps.commander.domain.intent.handler.PipedSearchHelper.useNewPipe = snapshot.youtubeUrlEngine == "newpipe"
            if (snapshot.youtubeUrlEngine == "newpipe") {
                com.voxapps.commander.domain.intent.handler.NewPipeExtractorHelper.warmUp()
            }
        }
        Logger.log("AppContainer init - starting compatibility checks", "AppContainer")
        checkVulkanCrashCookie()
        detectGeminiSupport()

        // Scan for Vox satellite apps (contract-implementing companions) at warmup so their NLU
        // domains are available immediately. Re-scanned on refresh from the Integrations screen.
        com.voxapps.commander.domain.integration.VoxSatelliteRegistry.refresh(appContext)
    }

    /**
     * Checks if Gemini Nano (AICore) is available on the system.
     */
    private fun detectGeminiSupport() {
        try {
            val pm = appContext.packageManager
            pm.getPackageInfo("com.google.android.aicore", 0)
            Logger.log("AICore detected - Gemini Nano supported", "GeminiProbe")
            kotlinx.coroutines.runBlocking { settingsRepository.setGeminiIncompatible(false) }
        } catch (e: android.content.pm.PackageManager.NameNotFoundException) {
            Logger.log("AICore not found - marking Gemini Nano incompatible", "GeminiProbe")
            kotlinx.coroutines.runBlocking { settingsRepository.setGeminiIncompatible(true) }
        } catch (e: Exception) {
            Logger.log("Error probing Gemini support: ${e.message}", "GeminiProbe")
        }
    }

    /**
     * Crash-cookie check. Before a real GPU transcription, [SettingsRepository.setVulkanRuntimeAttemptSync]
     * is committed synchronously. If the process crashed natively during that GPU work,
     * the flag survives to the next launch. Finding it pending here means the last GPU
     * attempt killed the process, so we mark Vulkan incompatible and clear the cookie.
     */
    private fun checkVulkanCrashCookie() {
        val snapshot = settingsRepository.getSettingsSnapshot()
        Logger.log("checkVulkanCrashCookie: pending=${snapshot.vulkanRuntimeAttempt}", "VulkanProbe")
        if (snapshot.vulkanRuntimeAttempt) {
            kotlinx.coroutines.runBlocking { settingsRepository.setVulkanIncompatible(true) }
            kotlinx.coroutines.runBlocking { settingsRepository.setVulkanRuntimeAttemptSync(false) }
            Logger.log(
                "Detected native crash during previous Vulkan GPU use -> marking incompatible",
                "VulkanProbe"
            )
        }
    }


    // --- VOICE MANAGER INITIALIZATION ---
    fun initVoiceManager(context: Context, voiceIntentLauncher: com.voxapps.commander.utils.VoiceIntentLauncher) {
        VoiceManager.init(
            context,
            null, // Engines are now managed internally by VoiceManager via observation
            null,
            null,
            null,
            settingsRepository,
            appStateManager
        )

        // Set offline fallback settings in VoiceManager
        val snapshot = settingsRepository.getSettingsSnapshot()
        VoiceManager.setOfflineFallbackSettings(
            snapshot.offlineFallbackTimeout,
            snapshot.defaultOfflineModel
        )

        // Initialize TTS manager
        TtsManager.init(context, settingsRepository, appStateManager)

        // Initialize conversation handler
        ConversationHandler.init(appStateManager)
    }

    companion object {
        private const val DB_NAME = "vox-database"
    }
}
