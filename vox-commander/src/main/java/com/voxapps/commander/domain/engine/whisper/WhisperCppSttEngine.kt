package com.voxapps.commander.domain.engine.whisper

import android.content.Context
import com.voxapps.commander.utils.AudioConvert
import com.voxapps.logging.Logger
import com.voxapps.commander.data.preferences.SettingsRepository
import com.voxapps.commander.domain.engine.SttEngine
import com.voxapps.commander.utils.Strings
import com.whispercpp.whisper.WhisperContext
import com.whispercpp.whisper.WhisperLib
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Hybrid STT Engine: Supports Vulkan with automatic fallback to NEON (CPU).
 */
class WhisperCppSttEngine(
    private val context: Context,
    private val settingsRepo: SettingsRepository,
    private val forceGpu: Boolean = false,
    private val onVulkanIncompatible: () -> Unit = {}
) : SttEngine {

    private val TAG = Strings.Tags.WHISPER_CPP_STT_ENGINE
    private var whisperContext: WhisperContext? = null
    private var isUsingGpu = false
    private val loadMutex = Mutex()
    @Volatile private var isTranscribing = false

    /**
     * Public method to trigger initialization and test compatibility.
     * Returns true if initialized (either GPU or CPU).
     */
    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        ensureModelLoaded()
        return@withContext whisperContext != null
    }

    private suspend fun ensureModelLoaded() = withContext(Dispatchers.IO) {
        loadMutex.withLock {
            if (whisperContext != null) return@withContext

            // Lazy-load native .so files only when Whisper is actually used for
            // transcription, avoiding unnecessary ~60MB RSS when another STT engine is active.
            val libDir = File(context.filesDir, "whisper_libs").absolutePath
            WhisperLib.load(libDir)

            // Check for custom model path first
            val snapshot = settingsRepo.getSettingsSnapshot()
            val whisperKey = com.voxapps.commander.data.remote.RemoteModelRegistry.getEngineKeyByExtension(".bin")
            val customPath = whisperKey?.let { snapshot.getCustomModelPath(it) }
            var modelPath = if (!customPath.isNullOrBlank()) {
                Logger.log("Using custom model path: $customPath", TAG)
                customPath
            } else {
                val selectedModelId = snapshot.activeVoiceModelId
                File(
                    context.getExternalFilesDir(null),
                    "$selectedModelId${whisperKey?.let { com.voxapps.commander.data.remote.RemoteModelRegistry.getExtension(it) } ?: ""}"
                ).absolutePath
            }

            if (!File(modelPath).exists()) {
                Logger.log("Model file not found at: $modelPath", TAG)
                return@withContext
            }

            // TIER 1: Try Vulkan (GPU)
            val attemptVulkan = forceGpu && !snapshot.vulkanIncompatible
            if (attemptVulkan) {
                Logger.log("Attempting to initialize with VULKAN...", TAG)
                try {
                    whisperContext = WhisperContext.createContextFromFile(modelPath, useGpu = true)
                    if (whisperContext != null) {
                        isUsingGpu = true
                        Logger.log("SUCCESS: Whisper context initialized with VULKAN", TAG)
                        return@withContext
                    }
                } catch (e: Throwable) {
                    Logger.log("VULKAN init failed. Marking as incompatible. ${e.message}", TAG)
                    kotlinx.coroutines.runBlocking { settingsRepo.setVulkanIncompatible(true) }
                    withContext(Dispatchers.Main) { onVulkanIncompatible() }
                }
            }

            // TIER 2: Fallback to NEON (CPU)
            Logger.log("Initializing with NEON/CPU...", TAG)
            try {
                whisperContext = WhisperContext.createContextFromFile(modelPath, useGpu = false)
                if (whisperContext != null) {
                    isUsingGpu = false
                    Logger.log("SUCCESS: Whisper context initialized Hex NEON/CPU", TAG)
                }
            } catch (e: Throwable) {
                Logger.log("CRITICAL: NEON/CPU initialization failed: ${e.message}", TAG)
            }
        }
    }

    override suspend fun transcribe(audio: ByteArray): String = transcribeWithLanguage(audio, null)

    suspend fun transcribeWithLanguage(audio: ByteArray, langCode: String?): String = withContext(Dispatchers.IO) {
        ensureModelLoaded()
        if (!WhisperLib.isReady()) return@withContext "Error: Native library failed to load"

        // Set the flag BEFORE reading whisperContext so releaseForMemoryPressure()
        // (which checks isTranscribing) cannot free the context in the window
        // between the read and the start of native work.
        isTranscribing = true
        val currentContext = whisperContext ?: run {
            isTranscribing = false
            return@withContext "Error: Whisper engine not initialized"
        }

        try {
            val floatAudio = AudioConvert.pcm16ToFloat(audio)

            // Use 4 threads on CPU for faster inference on modern multi-core devices
            val threads = if (isUsingGpu) 1 else 4

            Logger.log(
                "Transcribing using ${if (isUsingGpu) "VULKAN" else "CPU"} ($threads threads), Lang: ${langCode ?: "auto"}",
                TAG
            )
            
            // Crash-cookie: a native GPU crash during inference cannot be caught by
            // try/catch. Commit a marker before real GPU work; if the process dies,
            // AppContainer detects the leftover cookie next launch and disables Vulkan.
            val snapshot = settingsRepo.getSettingsSnapshot()
            val guardGpu = isUsingGpu && !snapshot.vulkanRuntimeVerified
            if (guardGpu) settingsRepo.setVulkanRuntimeAttemptSync(true)

            // Force language if provided to prevent Cyrillic/Slavic hallucinations
            val result = currentContext.transcribeData(floatAudio, threads, language = langCode, printTimestamp = false)

            if (guardGpu) {
                // Survived a real GPU transcription -> device is genuinely compatible.
                settingsRepo.setVulkanRuntimeAttemptSync(false)
                kotlinx.coroutines.runBlocking { settingsRepo.setVulkanRuntimeVerified(true) }
            }

            return@withContext result.trim()
        } catch (e: Exception) {
            Logger.log("Transcription failed: ${e.message}", TAG)
            if (isUsingGpu) settingsRepo.setVulkanRuntimeAttemptSync(false)
            "Error: ${e.message}"
        } finally {
            isTranscribing = false
        }
    }

    override fun releaseHardware() {
        Logger.log("Releasing native context", TAG)
        whisperContext?.release()
    }

    override fun releaseResources() {
        whisperContext = null
    }

    /**
     * Releases the Whisper context (~150MB+) on system memory pressure while keeping
     * the engine alive. ensureModelLoaded() will transparently reload it on the next
     * transcribe() call. Skipped if a transcription is currently in progress.
     */
    override fun releaseForMemoryPressure() {
        if (isTranscribing) {
            Logger.log("Skipping Whisper release — actively transcribing", TAG)
            return
        }
        if (whisperContext == null) return
        Logger.log("Releasing Whisper context for memory pressure", TAG)
        try { whisperContext?.release() } catch (_: Exception) {}
        whisperContext = null
    }
}
