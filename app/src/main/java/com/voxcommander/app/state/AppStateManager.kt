package com.voxcommander.app.state

import androidx.compose.runtime.Immutable

import android.content.Context
import com.voxcommander.app.data.preferences.SettingsRepository
import com.voxcommander.app.data.remote.RemoteModelRegistry
import com.voxcommander.app.domain.intent.registry.AppRegistry
import com.voxcommander.app.utils.Logger
import com.voxcommander.app.utils.Strings
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
    val isIncompatible: Boolean = false
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
    object Idle : AppScanState()
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
        val hasLocationPermission: Boolean = false
    )

    // --- WAKE WORD EVENT (one-shot event, not state) ---
    private val _wakeWordEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val wakeWordEvents: SharedFlow<Unit> = _wakeWordEvents.asSharedFlow()
    private val _runtimeState = MutableStateFlow(RuntimeState(
        canDrawOverlays = com.voxcommander.app.utils.PermissionUtils.canDrawOverlays(context),
        hasMicrophonePermission = com.voxcommander.app.utils.PermissionUtils.hasMicrophonePermission(context),
        hasNotificationPermission = com.voxcommander.app.utils.PermissionUtils.hasNotificationPermission(context),
        hasLocationPermission = com.voxcommander.app.domain.search.LocationHelper.hasLocationPermission(context)
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
    private val _serviceLoadingState = MutableStateFlow(ServiceLoadingState())
    val serviceLoadingState: StateFlow<ServiceLoadingState> = _serviceLoadingState.asStateFlow()

    fun setServiceLoading(state: ServiceLoadingState) {
        _serviceLoadingState.value = state
    }

    fun clearServiceLoading() {
        _serviceLoadingState.value = ServiceLoadingState()
    }

    private val _systemInfo = MutableStateFlow<String>("")
    val systemInfo: StateFlow<String> = _systemInfo.asStateFlow()

    init {
        // Reactive combine: settings + modelMap + runtime -> AppState
        combine(
            repo.settingsFlow,
            RemoteModelRegistry.modelMap,
            _runtimeState
        ) { settings, modelMap, runtime ->
            AppState.fromAppSettings(
                settings = settings,
                context = context,
                availableModels = modelMap,
                voiceState = runtime.voiceState,
                isWakeWordServiceListening = runtime.isWakeWordServiceListening,
                refreshTrigger = runtime.refreshTrigger
            ).copy(
                canDrawOverlays = runtime.canDrawOverlays,
                hasMicrophonePermission = runtime.hasMicrophonePermission,
                hasNotificationPermission = runtime.hasNotificationPermission,
                hasLocationPermission = runtime.hasLocationPermission
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
                canDrawOverlays = com.voxcommander.app.utils.PermissionUtils.canDrawOverlays(context),
                hasMicrophonePermission = com.voxcommander.app.utils.PermissionUtils.hasMicrophonePermission(context),
                hasNotificationPermission = com.voxcommander.app.utils.PermissionUtils.hasNotificationPermission(context),
                hasLocationPermission = com.voxcommander.app.domain.search.LocationHelper.hasLocationPermission(context)
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
            val models = com.voxcommander.app.data.remote.RemoteModelRegistry.getModels(processor)
            val savedSelection = settings.engineModelSelections[processor]
            val newActiveModelId = when {
                // If saved selection exists and is still a valid model, use it
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

    fun saveVoiceModelSelection(engineKey: String, modelId: String) {
        scope.launch { repo.setEngineModelSelection(engineKey, modelId) }
    }

    fun setCustomWhisperModelPath(path: String?) {
        if (path != null) {
            val whisperKey = com.voxcommander.app.data.remote.RemoteModelRegistry.getEngineKeyByExtension(".bin")
            whisperKey?.let { scope.launch { repo.setCustomModelPath(it, path) } }
        }
    }

    fun setCustomVoskModelPath(language: String, path: String?) {
        if (path != null) {
            val voskKey = com.voxcommander.app.data.remote.RemoteModelRegistry.getEngineKeyByExtension(".zip")
            voskKey?.let { scope.launch { repo.setCustomModelPath(it, path, language) } }
        }
    }

    fun setApiKey(key: String?) {
        scope.launch { repo.setApiKey(key) }
    }

    fun setGeminiApiKey(key: String?) {
        scope.launch { repo.setGeminiApiKey(key) }
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
        scope.launch { repo.setWakeWordEngineType(engineType) }
    }

    fun setPicovoiceAccessKey(key: String?) {
        scope.launch { repo.setPicovoiceAccessKey(key) }
    }

    fun setWakeWordSensitivity(sensitivity: String) {
        scope.launch { repo.setWakeWordSensitivity(sensitivity) }
    }

    fun setWakeWordAecEnabled(enabled: Boolean) {
        scope.launch { repo.setWakeWordAecEnabled(enabled) }
    }

    fun setSttSensitivity(sensitivity: String) {
        scope.launch { repo.setSttSensitivity(sensitivity) }
    }

    fun setCloudIntelligenceEnabled(enabled: Boolean) {
        scope.launch { repo.setCloudIntelligenceEnabled(enabled) }
    }

    fun setVerboseLoggingEnabled(enabled: Boolean) {
        scope.launch { repo.setVerboseLoggingEnabled(enabled) }
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

    fun setAppAliasRules(rules: List<com.voxcommander.app.data.preferences.AppAliasRule>) {
        scope.launch { repo.setAppAliasRules(rules) }
    }

    fun setAiProcessor(processor: String) {
        scope.launch {
            repo.setAiProcessor(processor)
            // Auto-set activeIntentModelId from per-engine selection mapping
            val settings = repo.getSettingsSnapshot()
            val models = com.voxcommander.app.data.remote.RemoteModelRegistry.getModels(processor)
            val savedSelection = settings.engineModelSelections[processor]
            val newActiveModelId = when {
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

    // Diagnostic Helpers
    fun refreshNativeLibsStatus() {
        val currentState = _uiState.value
        val voiceProcessor = currentState.voiceProcessor
        val aiProcessor = currentState.aiProcessor
        // Derive compatibility from intentModelReady/voiceModelReady instead of getSettingsSnapshot()
        val geminiIncompatible = aiProcessor == Strings.AiProcessors.GEMINI_NATIVE && !currentState.intentModelReady
        val vulkanIncompatible = voiceProcessor == Strings.Processors.WHISPER_VULKAN && !currentState.voiceModelReady
        
        // (libName, description, engineCategory) — engineCategory: "whisper", "vosk", "llm", "gemini"
        val soFiles = listOf(
            Triple("libwhisper.so", "Core Whisper STT Engine", "whisper"),
            Triple("libggml.so", "GGML Tensor Library", "whisper"),
            Triple("libggml-cpu.so", "GGML CPU Operations", "whisper"),
            Triple("libggml-base.so", "GGML Base Library", "whisper"),
            Triple("libggml-vulkan.so", "Vulkan GPU Acceleration", "whisper"),
            Triple("libomp.so", "OpenMP Multi-threading", "whisper"),
            Triple("libvosk.so", "Vosk Voice Engine", "vosk"),
            Triple("libllm_inference_engine_jni.so", "MediaPipe Llama Engine", "llm"),
            Triple("Google AICore", "Gemini Nano System Service", "gemini")
        )

        val statusList = soFiles.map { (name, desc, category) ->
            val exists: Boolean
            val isIncompatible: Boolean

            if (name == "Google AICore") {
                isIncompatible = geminiIncompatible
                exists = !isIncompatible
            } else {
                // Check system nativeLibraryDir first, then downloaded whisper_libs
                val systemFile = java.io.File(context.applicationInfo.nativeLibraryDir, name)
                val downloadedFile = java.io.File(context.filesDir, "whisper_libs/$name")
                exists = systemFile.exists() || downloadedFile.exists()
                isIncompatible = name.contains("ggml-vulkan") && vulkanIncompatible
            }

            val isActive: Boolean
            val adjustedDesc: String

            if (isIncompatible) {
                isActive = false
                adjustedDesc = "$desc (Incompatible)"
            } else {
                val voiceExt = com.voxcommander.app.data.remote.RemoteModelRegistry.getExtension(voiceProcessor)
                val active = when (category) {
                    "whisper" -> voiceExt == ".bin" || voiceProcessor == Strings.Processors.WHISPER_VULKAN
                    "vosk" -> voiceExt == ".zip"
                    "llm" -> com.voxcommander.app.data.remote.RemoteModelRegistry.isLlmEngine(aiProcessor)
                    "gemini" -> aiProcessor == Strings.AiProcessors.GEMINI_NATIVE
                    else -> false
                }
                isActive = active
                adjustedDesc = desc
            }
            NativeLibStatus(name, exists, isActive, adjustedDesc, isIncompatible)
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
        val whisperKey = com.voxcommander.app.data.remote.RemoteModelRegistry.getEngineKeyByExtension(".bin")
        val extension = whisperKey?.let { com.voxcommander.app.data.remote.RemoteModelRegistry.getExtension(it) } ?: ""
        val modelPath = java.io.File(context.getExternalFilesDir(null), "$modelId$extension").absolutePath

        Logger.log("Starting Vulkan compatibility test with model: $modelPath", "VulkanTest")

        com.voxcommander.app.domain.diagnostic.VulkanProbe(
            context = context,
            modelPath = modelPath
        ) { outcome ->
            when (outcome) {
                com.voxcommander.app.domain.diagnostic.VulkanProbe.Outcome.COMPATIBLE -> {
                    Logger.log("Vulkan test PASSED", "VulkanTest")
                    _vulkanTestState.value = VulkanTestState.RESULT
                    _vulkanTestPassed.value = true
                    scope.launch { repo.setVulkanProbeDone(true) }
                }
                com.voxcommander.app.domain.diagnostic.VulkanProbe.Outcome.INCOMPATIBLE -> {
                    Logger.log("Vulkan test FAILED - switching to NEON", "VulkanTest")
                    _vulkanTestState.value = VulkanTestState.RESULT
                    _vulkanTestPassed.value = false
                    scope.launch {
                        repo.setVulkanIncompatible(true)
                        repo.setVulkanProbeDone(true)
                        setVoiceProcessor(com.voxcommander.app.data.remote.RemoteModelRegistry.getDefaultVoiceEngineKey() ?: "")
                    }
                }
                com.voxcommander.app.domain.diagnostic.VulkanProbe.Outcome.UNDECIDED -> {
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
        @Volatile
        private var instance: AppStateManager? = null

        fun getInstance(repo: SettingsRepository, context: Context): AppStateManager {
            return instance ?: synchronized(this) {
                instance ?: AppStateManager(repo, context).also { instance = it }
            }
        }

        fun get(): AppStateManager? = instance
    }
}
