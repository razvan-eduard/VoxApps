package com.voxapps.commander.domain.intent.interpreter

import android.content.Context
import com.voxapps.logging.Logger
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ResponseFormat
import com.google.ai.edge.litertlm.SamplerConfig
import com.voxapps.commander.data.local.dao.FastMapDao
import com.voxapps.commander.domain.intent.model.FastMapRule
import com.voxapps.commander.domain.intent.model.NluIntent
import com.voxapps.commander.data.preferences.SettingsRepository
import com.voxapps.commander.data.remote.ModelDownloader
import com.voxapps.commander.data.remote.RemoteModelRegistry
import com.voxapps.commander.domain.intent.taxonomy.IntentTaxonomy
import com.voxapps.commander.utils.Strings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Generous — first-ever load of a multi-GB on-device model can genuinely take a while — this is a
 *  safety net against a stuck/corrupt model wedging every request forever, not a normal-path budget.
 *  See [LocalLlmInterpreter.mutex]'s doc comment for why a serialized call can still need one. */
private const val LOCAL_LLM_TIMEOUT_MS = 90_000L

/**
 * Constrains generation to exactly this shape via LiteRT-LM's LLGuidance-backed constrained
 * decoding ([ResponseFormat.json]) — confirmed on-device: without this, the model (a) truncated
 * mid-object once the token budget ran out before a closing brace, and separately (b), once that
 * was fixed, kept generating past the first complete object into a hallucinated second, malformed
 * "DEFAULT" wrapper. Constrained decoding fixes both at the root instead of patching around them:
 * the grammar can't emit tokens outside this shape, and `additionalProperties: false` means it
 * can't wander into extra keys once the declared ones are satisfied. Only `domain`/`action` are
 * `required` — everything else stays defensively optional to match [NluIntentParser]'s existing
 * `getSafeString`/`getSafeStringList` fallbacks.
 *
 * [domains]/[actions] enum-constrain those two fields to the taxonomy's actual valid values —
 * confirmed on-device this matters, not just in theory: an unconstrained model emitted
 * `domain="weather"` for a weather query, which isn't a real domain at all (`search` is; `weather`
 * is only a *category* within it — see `SearchProviderRegistry`), so `SearchIntentHandler.canHandle`
 * (which only matches `domain == "search"`) never even saw it. Deliberately the *full* taxonomy
 * list here, not the per-utterance token-scoped subset [PromptProvider.relevantDomains] computes
 * for the apps section — narrowing the legal domain *values* to whatever happened to fit this
 * utterance's app-list budget would risk the opposite failure (forcing a wrong domain when the
 * right one was merely scoped out of the apps text, not actually irrelevant).
 */
private fun buildNluResponseSchema(domains: List<String>, actions: List<String>): String =
    JSONObject().apply {
        put(
            "properties",
            JSONObject().apply {
                put("action_verb", JSONObject().put("type", "string"))
                put("logical_subject", JSONObject().put("type", JSONArray(listOf("string", "null"))))
                put("modifiers", JSONObject().put("type", "array").put("items", JSONObject().put("type", "string")))
                put("context_words", JSONObject().put("type", "array").put("items", JSONObject().put("type", "string")))
                put("domain", JSONObject().put("type", "string").put("enum", JSONArray(domains)))
                put("action", JSONObject().put("type", "string").put("enum", JSONArray(actions)))
                put("targetApp", JSONObject().put("type", JSONArray(listOf("string", "null"))))
                put("category", JSONObject().put("type", JSONArray(listOf("string", "null"))))
                put("media_type", JSONObject().put("type", JSONArray(listOf("string", "null"))))
                put("confidence", JSONObject().put("type", "number"))
            }
        )
        put("type", "object")
        put("required", JSONArray(listOf("domain", "action")))
        put("additionalProperties", false)
    }.toString()

/**
 * L2/L3 Engine: Local LLM interpretation using LiteRT-LM.
 * Model path resolved dynamically from models.json via ModelDownloader.
 */
