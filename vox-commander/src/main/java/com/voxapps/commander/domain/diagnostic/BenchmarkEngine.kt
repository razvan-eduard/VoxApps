package com.voxapps.commander.domain.diagnostic

import android.content.Context
import com.voxapps.commander.data.local.dao.FastMapDao
import com.voxapps.commander.data.preferences.SettingsRepository
import com.voxapps.commander.domain.engine.google.GoogleSttEngine
import com.voxapps.commander.domain.engine.vosk.VoskSttEngine
import com.voxapps.commander.domain.engine.whisper.WhisperCppSttEngine
import com.voxapps.commander.data.remote.RemoteModelRegistry
import com.voxapps.commander.data.remote.ModelDownloader
import com.voxapps.commander.domain.intent.interpreter.LocalLlmInterpreter
import com.voxapps.commander.domain.intent.interpreter.OpenAiInterpreter
import com.voxapps.commander.domain.intent.model.NluIntent
import com.voxapps.commander.domain.model.AppModel
import com.voxapps.commander.domain.engine.whisper.WhisperSttEngine
import com.voxapps.commander.state.AppStateManager
import com.voxapps.commander.state.BenchmarkResult
import com.voxapps.commander.state.VoiceState
import com.voxapps.logging.Logger
import com.voxapps.commander.utils.Strings
import com.whispercpp.whisper.WhisperLib
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Isolated Benchmark Engine to avoid cluttering production VoiceManager.
 */
