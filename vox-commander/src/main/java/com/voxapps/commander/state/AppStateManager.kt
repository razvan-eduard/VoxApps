package com.voxapps.commander.state

import androidx.compose.runtime.Immutable

import android.content.Context
import com.voxapps.commander.data.preferences.SettingsRepository
import com.voxapps.commander.data.remote.RemoteModelRegistry
import com.voxapps.commander.domain.engine.SttEngines
import com.voxapps.commander.domain.engine.whisper.WhisperCppSttEngine
import com.voxapps.commander.domain.engine.vosk.VoskSttEngine
import com.voxapps.commander.domain.intent.registry.AppRegistry
import com.voxapps.logging.Logger
import com.voxapps.commander.utils.Strings
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * State Management Hub for Vox Commander.
 * Centralizes all reactive states (Voice, Intent, Benchmark, Native Libs).
 */
enum class VoiceState {
    IDLE,               // Waiting for user
    LISTENING_WAKEWORD, // Background service active
    LISTENING_COMMAND,  // Actively recording user command
    PROCESSING,         // Engine is transcribing text
    CLEANING,           // Resetting engines
    BENCHMARKING        // Running diagnostics
}

@Immutable
data class BenchmarkResult(
    val engine: String,
    val model: String,
    val inferenceTimeMs: Long,
    val rtf: Float,
    val isSuccess: Boolean,
    val error: String? = null
)

@Immutable
data class NativeLibStatus(
    val name: String,
    val exists: Boolean,
    val isActive: Boolean,
    val description: String,
    val isIncompatible: Boolean = false,
    /** The engine key this library belongs to, or blank for one that serves no single engine.
     *  An engine key rather than a name invented here: the diagnostics screen then groups and
     *  labels these the way every other screen names an engine. */
    val category: String = ""
)

enum class VulkanTestState {
    IDLE,
    RUNNING,
    RESULT
}

@Immutable
data class ServiceLoadingState(
    val isActive: Boolean = false,
    val serviceName: String = "",
    val engineName: String = "",
    val modelName: String = "",
    val isStopping: Boolean = false
)

sealed class AppScanState {
    data object Idle : AppScanState()
    data class Scanning(val current: Int, val total: Int, val appName: String) : AppScanState()
    data class Done(val totalApps: Int, val durationMs: Long) : AppScanState()
}

