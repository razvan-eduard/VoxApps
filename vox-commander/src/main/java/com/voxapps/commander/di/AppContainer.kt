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
import com.voxapps.commander.utils.Logger
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
    val settingsRepository: SettingsRepository = SettingsRepositoryImpl(appContext)

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
    private val l2Engine = OpenAiInterpreter(settingsRepository)
    val localLlmInterpreter = LocalLlmInterpreter(appContext, settingsRepository, modelDownloader)
    val geminiNanoInterpreter = GeminiNanoInterpreter(appContext, settingsRepository)
    val geminiCloudInterpreter = GeminiCloudInterpreter(settingsRepository)
    val masterIntentEngine = IntentDecisionMap(l1Engine, l2Engine, localLlmInterpreter, geminiNanoInterpreter, geminiCloudInterpreter, settingsRepository)
    val intentRouter = IntentRouter(appContext, settingsRepository)

    // --- VIEW MODELS ---
    val mainViewModel = MainViewModel(masterIntentEngine, intentRouter, appStateManager, languageManager)
    val modelManagementViewModel = ModelManagementViewModel(
        settingsRepository,
        appStateManager,
        modelDownloader,
        languageManager,
        appContext
    )

    init {
        kotlinx.coroutines.runBlocking {
            (settingsRepository as SettingsRepositoryImpl).migrateFromSharedPreferencesIfNeeded()
            // Try loading from cache (fast path). If cache empty, splash screen will scan.
            val cache = settingsRepository.getSettingsSnapshot().appCacheJson
            AppRegistry.initFromCache(cache)

            // Load media service settings
            val snapshot = settingsRepository.getSettingsSnapshot()
            com.voxapps.commander.service.SpotifyRemoteManager.setClientId(snapshot.spotifyClientId)
            com.voxapps.commander.domain.intent.handler.PipedSearchHelper.setPipedApiUrl(snapshot.pipedApiUrl)
            com.voxapps.commander.domain.intent.handler.PipedSearchHelper.setPipedRegion(snapshot.pipedRegion)
            com.voxapps.commander.domain.intent.handler.PipedSearchHelper.useNewPipe = snapshot.youtubeUrlEngine == "newpipe"
            if (snapshot.youtubeUrlEngine == "newpipe") {
                com.voxapps.commander.domain.intent.handler.NewPipeExtractorHelper.warmUp()
            }
            com.voxapps.commander.domain.search.LocationHelper.settingsRepo = settingsRepository
        }
        Logger.log("AppContainer init - starting compatibility checks", "AppContainer")
        checkVulkanCrashCookie()
        detectGeminiSupport()
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