class BenchmarkEngine(
    private val context: Context,
    private val settingsRepo: SettingsRepository,
    private val appStateManager: AppStateManager,
    private val modelDownloader: ModelDownloader,
    private val fastMapDao: FastMapDao,
    private val localLlmInterpreter: LocalLlmInterpreter? = null
) {
    companion object {
        private const val TAG = "BenchmarkEngine"
        private const val DUMMY_AUDIO_DURATION_MS = 5000L
        private const val SAMPLE_RATE = 16000

        // Standardized test command for intent engines — exercises audio category with artist/track extraction
        private const val INTENT_TEST_COMMAND = "play bohemian rhapsody by queen on youtube"
        private const val INTENT_TEST_EXPECTED_CATEGORY = "audio"
        private const val INTENT_TEST_EXPECTED_ACTION = "audio_youtube"
    }

    suspend fun runFullBenchmark() = withContext(Dispatchers.Default) {
        appStateManager.setVoiceState(VoiceState.BENCHMARKING)
        appStateManager.clearBenchmarkResults()

        // 5 seconds dummy audio (silence — measures init + inference overhead)
        val dummyAudio = ByteArray(SAMPLE_RATE * 2 * (DUMMY_AUDIO_DURATION_MS / 1000).toInt()) { 0 }

        val diagInfo = StringBuilder()
        val snapshot = settingsRepo.getSettingsSnapshot()

        // --- 1. HARDWARE INFO ---
        diagInfo.append("--- HARDWARE CAPABILITIES ---\n")
        // getSystemInfo() is a native method, so the Whisper libraries have to be loaded before it
        // is reached. Nothing loads them at startup — they are downloaded on demand and loaded when
        // Whisper is actually used — so calling it cold threw an UnsatisfiedLinkError that took the
        // whole app down from a settings button. The other two callers (WhisperCppSttEngine,
        // VulkanProbeService) already load first; this one didn't.
        val whisperLibDir = com.voxapps.commander.data.remote.WhisperEngineManager.libDir(context).absolutePath
        diagInfo.append(
            if (WhisperLib.load(whisperLibDir)) WhisperLib.getSystemInfo()
            else "Whisper engine not installed — enable it in Advanced settings for hardware details"
        )
        diagInfo.append("\n")

        // --- 2. VULKAN STATUS ---
        diagInfo.append("--- WHISPER VULKAN COMPATIBILITY ---\n")
        if (snapshot.vulkanIncompatible) {
            diagInfo.append("Status: INCOMPATIBLE (GPU crashes during Whisper inference)\n")
        } else if (snapshot.vulkanRuntimeVerified) {
            diagInfo.append("Status: VERIFIED (GPU inference tested successfully)\n")
        } else if (snapshot.vulkanProbeDone) {
            diagInfo.append("Status: COMPATIBLE (probe passed, inference not yet verified)\n")
        } else {
            diagInfo.append("Status: UNKNOWN (probe not yet run)\n")
        }
        diagInfo.append("\n")

        // --- 3. WHISPER STT BENCHMARKS (CPU + GPU per downloaded model) ---
        // Skip if Whisper engine is not enabled
        if (snapshot.isWhisperSystemEnabled) {
            val whisperKey = com.voxapps.commander.domain.engine.whisper.WhisperCppSttEngine.ENGINE_KEY
            val downloadedWhisperModels = RemoteModelRegistry.getModels(whisperKey).filter {
                snapshot.isModelDownloaded(it.id)
            }

            if (downloadedWhisperModels.isNotEmpty()) {
                diagInfo.append("--- WHISPER MODELS DETECTED ---\n")
                downloadedWhisperModels.forEach {
                    diagInfo.append("ID: ${it.id} | Size: ${it.sizeDescription} | Label: ${it.label}\n")
                }
                diagInfo.append("\n")
            }

            for (model in downloadedWhisperModels) {
                runSingleWhisperBenchmark(model, forceGpu = false, dummyAudio)
                if (settingsRepo.getSettingsSnapshot().vulkanIncompatible) {
                    appStateManager.updateBenchmarkResult(BenchmarkResult(
                        engine = "Whisper Vulkan",
                        model = model.label,
                        inferenceTimeMs = 0,
                        rtf = 0f,
                        isSuccess = false,
                        error = "Skipped (Hardware Incompatible)"
                    ))
                } else {
                    runSingleWhisperBenchmark(model, forceGpu = true, dummyAudio)
                }
            }
        } else {
            diagInfo.append("--- WHISPER STT: Skipped (engine disabled) ---\n\n")
        }

        // --- 4. VOSK STT BENCHMARKS (all downloaded Vosk models) ---
        val voskKey = com.voxapps.commander.domain.engine.vosk.VoskSttEngine.ENGINE_KEY
        val downloadedVoskModels = RemoteModelRegistry.getModels(voskKey).filter {
            snapshot.isModelDownloaded(it.id)
        }

        if (downloadedVoskModels.isNotEmpty()) {
            diagInfo.append("--- VOSK MODELS DETECTED ---\n")
            downloadedVoskModels.forEach {
                diagInfo.append("ID: ${it.id} | Label: ${it.label} | Lang: ${it.langCode ?: "multi"}\n")
            }
            diagInfo.append("Backend: Kaldi-based (libvosk.so)\n\n")

            for (model in downloadedVoskModels) {
                val langCode = model.langCode ?: snapshot.modelFilterLang
                runVoskBenchmark(model.id, model.label, langCode, dummyAudio)
            }
        }

        // --- 5. WHISPER API STT BENCHMARK ---
        val apiKey = settingsRepo.getCredentialsSnapshot().forEngine(WhisperSttEngine.ENGINE_KEY)
        if (!apiKey.isNullOrBlank()) {
            diagInfo.append("--- CLOUD CONNECTIVITY ---\n")
            diagInfo.append("Whisper API: Active (Endpoint: OpenAI)\n")
            diagInfo.append("Key Masked: ${apiKey.take(4)}...${apiKey.takeLast(4)}\n\n")
            runApiBenchmark(apiKey, dummyAudio)
        }

        // --- 6. GOOGLE STT (Initialization-only — intent-based, no direct API) ---
        runGoogleBenchmark()

        // --- 7. LOCAL LLM INTENT BENCHMARK (llama.cpp) ---
        // Reuse the shared LocalLlmInterpreter from AppContainer to avoid a native crash — two
        // separate Engine instances loading the same model concurrently is the exact hazard
        // LocalLlmInterpreter's Mutex exists to prevent (see its own doc comment).
        diagInfo.append("--- LOCAL LLM DIAGNOSTICS ---\n")
        if (localLlmInterpreter != null) {
            val activeModelId = snapshot.activeIntentModelId
            // The interpreter resolves its model file with the *active processor's* key, so this
            // only measures anything when that processor is actually a local LLM. With a cloud
            // processor selected the path is built from an engine that has no extension, the load
            // fails, and the timing reported would be of nothing at all.
            val processorIsLocalLlm = RemoteModelRegistry.isLlmEngine(snapshot.aiProcessor)
            if (activeModelId != null && processorIsLocalLlm) {
                // activeModelId alone doesn't say which local-LLM-capable engine it belongs to —
                // search every local-LLM engine's model list rather than assuming the first one.
                val activeModel = RemoteModelRegistry.getLlmEngineKeys()
                    .asSequence()
                    .flatMap { RemoteModelRegistry.getModels(it).asSequence() }
                    .find { it.id == activeModelId }
                val modelLabel = activeModel?.label ?: activeModelId
                diagInfo.append("Model: $activeModelId | Label: $modelLabel (active)\n")
                runLocalLlmBenchmark(modelLabel, localLlmInterpreter)
            } else if (!processorIsLocalLlm) {
                diagInfo.append("NLU Model: skipped — active processor '${snapshot.aiProcessor}' is not a local LLM\n")
            } else {
                diagInfo.append("NLU Model: No active model selected\n")
            }
        } else {
            diagInfo.append("NLU Model: Interpreter not available\n")
        }
        diagInfo.append("\n")

        // --- 8. OPENAI INTENT BENCHMARK (Cloud) ---
        if (!apiKey.isNullOrBlank() && snapshot.cloudIntelligenceEnabled) {
            diagInfo.append("--- OPENAI INTENT ENGINE ---\n")
            runOpenAiIntentBenchmark()
            diagInfo.append("\n")
        }

        appStateManager.setSystemInfo(diagInfo.toString())
        appStateManager.setVoiceState(VoiceState.IDLE)
    }

    private suspend fun runSingleWhisperBenchmark(model: AppModel, forceGpu: Boolean, audioData: ByteArray) {
        val label = if (forceGpu) "Whisper Vulkan" else "Whisper NEON"
        try {
            val engine = WhisperCppSttEngine(context, settingsRepo, forceGpu = forceGpu)
            val spec = com.voxapps.commander.domain.engine.EngineSpecs.build(
                context, settingsRepo, engine.engineKey, model.id, settingsRepo.getSettingsSnapshot().voiceLanguage
            )
            if (spec == null || !engine.load(spec)) {
                appStateManager.updateBenchmarkResult(BenchmarkResult(label, model.label, 0, 0f, false, "model not loadable"))
                return
            }
            val start = System.currentTimeMillis()
            engine.transcribe(audioData)
            val end = System.currentTimeMillis()
            appStateManager.updateBenchmarkResult(BenchmarkResult(label, model.label, end - start, (end - start).toFloat() / 5000f, true))
            engine.release()
        } catch (e: Exception) {
            appStateManager.updateBenchmarkResult(BenchmarkResult(label, model.label, 0, 0f, false, e.message))
        }
    }

    private suspend fun runVoskBenchmark(modelId: String, modelLabel: String, langCode: String, audioData: ByteArray) {
        try {
            val engine = VoskSttEngine(context)
            // Engines no longer load themselves on first use, so the benchmark must load the model
            // it means to measure — and it measures *this* model, not whichever one is selected.
            val spec = com.voxapps.commander.domain.engine.EngineSpecs.build(
                context, settingsRepo, engine.engineKey, modelId, langCode, langCode
            )
            if (spec == null || !engine.load(spec)) {
                appStateManager.updateBenchmarkResult(BenchmarkResult("Vosk", "$modelLabel ($langCode)", 0, 0f, false, "model not loadable"))
                return
            }
            val start = System.currentTimeMillis()
            engine.transcribe(audioData)
            val end = System.currentTimeMillis()
            val elapsed = end - start
            appStateManager.updateBenchmarkResult(BenchmarkResult("Vosk", "$modelLabel ($langCode)", elapsed, elapsed.toFloat() / DUMMY_AUDIO_DURATION_MS, true))
            engine.release()
        } catch (e: Exception) {
            appStateManager.updateBenchmarkResult(BenchmarkResult("Vosk", "$modelLabel ($langCode)", 0, 0f, false, e.message))
        }
    }

    private suspend fun runGoogleBenchmark() {
        try {
            val start = System.currentTimeMillis()
            val engine = GoogleSttEngine(context, settingsRepo)
            val end = System.currentTimeMillis()
            val available = engine.isAvailable
            engine.release()
            appStateManager.updateBenchmarkResult(BenchmarkResult(
                "Google STT",
                "Intent-based",
                end - start,
                0f,
                available,
                if (available) null else "SpeechRecognizer not available"
            ))
        } catch (e: Exception) {
            appStateManager.updateBenchmarkResult(BenchmarkResult("Google STT", "Intent-based", 0, 0f, false, e.message))
        }
    }

    private suspend fun runApiBenchmark(apiKey: String, audioData: ByteArray) {
        try {
            val engine = WhisperSttEngine(apiKey, settingsRepo)
            val start = System.currentTimeMillis()
            engine.transcribe(audioData)
            val end = System.currentTimeMillis()
            val elapsed = end - start
            appStateManager.updateBenchmarkResult(BenchmarkResult("Whisper API", "Cloud", elapsed, elapsed.toFloat() / DUMMY_AUDIO_DURATION_MS, true))
            engine.release()
        } catch (e: Exception) {
            appStateManager.updateBenchmarkResult(BenchmarkResult("Whisper API", "Cloud", 0, 0f, false, e.message))
        }
    }

    private suspend fun runLocalLlmBenchmark(modelLabel: String, interpreter: LocalLlmInterpreter) {
        try {
            val start = System.currentTimeMillis()
            val result = interpreter.processCommand(INTENT_TEST_COMMAND)
            val end = System.currentTimeMillis()
            val elapsed = end - start

            val validation = validateIntentPayload(result)
            appStateManager.updateBenchmarkResult(BenchmarkResult(
                engine = "Local LLM",
                model = modelLabel,
                inferenceTimeMs = elapsed,
                rtf = 0f,
                isSuccess = validation.isSuccess,
                error = validation.error
            ))
        } catch (e: Exception) {
            appStateManager.updateBenchmarkResult(BenchmarkResult(
                engine = "Local LLM",
                model = modelLabel,
                inferenceTimeMs = 0,
                rtf = 0f,
                isSuccess = false,
                error = e.message
            ))
        }
    }

    private suspend fun runOpenAiIntentBenchmark() {
        try {
            val engine = OpenAiInterpreter(context, settingsRepo, fastMapDao)
            val start = System.currentTimeMillis()
            val result = engine.processCommand(INTENT_TEST_COMMAND)
            val end = System.currentTimeMillis()
            val elapsed = end - start

            val validation = validateIntentPayload(result)
            appStateManager.updateBenchmarkResult(BenchmarkResult(
                engine = "OpenAI Intent",
                model = Strings.Models.GPT_4O_MINI,
                inferenceTimeMs = elapsed,
                rtf = 0f,
                isSuccess = validation.isSuccess,
                error = validation.error
            ))
        } catch (e: Exception) {
            appStateManager.updateBenchmarkResult(BenchmarkResult(
                engine = "OpenAI Intent",
                model = Strings.Models.GPT_4O_MINI,
                inferenceTimeMs = 0,
                rtf = 0f,
                isSuccess = false,
                error = e.message
            ))
        }
    }

    private data class IntentValidation(val isSuccess: Boolean, val error: String?)

    private fun validateIntentPayload(payload: NluIntent?): IntentValidation {
        if (payload == null) {
            return IntentValidation(false, "Returned null (no JSON generated)")
        }
        if (payload.domain.isBlank()) {
            return IntentValidation(false, "domain is blank")
        }
        if (payload.action.isBlank()) {
            return IntentValidation(false, "action is blank")
        }
        // Check if domain/action match expected values for the test command
        if (payload.domain != INTENT_TEST_EXPECTED_CATEGORY) {
            return IntentValidation(false, "domain='${payload.domain}' (expected '$INTENT_TEST_EXPECTED_CATEGORY')")
        }
        if (payload.action != "play") {
            return IntentValidation(false, "action='${payload.action}' (expected 'play')")
        }
        return IntentValidation(true, null)
    }
}
