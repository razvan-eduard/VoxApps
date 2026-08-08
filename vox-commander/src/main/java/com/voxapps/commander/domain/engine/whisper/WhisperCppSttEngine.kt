package com.voxapps.commander.domain.engine.whisper

import android.content.Context
import com.voxapps.commander.utils.AudioConvert
import com.voxapps.logging.Logger
import com.voxapps.commander.data.preferences.SettingsRepository
import com.voxapps.commander.domain.engine.BaseVoxEngine
import com.voxapps.commander.domain.engine.ModelSpec
import com.voxapps.commander.domain.engine.SttEngine
import com.voxapps.commander.utils.Strings
import com.whispercpp.whisper.WhisperContext
import com.whispercpp.whisper.WhisperLib
import kotlinx.coroutines.Dispatchers
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
) : BaseVoxEngine(), SttEngine {

    override val engineKey: String = ENGINE_KEY

    private val TAG = Strings.Tags.WHISPER_CPP_STT_ENGINE
    private var whisperContext: WhisperContext? = null
    private var isUsingGpu = false

    /**
     * Builds the whisper context from an already-resolved model file.
     *
     * The engine no longer decides where its model is: it used to look up its own key by extension,
     * check the custom path, then rebuild the download layout by hand. Locating the file — including
     * honouring a custom import — happens once, in the caller that also knows which model is
     * selected. The GPU/CPU tiering stays, because that is genuinely this engine's business.
     *
     * No mutex here either. Concurrent loads are serialised by [BaseVoxEngine], which is what
     * removes the difference between this engine and Vosk, whose own check-then-act had none.
     */
    override suspend fun onLoad(spec: ModelSpec): Boolean = withContext(Dispatchers.IO) {
        val local = spec as? ModelSpec.LocalModel ?: run {
            Logger.log("Whisper needs a local model, got ${spec::class.simpleName}", TAG)
            return@withContext false
        }

        // Lazy-load native .so files only when Whisper is actually used, avoiding ~60MB RSS
        // when another STT engine is active.
        val libDir = File(context.filesDir, "whisper_libs").absolutePath
        WhisperLib.load(libDir)

        val modelPath = local.entryPoint.absolutePath
        if (!local.entryPoint.exists()) {
            Logger.log("Model file not found at: $modelPath", TAG)
            return@withContext false
        }
        val snapshot = settingsRepo.getSettingsSnapshot()

        // TIER 1: Try Vulkan (GPU)
        run {
            val attemptVulkan = forceGpu && !snapshot.vulkanIncompatible
            if (attemptVulkan) {
                Logger.log("Attempting to initialize with VULKAN...", TAG)
                try {
                    whisperContext = WhisperContext.createContextFromFile(modelPath, useGpu = true)
                    if (whisperContext != null) {
                        isUsingGpu = true
                        Logger.log("SUCCESS: Whisper context initialized with VULKAN", TAG)
                        return@withContext true
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
        return@withContext whisperContext != null
    }

    override fun onUnload() {
        Logger.log("Releasing native context", TAG)
        try { whisperContext?.release() } catch (_: Exception) {}
        whisperContext = null
    }

    override suspend fun transcribe(audio: ByteArray): String = transcribeWithLanguage(audio, null)

    suspend fun transcribeWithLanguage(audio: ByteArray, langCode: String?): String = withContext(Dispatchers.IO) {
        if (!WhisperLib.isReady()) return@withContext "Error: Native library failed to load"

        val currentContext = whisperContext
            ?: return@withContext "Error: Whisper engine not initialized"

        // Pins the model for the duration, so a concurrent unload defers rather than freeing the
        // native context underneath the inference. This replaces a hand-rolled `isTranscribing`
        // flag whose read order had to be commented to stay correct.
        withModel {
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

                result.trim()
            } catch (e: Exception) {
                Logger.log("Transcription failed: ${e.message}", TAG)
                if (isUsingGpu) settingsRepo.setVulkanRuntimeAttemptSync(false)
                "Error: ${e.message}"
            }
        }
    }

    companion object {
        const val ENGINE_KEY = "stt_whisper"
    }
}