class AppStateManager private constructor(
    private val repo: SettingsRepository,
    private val context: Context
) {
    private val voiceMutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // --- RUNTIME EPHEMERAL STATE (not persisted) ---
    private data class RuntimeState(
        val voiceState: VoiceState = VoiceState.IDLE,
        val isWakeWordServiceListening: Boolean = false,
        val refreshTrigger: Int = 0,
        val canDrawOverlays: Boolean = false,
        val hasMicrophonePermission: Boolean = false,
        val hasNotificationPermission: Boolean = false,
        val hasLocationPermission: Boolean = false,
        val isIgnoringBatteryOptimizations: Boolean = false
    )

    // --- WAKE WORD EVENT (one-shot event, not state) ---
    private val _wakeWordEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val wakeWordEvents: SharedFlow<Unit> = _wakeWordEvents.asSharedFlow()
    private val _runtimeState = MutableStateFlow(RuntimeState(
        canDrawOverlays = com.voxapps.commander.utils.PermissionUtils.canDrawOverlays(context),
        hasMicrophonePermission = com.voxapps.commander.utils.PermissionUtils.hasMicrophonePermission(context),
        hasNotificationPermission = com.voxapps.commander.utils.PermissionUtils.hasNotificationPermission(context),
        hasLocationPermission = com.voxapps.commander.utils.PermissionUtils.hasLocationPermission(context),
        isIgnoringBatteryOptimizations = com.voxapps.commander.utils.PermissionUtils.isIgnoringBatteryOptimizations(context)
    ))

    // --- CENTRALIZED UI STATE (reactive combination of settings + runtime) ---
    private val _uiState = MutableStateFlow(AppState.initial())
    val uiState: StateFlow<AppState> = _uiState.asStateFlow()

    // Alias for compatibility with older components
    val state: StateFlow<AppState> = uiState

    // --- NON-SETTINGS STATE ---
    private val _benchmarkResults = MutableStateFlow<List<BenchmarkResult>>(emptyList())
    val benchmarkResults: StateFlow<List<BenchmarkResult>> = _benchmarkResults.asStateFlow()

    private val _nativeLibsStatus = MutableStateFlow<List<NativeLibStatus>>(emptyList())
    val nativeLibsStatus: StateFlow<List<NativeLibStatus>> = _nativeLibsStatus.asStateFlow()

    // --- VULKAN TEST STATE ---
    private val _vulkanTestState = MutableStateFlow(VulkanTestState.IDLE)
    val vulkanTestState: StateFlow<VulkanTestState> = _vulkanTestState.asStateFlow()

    private val _vulkanTestPassed = MutableStateFlow<Boolean?>(null)
    val vulkanTestPassed: StateFlow<Boolean?> = _vulkanTestPassed.asStateFlow()

    // --- APP SCAN STATE ---
    private val _appScanState = MutableStateFlow<AppScanState>(AppScanState.Idle)
    val appScanState: StateFlow<AppScanState> = _appScanState.asStateFlow()

    // --- SERVICE LOADING STATE ---
    /**
     * Set while the wake-word service is deliberately shutting down. This is the only part of the
     * loading dialog that cannot be derived: an engine that has gone Idle looks identical whether it
     * is stopping or was never started.
     */
    private val _wakeServiceStopping = MutableStateFlow(false)

    fun setWakeServiceStopping(stopping: Boolean) {
        _wakeServiceStopping.value = stopping
    }

    /**
     * What the loading dialog shows, derived from the wake-word engine rather than pushed to it.
     *
     * The service used to publish this itself: two calls set it and **nine** cleared it, one on each
     * way out of startWakeWordDetection. A tenth exit path that forgot would have left the dialog on
     * screen forever, and nothing about the shape made that visible. Clearing is now implicit —
     * anything other than Loading is not loading — so there is no path to forget.
     *
     * The engine's own state supplies the model name, which is why [EngineState.Loading] carries the
     * spec instead of just a flag.
     */
    val serviceLoadingState: StateFlow<ServiceLoadingState> =
        combine(
            com.voxapps.commander.domain.engine.EngineRegistry.observe(
                com.voxapps.commander.domain.engine.EngineRegistry.Domain.WAKE
            ),
            _wakeServiceStopping
        ) { engineState, stopping ->
            val loading = engineState as? com.voxapps.commander.domain.engine.EngineState.Loading
            if (loading == null && !stopping) {
                ServiceLoadingState()
            } else {
                val spec = loading?.spec as? com.voxapps.commander.domain.engine.ModelSpec.WakeWordModel
                ServiceLoadingState(
                    isActive = true,
                    serviceName = "Wake Word",
                    engineName = com.voxapps.commander.data.remote.RemoteModelRegistry
                        .declaredEngineLabel(repo.getSettingsSnapshot().wakeWordEngineType),
                    modelName = spec?.modelId ?: spec?.keyword.orEmpty(),
                    isStopping = stopping
                )
            }
        }.stateIn(scope, kotlinx.coroutines.flow.SharingStarted.Eagerly, ServiceLoadingState())

    private val _systemInfo = MutableStateFlow<String>("")
    val systemInfo: StateFlow<String> = _systemInfo.asStateFlow()

    init {
        // Reactive combine: settings + modelMap + runtime -> AppState
        combine(
            repo.settingsFlow,
            repo.credentialsFlow,
            RemoteModelRegistry.modelMap,
            _runtimeState
        ) { settings, credentials, modelMap, runtime ->
            AppState.fromAppSettings(
                settings = settings,
                credentials = credentials,
                context = context,
                availableModels = modelMap,
                voiceState = runtime.voiceState,
                isWakeWordServiceListening = runtime.isWakeWordServiceListening,
                refreshTrigger = runtime.refreshTrigger
            ).copy(
                canDrawOverlays = runtime.canDrawOverlays,
                hasMicrophonePermission = runtime.hasMicrophonePermission,
                hasNotificationPermission = runtime.hasNotificationPermission,
                hasLocationPermission = runtime.hasLocationPermission,
                isIgnoringBatteryOptimizations = runtime.isIgnoringBatteryOptimizations
            )
        }.onEach { newState ->
            _uiState.value = newState
            refreshNativeLibsStatus()
        }.launchIn(scope)

        refreshPermissions()
        setupVulkanTestTrigger()
    }

    /**
     * Updates permission-related states in runtime state.
     */
    fun refreshPermissions() {
        _runtimeState.update {
            it.copy(
                canDrawOverlays = com.voxapps.commander.utils.PermissionUtils.canDrawOverlays(context),
                hasMicrophonePermission = com.voxapps.commander.utils.PermissionUtils.hasMicrophonePermission(context),
                hasNotificationPermission = com.voxapps.commander.utils.PermissionUtils.hasNotificationPermission(context),
                hasLocationPermission = com.voxapps.commander.utils.PermissionUtils.hasLocationPermission(context),
                isIgnoringBatteryOptimizations = com.voxapps.commander.utils.PermissionUtils.isIgnoringBatteryOptimizations(context)
            )
        }
    }

    /**
     * Centralized wrapper for runtime state updates.
     */
    private inline fun updateRuntime(mutation: RuntimeState.() -> RuntimeState) {
        _runtimeState.update { it.mutation() }
    }

    // Secure access to native resources
    suspend fun <T> executeSecureVoiceAction(block: suspend () -> T): T {
        return voiceMutex.withLock {
            block()
        }
    }

    fun setVoiceState(state: VoiceState) {
        updateRuntime { copy(voiceState = state) }
    }

    fun onWakeWordDetected() {
        _wakeWordEvents.tryEmit(Unit)
    }

    // --- SETTINGS WRITES (delegate to SettingsRepository, flow updates _uiState reactively) ---

    fun setVoiceProcessor(processor: String) {
        scope.launch {
            repo.setVoiceProcessor(processor)
            // Auto-set activeVoiceModelId from per-engine selection mapping
            val settings = repo.getSettingsSnapshot()
            val models = com.voxapps.commander.data.remote.RemoteModelRegistry.getModels(processor)
            val savedSelection = settings.engineModelSelections[processor]
            val newActiveModelId = when {
                // If saved selection exists and is still a valid model, use it
                // An imported selection is not in the registry's model list; it is valid
                // exactly when its stored path still resolves to a file.
                savedSelection != null &&
                    com.voxapps.commander.domain.model.ImportedModelId.isImported(savedSelection) &&
                    com.voxapps.commander.domain.engine.EngineSpecs.importedModel(
                        repo,
                        com.voxapps.commander.domain.model.ImportedModelId.engineOf(savedSelection).orEmpty(),
                        com.voxapps.commander.domain.model.ImportedModelId.langOf(savedSelection),
                        importId = savedSelection
                    ) != null -> savedSelection
                savedSelection != null && models.any { it.id == savedSelection } -> savedSelection
                // Otherwise use first downloaded model if any
                models.any { settings.isModelDownloaded(it.id) } -> models.first { settings.isModelDownloaded(it.id) }.id
                // Otherwise use first model overall
                models.isNotEmpty() -> models.first().id
                else -> null
            }
            repo.setActiveVoiceModelId(newActiveModelId)
        }
    }

    fun setVoiceLanguage(language: String) {
        scope.launch { repo.setVoiceLanguage(language) }
    }

    fun setVoiceLanguageAutoDetect(enabled: Boolean) {
        scope.launch { repo.setVoiceLanguageAutoDetect(enabled) }
    }

    fun setModelFilterLang(lang: String) {
        scope.launch { repo.setModelFilterLang(lang) }
    }

    fun setActiveVoiceModelId(modelId: String) {
        scope.launch { repo.setActiveVoiceModelId(modelId) }
    }

    fun setActiveWakeModelId(modelId: String?) {
        scope.launch { repo.setActiveWakeModelId(modelId) }
    }

    fun saveVoiceModelSelection(engineKey: String, modelId: String) {
        scope.launch { repo.setEngineModelSelection(engineKey, modelId) }
    }

    /** One writer for every engine credential — see [SettingsRepository.setEngineApiKey]. */
    fun setEngineApiKey(engineKey: String, key: String?) {
        scope.launch { repo.setEngineApiKey(engineKey, key) }
    }

    /** The same, for a search provider that owns its key. */
    fun setSearchProviderApiKey(providerName: String, key: String?) {
        scope.launch { repo.setSearchProviderApiKey(providerName, key) }
    }

    /** Whether the repository is asked for newer schemas at startup — see [AppSettings.useRemoteSchemas]. */
    fun setUseRemoteSchemas(enabled: Boolean) {
        scope.launch { repo.setUseRemoteSchemas(enabled) }
    }

    fun setAppLanguage(lang: String) {
        scope.launch { repo.setLanguage(lang) }
    }

    fun setFirstLaunchCompleted(completed: Boolean) {
        scope.launch { repo.setFirstLaunchCompleted(completed) }
    }

    fun setTutorialCompleted(completed: Boolean) {
        scope.launch { repo.setTutorialCompleted(completed) }
    }

    fun setOfflineFallbackTimeout(seconds: Int) {
        scope.launch { repo.setOfflineFallbackTimeout(seconds) }
    }

    fun setWakeWord(word: String) {
        scope.launch { repo.setWakeWord(word) }
    }

    fun setWakeWordEnabled(enabled: Boolean) {
        scope.launch { repo.setWakeWordEnabled(enabled) }
    }

    fun setCommandQueueEnabled(enabled: Boolean) {
        scope.launch { repo.setCommandQueueEnabled(enabled) }
    }

    fun setWakeWordProfile(profileJson: String?) {
        scope.launch { repo.setWakeWordProfile(profileJson) }
    }

    fun clearWakeWordProfile() {
        scope.launch { repo.setWakeWordProfile(null) }
    }

    fun setWakeWordServiceListening(listening: Boolean) {
        updateRuntime { copy(isWakeWordServiceListening = listening) }
    }

    fun setWakeWordModelPath(path: String?) {
        scope.launch { repo.setWakeWordModelPath(path) }
    }

    fun setWakeWordEngineType(engineType: String) {
        scope.launch {
            val previous = repo.getSettingsSnapshot().wakeWordEngineType
            repo.setWakeWordEngineType(engineType)
            // wakeWordModelPath is a single field shared by all wake-word engines, so a
            // model selected for the previous engine (e.g. a Vosk model) would otherwise be
            // handed to the new engine (e.g. OpenWakeWord) and fail to load. On an actual
            // engine change, clear it — each engine resolves its own default from a null
            // model path. Kept engine/model-name agnostic: no per-engine branches here.
            if (previous != engineType) {
                repo.setWakeWordModelPath(null)
            }
        }
    }

    fun setTtsEngineType(engineType: String) {
        scope.launch { repo.setTtsEngineType(engineType) }
    }

    fun setWakeWordSensitivity(sensitivity: String) {
        scope.launch { repo.setWakeWordSensitivity(sensitivity) }
    }

    fun setThemeDarkMode(mode: String) {
        scope.launch { repo.setThemeDarkMode(mode) }
    }

    fun setThemeColored(colored: Boolean) {
        scope.launch { repo.setThemeColored(colored) }
    }

    fun setWakeWordAecEnabled(enabled: Boolean) {
        scope.launch { repo.setWakeWordAecEnabled(enabled) }
    }

    fun setWakeWordMusicDuckEnabled(enabled: Boolean) {
        scope.launch { repo.setWakeWordMusicDuckEnabled(enabled) }
    }

    fun setSttSensitivity(sensitivity: String) {
        scope.launch { repo.setSttSensitivity(sensitivity) }
    }

    fun setGoogleServicesEnabled(enabled: Boolean) {
        scope.launch {
            repo.setGoogleServicesEnabled(enabled)
            if (!enabled) repo.clearEngineSelections(gatedEngineKeys { key ->
                RemoteModelRegistry.hasCapability(key, "google_service")
            })
        }
    }

    fun setCloudIntelligenceEnabled(enabled: Boolean) {
        scope.launch {
            repo.setCloudIntelligenceEnabled(enabled)
            if (!enabled) repo.clearEngineSelections(gatedEngineKeys { key ->
                RemoteModelRegistry.runtimeOf(key) == com.voxapps.commander.data.remote.EngineRuntime.CLOUD
            })
        }
    }

    /** Turning a gate off also forgets the selections it had authorized — the affected engines
     *  fall back to schema defaults, exactly as if they had never been chosen. Which engines a
     *  gate covers is the schema's call (runtime / capability), never a key list in code, so the
     *  same disable works for engines that don't exist yet. Re-enabling restores nothing: the
     *  gate is consent, not a mute button. */
    private inline fun gatedEngineKeys(predicate: (String) -> Boolean): Set<String> =
        RemoteModelRegistry.getEngineTypes().filterTo(mutableSetOf(), predicate)

    fun setDebugLoggingEnabled(enabled: Boolean) {
        Logger.setEnabled(enabled)
        scope.launch { repo.setDebugLoggingEnabled(enabled) }
    }

    fun setDebugToastsEnabled(enabled: Boolean) {
        Logger.setToastsEnabled(enabled)
        scope.launch { repo.setDebugToastsEnabled(enabled) }
    }

    fun setExperimentalVulkanEnabled(enabled: Boolean) {
        scope.launch { repo.setExperimentalVulkanEnabled(enabled) }
    }

    fun setWhisperSystemEnabled(enabled: Boolean) {
        scope.launch { repo.setWhisperSystemEnabled(enabled) }
    }

    fun setDownloadPreference(preference: String) {
        scope.launch { repo.setDownloadPreference(preference) }
    }

    // --- TTS ---
    fun setTtsEnabled(enabled: Boolean) {
        scope.launch { repo.setTtsEnabled(enabled) }
    }

    fun setTtsSpeechRate(rate: Float) {
        scope.launch { repo.setTtsSpeechRate(rate) }
    }

    fun setTtsPitch(pitch: Float) {
        scope.launch { repo.setTtsPitch(pitch) }
    }

    fun setTtsAudioFocusMode(mode: String) {
        scope.launch { repo.setTtsAudioFocusMode(mode) }
    }

    fun setOverlayTextSize(size: Float) {
        scope.launch { repo.setOverlayTextSize(size) }
    }

    fun setPiperVoiceModelId(id: String?) {
        scope.launch { repo.setPiperVoiceModelId(id) }
    }

    fun setAppAliasRules(rules: List<com.voxapps.commander.data.preferences.AppAliasRule>) {
        scope.launch { repo.setAppAliasRules(rules) }
    }

    fun setAiProcessor(processor: String) {
        scope.launch {
            repo.setAiProcessor(processor)
            // Auto-set activeIntentModelId from per-engine selection mapping
            val settings = repo.getSettingsSnapshot()
            val models = com.voxapps.commander.data.remote.RemoteModelRegistry.getModels(processor)
            val savedSelection = settings.engineModelSelections[processor]
            val newActiveModelId = when {
                // An imported selection is not in the registry's model list; it is valid
                // exactly when its stored path still resolves to a file.
                savedSelection != null &&
                    com.voxapps.commander.domain.model.ImportedModelId.isImported(savedSelection) &&
                    com.voxapps.commander.domain.engine.EngineSpecs.importedModel(
                        repo,
                        com.voxapps.commander.domain.model.ImportedModelId.engineOf(savedSelection).orEmpty(),
                        com.voxapps.commander.domain.model.ImportedModelId.langOf(savedSelection),
                        importId = savedSelection
                    ) != null -> savedSelection
                savedSelection != null && models.any { it.id == savedSelection } -> savedSelection
                models.any { settings.isModelDownloaded(it.id) } -> models.first { settings.isModelDownloaded(it.id) }.id
                models.isNotEmpty() -> models.first().id
                else -> null
            }
            repo.setActiveIntentModelId(newActiveModelId)
        }
    }

    fun setActiveIntentModelId(modelId: String) {
        scope.launch { repo.setActiveIntentModelId(modelId) }
    }

    fun saveIntentModelSelection(engineKey: String, modelId: String) {
        scope.launch { repo.setEngineModelSelection(engineKey, modelId) }
    }

    /** Which provider answers a search in [category] — for spoken queries, not only for the
     *  screen's own test box, which is all the choice used to affect. */
    fun setSearchProvider(category: String, providerName: String) {
        scope.launch { repo.setSearchProviderSelection(category, providerName) }
    }

    // Diagnostic Helpers
    fun refreshNativeLibsStatus() {
        val currentState = _uiState.value
        val voiceProcessor = currentState.voiceProcessor
        val aiProcessor = currentState.aiProcessor
        // Derive compatibility from voiceModelReady instead of getSettingsSnapshot()
        val vulkanIncompatible = voiceProcessor == Strings.Processors.WHISPER_VULKAN && !currentState.voiceModelReady
        
        // (libName, description, engineKey). The third column used to be a naming of its own —
        // "whisper", "vosk", "llm", "gemini" — a fourth way to say which engine something belongs
        // to, matching neither the schema's keys nor the stored processor values nor anything else.
        // These are engine keys, so the screen can name each group from the registry.
        val localLlmEngine = "nlu_llm" // schema key; served by LocalLlmInterpreter
        val soFiles = listOf(
            // ggml has no row of its own: it is linked into libwhisper.so and libllama.so rather
            // than shipped beside them, so a row per ggml file would report libraries that are
            // never fetched as permanently missing.
            Triple("libwhisper.so", "Core Whisper STT Engine", WhisperCppSttEngine.ENGINE_KEY),
            Triple("libomp.so", "OpenMP Multi-threading", WhisperCppSttEngine.ENGINE_KEY),
            // Vulkan is a capability, not a file — the backend is inside libwhisper.so, and whether
            // it can be used is decided by VulkanProbeService running real GPU work in its own
            // process. Answered by the probe's verdict rather than by looking for a library.
            Triple(VULKAN_CAPABILITY, "Vulkan GPU Acceleration", WhisperCppSttEngine.ENGINE_KEY),
            Triple("libvosk.so", "Vosk Voice Engine", VoskSttEngine.ENGINE_KEY),
            Triple("libllama.so", "llama.cpp LLM Engine", localLlmEngine)
        )

        val statusList = soFiles.map { (name, desc, category) ->
            val exists: Boolean
            val isIncompatible: Boolean

            // Each name is asked at the place its own downloader writes: the DLC libraries live in
            // core:nativelibs' version-scoped directory, whisper's in WhisperEngineManager's,
            // llama's in LlamaEngineManager's — one shared path here would answer for at most one.
            fun libPresent(fileName: String) = when {
                fileName in com.voxapps.commander.data.remote.NativeLibManager.libs ->
                    com.voxapps.commander.data.remote.NativeLibManager.hasLib(context, fileName)
                fileName in com.voxapps.commander.data.remote.LlamaEngineManager.LLAMA_LIBS ->
                    java.io.File(context.applicationInfo.nativeLibraryDir, fileName).exists() ||
                        java.io.File(
                            com.voxapps.commander.data.remote.LlamaEngineManager.libDir(context),
                            fileName
                        ).exists()
                else ->
                    java.io.File(context.applicationInfo.nativeLibraryDir, fileName).exists() ||
                        java.io.File(
                            com.voxapps.commander.data.remote.WhisperEngineManager.libDir(context),
                            fileName
                        ).exists()
            }

            if (name == VULKAN_CAPABILITY) {
                // Present once the engine carrying the backend is on device; the probe's verdict is
                // what decides whether it is usable.
                isIncompatible = vulkanIncompatible
                exists = libPresent("libwhisper.so")
            } else {
                exists = libPresent(name)
                isIncompatible = false
            }

            val isActive: Boolean
            val adjustedDesc: String

            if (isIncompatible) {
                isActive = false
                adjustedDesc = "$desc (Incompatible)"
            } else {
                // A library is in use when its engine is the one selected — asked by comparing
                // engine keys, in place of a `when` that restated the same four invented names and
                // answered each with a differently-shaped question (an extension for one, a
                // capability for another, an equality for the third).
                //
                // Whisper on the GPU is the same engine asked to run differently, and the only
                // selection whose stored value is not an engine key of its own.
                val selectedVoiceEngine = SttEngines.backingEngineKey(voiceProcessor)
                isActive = category == selectedVoiceEngine ||
                    category == aiProcessor ||
                    category == currentState.wakeWordEngineType
                adjustedDesc = desc
            }
            NativeLibStatus(name, exists, isActive, adjustedDesc, isIncompatible, category)
        }
        _nativeLibsStatus.value = statusList
    }

    fun updateBenchmarkResult(result: BenchmarkResult) {
        val current = _benchmarkResults.value.toMutableList()
        current.add(result)
        _benchmarkResults.value = current
    }

    fun clearBenchmarkResults() {
        _benchmarkResults.value = emptyList()
    }

    fun setSystemInfo(info: String) {
        _systemInfo.value = info
    }

    // Trigger a refresh - increments refreshTrigger which causes combine to re-emit
    fun refreshAll() {
        updateRuntime { copy(refreshTrigger = refreshTrigger + 1) }
    }

    // --- VULKAN TEST TRIGGER ---
    private fun setupVulkanTestTrigger() {
        combine(
            _uiState,
            _vulkanTestState
        ) { uiState, testState ->
            Pair(uiState, testState)
        }.onEach { (uiState, testState) ->
            val s = repo.getSettingsSnapshot()
            if (testState == VulkanTestState.IDLE &&
                uiState.voiceProcessor == Strings.Processors.WHISPER_VULKAN &&
                uiState.voiceModelReady &&
                !s.vulkanProbeDone &&
                !s.vulkanIncompatible) {
                startVulkanTest()
            }
        }.launchIn(scope)
    }

    private fun startVulkanTest() {
        _vulkanTestState.value = VulkanTestState.RUNNING
        _vulkanTestPassed.value = null

        val modelId = _uiState.value.activeVoiceModelId
        // The GPU probe runs the whisper engine's own model — WHISPER_VULKAN is that engine asked
        // to run on the GPU, not an engine of its own, so it is that engine's key that resolves the
        // file. Asked by extension, this would answer "whichever engine ships .bin files first".
        val whisperKey = SttEngines.backingEngineKey(Strings.Processors.WHISPER_VULKAN)
        val extension = com.voxapps.commander.data.remote.RemoteModelRegistry.getExtension(whisperKey)
        val modelPath = java.io.File(context.getExternalFilesDir(null), "$modelId$extension").absolutePath

        Logger.log("Starting Vulkan compatibility test with model: $modelPath", "VulkanTest")

        com.voxapps.commander.domain.diagnostic.VulkanProbe(
            context = context,
            modelPath = modelPath
        ) { outcome ->
            when (outcome) {
                com.voxapps.commander.domain.diagnostic.VulkanProbe.Outcome.COMPATIBLE -> {
                    Logger.log("Vulkan test PASSED", "VulkanTest")
                    _vulkanTestState.value = VulkanTestState.RESULT
                    _vulkanTestPassed.value = true
                    scope.launch { repo.setVulkanProbeDone(true) }
                }
                com.voxapps.commander.domain.diagnostic.VulkanProbe.Outcome.INCOMPATIBLE -> {
                    Logger.log("Vulkan test FAILED - switching to NEON", "VulkanTest")
                    _vulkanTestState.value = VulkanTestState.RESULT
                    _vulkanTestPassed.value = false
                    scope.launch {
                        repo.setVulkanIncompatible(true)
                        repo.setVulkanProbeDone(true)
                        setVoiceProcessor(com.voxapps.commander.data.remote.RemoteModelRegistry.getDefaultVoiceEngineKey() ?: "")
                    }
                }
                com.voxapps.commander.domain.diagnostic.VulkanProbe.Outcome.UNDECIDED -> {
                    Logger.log("Vulkan test UNDECIDED - will retry later", "VulkanTest")
                    _vulkanTestState.value = VulkanTestState.IDLE
                    _vulkanTestPassed.value = null
                }
            }
        }.start()
    }

    fun dismissVulkanTestResult() {
        _vulkanTestState.value = VulkanTestState.IDLE
        _vulkanTestPassed.value = null
    }

    fun startAppScan() {
        if (_appScanState.value is AppScanState.Scanning) return
        scope.launch(Dispatchers.IO) {
            val startTime = System.currentTimeMillis()
            val json = AppRegistry.rescanAndCache(context) { current, total, appName ->
                _appScanState.value = AppScanState.Scanning(current, total, appName)
            }
            val duration = System.currentTimeMillis() - startTime
            repo.setAppCache(json)
            val totalApps = AppRegistry.allInstalledApps().size
            _appScanState.value = AppScanState.Done(totalApps, duration)
        }
    }

    fun dismissAppScanResult() {
        _appScanState.value = AppScanState.Idle
    }

    companion object {
        /**
         * Names the Vulkan row in the native-component list. Not a file name: the backend is linked
         * into libwhisper.so, so there is nothing on disk to look for.
         */
        const val VULKAN_CAPABILITY = "Vulkan GPU"

        @Volatile
        private var instance: AppStateManager? = null

        /**
         * Kept, unlike the satellites' state managers, which are now plain constructor calls from
         * their containers. This one has entry points the container can't reach: [WakeWordService]
         * and [VoiceTriggerReceiver] are instantiated by the OS with no reference to
         * [com.voxapps.commander.di.AppContainer], and SpeakingOverlay reaches it from a composable
         * via [get]. Until those are given a container handle, the process-wide instance is what
         * makes them observe the same state as the UI rather than a second, divergent copy.
         */
        fun getInstance(repo: SettingsRepository, context: Context): AppStateManager {
            return instance ?: synchronized(this) {
                instance ?: AppStateManager(repo, context).also { instance = it }
            }
        }

        fun get(): AppStateManager? = instance
    }
}
