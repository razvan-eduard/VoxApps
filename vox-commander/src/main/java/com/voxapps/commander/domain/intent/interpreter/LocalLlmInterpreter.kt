package com.voxapps.commander.domain.intent.interpreter

import android.content.Context
import com.voxapps.commander.utils.Logger
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import com.voxapps.commander.domain.intent.model.NluIntent
import com.voxapps.commander.data.preferences.SettingsRepository
import com.voxapps.commander.data.remote.ModelDownloader
import com.voxapps.commander.data.remote.RemoteModelRegistry
import com.voxapps.commander.utils.Strings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

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

    override suspend fun processCommand(spokenText: String, modelFilterLang: String?): NluIntent? = withContext(Dispatchers.IO) {
        isProcessing = true
        try {
        setupLlm()
        val engine = llmInference ?: return@withContext null

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
            try {
                val fullPrompt = "$systemPrompt\n$userInput"
                val response = engine.generateResponse(fullPrompt)
                return@withContext NluIntentParser.parse(response)
            } catch (e: Exception) {
                Logger.log("LLM generation failed (fallback): ${e.message}", TAG)
                return@withContext null
            }
        }

        // Clone the base session (reuses KV cache for system prompt), add user input, generate
        var querySession: LlmInferenceSession? = null
        try {
            querySession = session.cloneSession()
            querySession.addQueryChunk(userInput)
            val response = querySession.generateResponse()
            Logger.log("LLM response: $response", TAG)
            return@withContext NluIntentParser.parse(response)
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
