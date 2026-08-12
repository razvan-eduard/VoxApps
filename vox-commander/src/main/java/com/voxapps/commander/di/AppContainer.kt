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
    val masterIntentEngine = IntentDecisionMap(l1Engine, l2Engine, localLlmInterpreter, settingsRepository)
    val llmHookEngineSelector = com.voxapps.commander.domain.intent.LlmHookEngineSelector(
        openAiEngine = l2Engine,
        localLlmEngine = localLlmInterpreter,
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
            settingsRepositoryImpl.migrateGoogleLlmRemoval()
            settingsRepositoryImpl.migrateLiteRtRemoval()
            settingsRepositoryImpl.migrateWhisperVulkanRetirement()
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
        checkGpuCrashCookies()

        // Scan for Vox satellite apps (contract-implementing companions) at warmup so their NLU
        // domains are available immediately. Re-scanned on refresh from the Integrations screen.
        com.voxapps.commander.domain.integration.VoxSatelliteRegistry.refresh(appContext)
    }

    /**
     * Crash-cookie check, per engine. Before a real GPU inference on an unverified device,
     * [SettingsRepository.setGpuRuntimeAttemptSync] is committed synchronously; a cookie still
     * pending here means the process died mid-attempt. A death is not automatically the GPU's
     * fault, so where the platform keeps an exit record (API 30+) only a crash counts a strike —
     * an OOM kill, a force-stop or a swipe-away clears the cookie and counts nothing — and the
     * verdict latches at two strikes, not one. Where there is no record (API 29, or none kept),
     * nothing is counted: a device can only be condemned on evidence.
     *
     * A legacy whisper cookie (written by the pre-alignment single-engine mechanism) is honored
     * as a whisper attempt once, then cleared.
     */
    private fun checkGpuCrashCookies() {
        val snapshot = settingsRepository.getSettingsSnapshot()
        val legacyWhisperCookie = snapshot.vulkanRuntimeAttempt
        handleGpuCrashCookie(
            SettingsRepository.GPU_WHISPER,
            pending = snapshot.whisperGpuRuntimeAttempt || legacyWhisperCookie,
            strikes = snapshot.whisperGpuCrashStrikes
        )
        handleGpuCrashCookie(
            SettingsRepository.GPU_LLAMA,
            pending = snapshot.llamaGpuRuntimeAttempt,
            strikes = snapshot.llamaGpuCrashStrikes
        )
        if (legacyWhisperCookie) settingsRepository.setVulkanRuntimeAttemptSync(false)
    }

    private fun handleGpuCrashCookie(engine: String, pending: Boolean, strikes: Int) {
        if (!pending) return
        settingsRepository.setGpuRuntimeAttemptSync(engine, false)
        val crashed = lastMainProcessExitWasNativeCrash()
        Logger.log("GPU crash cookie pending for $engine — crash-attributed=$crashed strikes=$strikes", "GpuProbe")
        if (crashed != true) return
        val newStrikes = strikes + 1
        kotlinx.coroutines.runBlocking {
            settingsRepository.setGpuCrashStrikes(engine, newStrikes)
            if (newStrikes >= GPU_CRASH_STRIKE_LIMIT) {
                settingsRepository.setGpuIncompatible(engine, true)
                settingsRepository.setGpuEnabled(engine, false)
                Logger.log("GPU marked incompatible for $engine after $newStrikes crash strikes", "GpuProbe")
            }
        }
    }

    /** True/false when the platform can answer, null when it cannot (API 29, no record). */
    private fun lastMainProcessExitWasNativeCrash(): Boolean? {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) return null
        return try {
            val am = appContext.getSystemService(android.content.Context.ACTIVITY_SERVICE)
                as android.app.ActivityManager
            val record = am.getHistoricalProcessExitReasons(appContext.packageName, 0, 8)
                .firstOrNull { it.processName == appContext.packageName }
                ?: return null
            record.reason == android.app.ApplicationExitInfo.REASON_CRASH_NATIVE
        } catch (e: Exception) {
            Logger.log("Exit-record lookup failed: ${e.message}", "GpuProbe")
            null
        }
    }


    // --- VOICE MANAGER INITIALIZATION ---
    fun initVoiceManager(context: Context, voiceIntentLauncher: com.voxapps.commander.utils.VoiceIntentLauncher) {
        VoiceManager.init(context, settingsRepository, appStateManager)

        // Initialize TTS manager
        TtsManager.init(context, settingsRepository, appStateManager)

        // Initialize conversation handler
        ConversationHandler.init(appStateManager)
    }

    companion object {
        /** Crash-attributed strikes before a GPU verdict latches — one unlucky death is not a
         *  condemnation, two attributed native crashes are. */
        private const val GPU_CRASH_STRIKE_LIMIT = 2

        private const val DB_NAME = "vox-database"
    }
}