class LocalLlmInterpreter(
    private val context: Context,
    private val settingsRepo: SettingsRepository,
    private val modelDownloader: ModelDownloader,
    private val fastMapDao: FastMapDao
) : AssistantEngine {

    private val TAG = Strings.Tags.LOCAL_LLM_INTERPRETER
    private var engine: Engine? = null
    private var baseConversation: Conversation? = null
    private var cachedSystemPromptHash: String? = null
    private var loadedModelId: String? = null
    private var loadedEngineKey: String? = null
    @Volatile private var isProcessing = false

    /** Serializes every call that touches [engine]/[baseConversation] — this class is a single
     *  process-wide singleton (see AppContainer), and [setupLlm]'s `if (engine != null) return`
     *  is a check-then-act with no synchronization of its own. Without this, a burst of concurrent
     *  callers (confirmed on-device: Expenses' "Force-check notifications now" forwarding several
     *  matched notifications at once) each see `engine == null` and each call the native,
     *  memory-heavy `Engine(...).initialize()` concurrently — N full copies of the model
     *  loading into RAM at once, which crashed the process outright in the observed repro (visible as
     *  a silent PID change plus the pre-existing "stale model cache" cleanup path firing, evidence
     *  of a prior native crash) and meant every one of those N requests vanished with zero reply, since
     *  nothing ever reached `LlmHookWorker`'s `catch` block to send one. */
    private val mutex = Mutex()

    private fun setupLlm() {
        val snapshot = settingsRepo.getSettingsSnapshot()
        val modelId = snapshot.activeIntentModelId ?: return
        val engineKey = snapshot.aiProcessor

        // If model or engine changed, tear down everything and reload
        if (engine != null && (loadedModelId != modelId || loadedEngineKey != engineKey)) {
            Logger.log("LLM model changed ($loadedModelId -> $modelId), reloading", TAG)
            try { baseConversation?.close() } catch (_: Exception) {}
            try { engine?.close() } catch (_: Exception) {}
            engine = null
            baseConversation = null
            cachedSystemPromptHash = null
            loadedModelId = null
            loadedEngineKey = null
        }

        if (engine != null) return

        val modelFile = modelDownloader.resolveLocalFile(modelId, engineKey)
        if (modelFile == null || !modelFile.exists()) {
            Logger.log("LLM model not found for $modelId ($engineKey). Make sure it is downloaded.", TAG)
            return
        }

        val modelPath = modelFile.absolutePath
        Logger.log("Loading LLM model: $modelPath", TAG)

        val engineConfig = EngineConfig(
            modelPath = modelPath,
            backend = Backend.CPU(),
            // Total context budget (input + output). Must exceed the NLU system prompt
            // (which alone has grown past 1900 tokens — confirmed on-device: a 7685-char prompt)
            // plus user input and the generated response. 1024 was too small (the prompt alone
            // overflowed it — OUT_OF_RANGE "was not less than maxTokens(1024)"); 2048 was *also*
            // too small — it left so little headroom for output that generation silently ran out
            // of budget mid-JSON, truncating the response right after a field value with no
            // closing brace (confirmed on-device: NluIntentParser's EOFException on a response
            // ending "confidence": 1.0 with no trailing `}`), which read as "no intent detected"
            // even though the model had already decided the right domain/action. Both bundled
            // models are exported with a 4096-token context (models.json's "ekv4096"/"4096 ctx"
            // labels), so this can safely go all the way to what they actually support.
            maxNumTokens = 4096,
            // Writable per-model cache dir (mirrors the old XNNPACK-cache-cleanup workaround's
            // intent: keep engine-internal cache files out of the way of stale-state issues by
            // giving each model its own directory instead of relying on modelPath's directory).
            cacheDir = File(context.cacheDir, "litertlm_cache").apply { mkdirs() }.absolutePath
        )

        val instance = try {
            Engine(engineConfig).apply { initialize() }
        } catch (e: Exception) {
            Logger.log("Engine.initialize() failed — model failed to load: ${e.message}", TAG)
            null
        }
        if (instance == null) return
        engine = instance
        loadedModelId = modelId
        loadedEngineKey = engineKey
    }

    /**
     * Warms the engine up front — model load, native `Engine.initialize()`, and the base
     * `Conversation` (system prompt prefill + one-time XNNPACK weight-cache compile) — so the
     * user's first real command doesn't pay that cost. Mirrors [com.voxapps.commander.domain.engine
     * .whisper.WhisperCppSttEngine.initialize]'s shape: a public suspend fun, safe to call
     * speculatively at app startup, returning whether the engine ended up ready.
     *
     * Cold-load cost on-device (confirmed): a first-ever load of a given model builds its XNNPACK
     * weight cache from scratch (one compile pass per prefill signature the model ships, e.g.
     * prefill_1280/prefill_512/…), which can take 15-25s; a subsequent load of the SAME model
     * reuses the cache and is fast. Preloading at startup pays that cost once, in the background,
     * instead of on the user's first spoken/typed command.
     */
    suspend fun preload(modelFilterLang: String? = null): Boolean =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                withTimeoutOrNull(LOCAL_LLM_TIMEOUT_MS) {
                    setupLlm()
                    val currentEngine = engine ?: return@withTimeoutOrNull false
                    // No spokenText yet (this runs before any command exists) — PromptProvider
                    // falls back to every domain in that case, so there's no FastMap rules to
                    // fetch for keyword-matching purposes; skip that DB read entirely.
                    ensureBaseConversation(currentEngine, modelFilterLang) != null
                }
            } ?: false
        }

    /** Builds (or rebuilds, if the system prompt changed) [baseConversation] against [engine] —
     *  shared by [doProcessCommand] and [preload] so both pay/skip the exact same warm-up cost. */
    private fun ensureBaseConversation(
        currentEngine: Engine,
        modelFilterLang: String?,
        spokenText: String = "",
        fastMapRules: List<FastMapRule> = emptyList()
    ): Conversation? {
        val settings = settingsRepo.getSettingsSnapshot()
        val systemPrompt = PromptProvider.getNluSystemPrompt(spokenText, settings, modelFilterLang, settingsRepo, fastMapRules)
        val promptHash = sha256(systemPrompt)

        // Invalidate cached conversation if system prompt changed (apps, language, defaults, etc.)
        if (cachedSystemPromptHash != promptHash) {
            if (baseConversation != null) {
                Logger.log("System prompt changed — rebuilding cached conversation", TAG)
                try { baseConversation?.close() } catch (_: Exception) {}
                baseConversation = null
            }
            cachedSystemPromptHash = promptHash
        }

        // Create base conversation with system prompt pre-loaded (cached across calls) — a
        // Conversation retains its own KV-cache across sendMessage() calls, replacing the old
        // MediaPipe "clone base session per query" pattern entirely.
        if (baseConversation == null) {
            try {
                val conversationConfig = ConversationConfig(
                    systemInstruction = Contents.of(systemPrompt),
                    samplerConfig = SamplerConfig(topK = 40, topP = 1.0, temperature = 0.1),
                    // Required for the responseFormat passed to sendMessage() below to take
                    // effect at all — confirmed on-device: omitting this throws "response_format
                    // cannot be used unless enableResponseFormat=True was passed to
                    // ConversationConfig" on every call, silently falling through to "no intent".
                    enableResponseFormat = true
                )
                baseConversation = currentEngine.createConversation(conversationConfig)
                Logger.log("Base conversation created with cached system prompt (${systemPrompt.length} chars)", TAG)
            } catch (e: Exception) {
                Logger.log("Failed to create base conversation: ${e.message}", TAG)
            }
        }
        return baseConversation
    }

    override suspend fun processCommand(spokenText: String, modelFilterLang: String?): NluIntent? =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                withTimeoutOrNull(LOCAL_LLM_TIMEOUT_MS) {
                    val activeRules = fastMapDao.getAllRulesOnce().filter { it.isActive }
                    doProcessCommand(spokenText, modelFilterLang, activeRules)
                }
            }
        }

    private fun doProcessCommand(spokenText: String, modelFilterLang: String?, fastMapRules: List<FastMapRule>): NluIntent? {
        isProcessing = true
        try {
            setupLlm()
            val currentEngine = engine ?: return null

            val userInput = PromptProvider.formatUserInput(spokenText)
            val conversation = ensureBaseConversation(currentEngine, modelFilterLang, spokenText, fastMapRules)
            if (conversation == null) {
                // Fallback: no cached conversation, use a one-shot conversation with the system
                // prompt inlined into the same message instead of as a persisted systemInstruction.
                return try {
                    val settings = settingsRepo.getSettingsSnapshot()
                    val systemPrompt = PromptProvider.getNluSystemPrompt(spokenText, settings, modelFilterLang, settingsRepo, fastMapRules)
                    val fullPrompt = "$systemPrompt\n$userInput"
                    val domains = (IntentTaxonomy.Domains.ALL + settings.customDomains).distinct()
                    val schema = buildNluResponseSchema(domains, IntentTaxonomy.Actions.ALL)
                    val response = currentEngine.createConversation(ConversationConfig(enableResponseFormat = true)).use {
                        it.sendMessage(fullPrompt, responseFormat = ResponseFormat.json(schema))
                    }
                    NluIntentParser.parse(response.toString())
                } catch (e: Exception) {
                    Logger.log("LLM generation failed (fallback): ${e.message}", TAG)
                    null
                }
            }

            return try {
                val settings = settingsRepo.getSettingsSnapshot()
                val domains = (IntentTaxonomy.Domains.ALL + settings.customDomains).distinct()
                val schema = buildNluResponseSchema(domains, IntentTaxonomy.Actions.ALL)
                val response = conversation.sendMessage(userInput, responseFormat = ResponseFormat.json(schema))
                Logger.log("LLM response: $response", TAG)
                NluIntentParser.parse(response.toString())
            } catch (e: Exception) {
                Logger.log("LLM generation failed: ${e.message}", TAG)
                null
            } finally {
                // Each processCommand() call is an independent, stateless intent-parse, not a
                // multi-turn chat — but Conversation's KV-cache only ever grows (LiteRT-LM 0.15.0
                // exposes no reset/clone/checkpoint API, unlike MediaPipe's old cheap
                // cloneSession()). Confirmed on-device: reusing one Conversation across unrelated
                // commands silently degrades — 1st call parses fine, 2nd returns truncated JSON,
                // 3rd returns nothing — because the ~2000-token system prompt plus just 1-2 turns
                // already approaches the model's context cap. So every call rebuilds fresh from the
                // cached systemInstruction rather than reuse across calls; this repays the system
                // prompt's prefill cost each time, but that's unavoidable without a rewind API, and
                // is far cheaper than the one-time XNNPACK weight-cache compile it does NOT repeat.
                try { baseConversation?.close() } catch (_: Exception) {}
                baseConversation = null
                cachedSystemPromptHash = null
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
        // LiteRT-LM on-device models aren't multimodal-capable here today — imageUri is always null
        // here (RemoteModelRegistry.isMultimodal never reports true for a local engine unless it
        // declares "multimodal" in models.json, which none currently do), silently ignored otherwise.
        isProcessing = true
        try {
            setupLlm()
            val currentEngine = engine ?: return null
            // Deliberately does NOT touch baseConversation/cachedSystemPromptHash — those are primed
            // with the NLU system prompt for processCommand()'s per-utterance path. A raw-prompt call
            // has a different "system" framing per task and is infrequent (manual button / scheduled
            // job), so it uses a fresh, one-shot conversation with no system instruction rather than
            // corrupting or reusing the NLU conversation cache.
            return try {
                currentEngine.createConversation().use { it.sendMessage(promptText).toString() }
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
        if (engine == null) return
        Logger.log("Releasing LLM engine for memory pressure", TAG)
        try { baseConversation?.close() } catch (_: Exception) {}
        try { engine?.close() } catch (_: Exception) {}
        baseConversation = null
        engine = null
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
