package com.voxapps.commander.domain.intent.interpreter

import android.content.Context
import com.voxapps.logging.Logger
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import com.voxapps.commander.domain.intent.model.NluIntent
import com.voxapps.commander.data.preferences.SettingsRepository
import com.voxapps.commander.data.remote.ModelDownloader
import com.voxapps.commander.data.remote.RemoteModelRegistry
import com.voxapps.commander.utils.Strings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.security.MessageDigest

/** Generous — first-ever load of a multi-GB on-device model can genuinely take a while — this is a
 *  safety net against a stuck/corrupt model wedging every request forever, not a normal-path budget.
 *  See [LocalLlmInterpreter.mutex]'s doc comment for why a serialized call can still need one. */
private const val LOCAL_LLM_TIMEOUT_MS = 90_000L

/**
 * L2/L3 Engine: Local LLM interpretation using MediaPipe GenAI.
 * Model path resolved dynamically from models.json via ModelDownloader.
 */
class LocalLlmInterpreter(
    private val context: Context,
    private val settingsRepo: SettingsRepository,
    private val modelDownloader: ModelDownloader
) : AssistantEngine {

    private val TAG = Strings.Tags.LOCAL_LLM_INTERPRETER
    private var llmInference: LlmInference? = null
    private var baseSession: LlmInferenceSession? = null
    private var cachedSystemPromptHash: String? = null
    private var loadedModelId: String? = null
    private var loadedEngineKey: String? = null
    @Volatile private var isProcessing = false

    /** Serializes every call that touches [llmInference]/[baseSession] — this class is a single
     *  process-wide singleton (see AppContainer), and [setupLlm]'s `if (llmInference != null) return`
     *  is a check-then-act with no synchronization of its own. Without this, a burst of concurrent
     *  callers (confirmed on-device: Expenses' "Force-check notifications now" forwarding several
     *  matched notifications at once) each see `llmInference == null` and each call the native,
     *  memory-heavy `LlmInference.createFromOptions(...)` concurrently — N full copies of the model
     *  loading into RAM at once, which crashed the process outright in the observed repro (visible as
     *  a silent PID change plus the pre-existing "stale XNNPACK cache" cleanup path firing, evidence
     *  of a prior native crash) and meant every one of those N requests vanished with zero reply, since
     *  nothing ever reached `LlmHookWorker`'s `catch` block to send one. */
    private val mutex = Mutex()

    private fun setupLlm() {
        val snapshot = settingsRepo.getSettingsSnapshot()
        val modelId = snapshot.activeIntentModelId ?: return
        val engineKey = snapshot.aiProcessor

        // If model or engine changed, tear down everything and reload
        if (llmInference != null && (loadedModelId != modelId || loadedEngineKey != engineKey)) {
            Logger.log("LLM model changed ($loadedModelId -> $modelId), reloading", TAG)
            try { baseSession?.close() } catch (_: Exception) {}
            try { llmInference?.close() } catch (_: Exception) {}
            llmInference = null
            baseSession = null
            cachedSystemPromptHash = null
            loadedModelId = null
            loadedEngineKey = null
        }

        if (llmInference != null) return

        val modelFile = modelDownloader.resolveLocalFile(modelId, engineKey)
        if (modelFile == null || !modelFile.exists()) {
            Logger.log("LLM model not found for $modelId ($engineKey). Make sure it is downloaded.", TAG)
            return
        }

        // Clear potentially corrupted XNNPACK cache files from previous native crashes
        val cacheDir = context.cacheDir
        cacheDir.listFiles { f -> f.name.contains("xnnpack_cache") }?.forEach { f ->
            Logger.log("Removing stale XNNPACK cache: ${f.name}", TAG)
            f.delete()
        }

        val modelPath = modelFile.absolutePath
        Logger.log("Loading LLM model: $modelPath", TAG)

        val options = LlmInference.LlmInferenceOptions.builder()
            .setModelPath(modelPath)
            // Total context budget (input + output). Must exceed the NLU system prompt
            // (~1100+ tokens) plus user input and the generated response. 1024 was too
            // small — the prompt alone overflowed it, so every generation failed with
            // OUT_OF_RANGE ("Input is too long ... was not less than maxTokens(1024)").
            .setMaxTokens(2048)
            .build()

        val instance = LlmInference.createFromOptions(context, options)
        if (instance == null) {
            Logger.log("LlmInference.createFromOptions returned null — model failed to load", TAG)
            return
        }
        llmInference = instance
        loadedModelId = modelId
        loadedEngineKey = engineKey
    }

    override suspend fun processCommand(spokenText: String, modelFilterLang: String?): NluIntent? =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                withTimeoutOrNull(LOCAL_LLM_TIMEOUT_MS) { doProcessCommand(spokenText, modelFilterLang) }
            }
        }

    private fun doProcessCommand(spokenText: String, modelFilterLang: String?): NluIntent? {
        isProcessing = true
        try {
            setupLlm()
            val engine = llmInference ?: return null

            val settings = settingsRepo.getSettingsSnapshot()
            val systemPrompt = PromptProvider.getNluSystemPrompt(settings, modelFilterLang, settingsRepo)
            val userInput = PromptProvider.formatUserInput(spokenText)
            val promptHash = sha256(systemPrompt)

            // Invalidate cached session if system prompt changed (apps, language, defaults, etc.)
            if (cachedSystemPromptHash != promptHash) {
                if (baseSession != null) {
                    Logger.log("System prompt changed — rebuilding cached session", TAG)
                    try { baseSession?.close() } catch (_: Exception) {}
                    baseSession = null
                }
                cachedSystemPromptHash = promptHash
            }

            // Create base session with system prompt pre-loaded (cached across calls)
            if (baseSession == null) {
                try {
                    val sessionOptions = LlmInferenceSession.LlmInferenceSessionOptions.builder()
                        .setTopK(40)
                        .setTemperature(0.1f)
                        .build()
                    val session = LlmInferenceSession.createFromOptions(engine, sessionOptions)
                    session.addQueryChunk(systemPrompt)
                    baseSession = session
                    Logger.log("Base session created with cached system prompt (${systemPrompt.length} chars)", TAG)
                } catch (e: Exception) {
                    Logger.log("Failed to create base session: ${e.message}", TAG)
                }
            }

            val session = baseSession
            if (session == null) {
                // Fallback: no session, use direct generateResponse
                return try {
                    val fullPrompt = "$systemPrompt\n$userInput"
                    val response = engine.generateResponse(fullPrompt)
                    NluIntentParser.parse(response)
                } catch (e: Exception) {
                    Logger.log("LLM generation failed (fallback): ${e.message}", TAG)
                    null
                }
            }

            // Clone the base session (reuses KV cache for system prompt), add user input, generate
            var querySession: LlmInferenceSession? = null
            return try {
                querySession = session.cloneSession()
                querySession.addQueryChunk(userInput)
                val response = querySession.generateResponse()
                Logger.log("LLM response: $response", TAG)
                NluIntentParser.parse(response)
            } catch (e: Exception) {
                Logger.log("LLM generation failed: ${e.message}", TAG)
                // If clone failed, the base session might be corrupted — invalidate it
                try { baseSession?.close() } catch (_: Exception) {}
                baseSession = null
                cachedSystemPromptHash = null
                null
            } finally {
                try { querySession?.close() } catch (_: Exception) {}
            }
        } finally {
            isProcessing = false
        }
    }

    override suspend fun rawPrompt(promptText: String, imageUri: String?): String? =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                withTimeoutOrNull(LOCAL_LLM_TIMEOUT_MS) { doRawPrompt(promptText) }
            }
        }

    private fun doRawPrompt(promptText: String): String? {
        // MediaPipe GenAI on-device models aren't multimodal-capable today — imageUri is always null
        // here (RemoteModelRegistry.isMultimodal never reports true for a local engine unless it
        // declares "multimodal" in models.json, which none currently do), silently ignored otherwise.
        isProcessing = true
        try {
            setupLlm()
            val engine = llmInference ?: return null
            // Deliberately does NOT touch baseSession/cachedSystemPromptHash — those are primed with
            // the NLU system prompt for processCommand()'s per-utterance path. A raw-prompt call has
            // a different "system" framing per task and is infrequent (manual button / scheduled
            // job), so it uses the same no-session fallback path processCommand() already has for
            // when no session is available, rather than corrupting or reusing the NLU session cache.
            return try {
                engine.generateResponse(promptText)
            } catch (e: Exception) {
                Logger.log("LLM rawPrompt generation failed: ${e.message}", TAG)
                null
            }
        } finally {
            isProcessing = false
        }
    }

    /**
     * Releases the LLM engine (~500MB+) on system memory pressure while keeping
     * the interpreter alive. setupLlm() will transparently reload it on the next
     * processCommand() call. Skipped if a command is currently being processed.
     */
    override fun releaseForMemoryPressure() {
        if (isProcessing) {
            Logger.log("Skipping LLM release — actively processing", TAG)
            return
        }
        if (llmInference == null) return
        Logger.log("Releasing LLM engine for memory pressure", TAG)
        try { baseSession?.close() } catch (_: Exception) {}
        try { llmInference?.close() } catch (_: Exception) {}
        baseSession = null
        llmInference = null
        cachedSystemPromptHash = null
        loadedModelId = null
        loadedEngineKey = null
    }

    private fun sha256(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(text.toByteArray())
        return hash.joinToString("") { "%02x".format(it) }
    }
}
