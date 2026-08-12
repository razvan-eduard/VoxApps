package com.voxapps.commander.domain.intent.interpreter

import android.content.Context
import com.voxapps.commander.data.local.dao.FastMapDao
import com.voxapps.commander.data.preferences.SettingsRepository
import com.voxapps.commander.data.remote.LlamaEngineManager
import com.voxapps.commander.data.remote.ModelDownloader
import com.voxapps.commander.domain.engine.EngineSelection
import com.voxapps.commander.domain.intent.model.NluIntent
import com.voxapps.commander.domain.intent.taxonomy.IntentTaxonomy
import com.voxapps.commander.utils.NetworkMonitor
import com.voxapps.commander.utils.Strings
import com.voxapps.llamacpp.LibLlama
import com.voxapps.llamacpp.LlamaBridge
import com.voxapps.llamacpp.LlamaBridgeImpl
import com.voxapps.logging.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Generous — first-ever load of a multi-GB on-device model can genuinely take a while — this is a
 *  safety net against a stuck/corrupt model wedging every request forever, not a normal-path budget.
 *  See [LocalLlmInterpreter.mutex]'s doc comment for why a serialized call can still need one. */
private const val LOCAL_LLM_TIMEOUT_MS = 90_000L

/** Total context budget (input + output) — the NLU system prompt alone exceeds 1900 tokens, and
 *  every model in the lineup is served with at least a 4096-token context. */
private const val LLM_CONTEXT_TOKENS = 4096

/** Output budget per intent parse: a full NLU object decodes in ~60 tokens; the rest is headroom
 *  so an unusually long logical_subject cannot truncate mid-JSON. */
private const val NLU_MAX_TOKENS = 192

/**
 * GBNF primitives shared by every generated grammar — the JSON string/number/array machinery,
 * byte-for-byte what llama.cpp's own json_schema_to_grammar emits for this response shape, so the
 * hand-built coupling below composes with rules the sampler is known to handle.
 */
private val NLU_GRAMMAR_PRIMITIVES = listOf(
    """action-verb-kv ::= "\"action_verb\"" space ":" space string""",
    """category ::= string | null""",
    """category-kv ::= "\"category\"" space ":" space category""",
    """char ::= [^"\\\x7F\x00-\x1F] | [\\] (["\\bfnrt] | "u" [0-9a-fA-F]{4})""",
    """confidence-kv ::= "\"confidence\"" space ":" space number""",
    """context-words ::= "[" space (string ("," space string)*)? space "]"""",
    """context-words-kv ::= "\"context_words\"" space ":" space context-words""",
    """decimal-part ::= [0-9]{1,16}""",
    """integral-part ::= [0] | [1-9] [0-9]{0,15}""",
    """logical-subject ::= string | null""",
    """logical-subject-kv ::= "\"logical_subject\"" space ":" space logical-subject""",
    """media-type ::= string | null""",
    """media-type-kv ::= "\"media_type\"" space ":" space media-type""",
    """modifiers ::= "[" space (string ("," space string)*)? space "]"""",
    """modifiers-kv ::= "\"modifiers\"" space ":" space modifiers""",
    """null ::= "null"""",
    """number ::= ("-"? integral-part) ("." decimal-part)? ([eE] [-+]? integral-part)?""",
    """space ::= | " " | "\n"{1,2} [ \t]{0,20}""",
    """string ::= "\"" char* "\""""",
    """targetApp ::= string | null""",
    """targetApp-kv ::= "\"targetApp\"" space ":" space targetApp"""
).joinToString("\n")

/**
 * Constrains generation to the NLU response shape via grammar-constrained sampling — the same
 * fixes LiteRT's constrained decoding bought (no truncation past the token budget mid-object, no
 * hallucinated second object), enforced at the sampler so tokens outside the shape cannot be
 * emitted at all.
 *
 * The `action`/`domain` pair is emitted **action first, with the domain enum constrained to the
 * taxonomy's domains for the chosen action** (actions_by_domain, reversed). Measured on-device
 * (Qwen3-0.6B Q8_0, 28 labeled commands): a flat schema where the model commits to `domain` before
 * `action`, with nothing coupling them, parsed 43% of commands to the right domain+action — the
 * model repeatedly chose the right action under a wrong domain (`flashlight_on` under `maps`).
 * Action-first coupling lifted that to 64% with the same model and prompt, because the action
 * choice is the accurate one and the domain then follows from the taxonomy instead of from the
 * model's weaker prior. Key order in JSON carries no meaning to [NluIntentParser] (Gson object).
 *
 * `launch` alone keeps a free domain choice — the taxonomy gives custom/unknown domains `launch`,
 * so it is the one action every domain can carry. Deliberately NOT added to every domain's branch:
 * a universal escape hatch lets the model dodge the discrimination this coupling exists to force
 * (measured: a domain-first coupling with `launch` everywhere answered `maps`+`launch` for
 * "turn on the flashlight").
 */
internal fun buildNluGrammar(domains: List<String>): String {
    fun lit(s: String) = "\"\\\"" + s + "\\\"\""

    val domainsByAction = LinkedHashMap<String, MutableList<String>>()
    for (domain in domains) {
        for (action in IntentTaxonomy.getActionsForDomain(domain)) {
            domainsByAction.getOrPut(action) { mutableListOf() }.let { if (domain !in it) it.add(domain) }
        }
    }
    domainsByAction["launch"] = domains.toMutableList()

    val pairAlts = domainsByAction.entries.joinToString(" | ") { (action, doms) ->
        val domAlt = doms.joinToString(" | ") { lit(it) }
        "${lit("action")} space \":\" space ${lit(action)} space \",\" space ${lit("domain")} space \":\" space ($domAlt)"
    }

    val root = "root ::= \"{\" space " +
        "(action-verb-kv \",\" space)? " +
        "(logical-subject-kv \",\" space)? " +
        "(modifiers-kv \",\" space)? " +
        "(context-words-kv \",\" space)? " +
        "action-domain " +
        "(\",\" space targetApp-kv)? " +
        "(\",\" space category-kv)? " +
        "(\",\" space media-type-kv)? " +
        "(\",\" space confidence-kv)? " +
        "space \"}\""

    return NLU_GRAMMAR_PRIMITIVES + "\naction-domain ::= " + pairAlts + "\n" + root + "\n"
}

/**
 * L2/L3 Engine: Local LLM interpretation using llama.cpp (libllama.so via [LlamaBridge]).
 * Model path resolved dynamically from models.json via ModelDownloader.
 */
class LocalLlmInterpreter(
    private val context: Context,
    private val settingsRepo: SettingsRepository,
    private val modelDownloader: ModelDownloader,
    private val fastMapDao: FastMapDao,
    private val bridge: LlamaBridge = LlamaBridgeImpl,
    private val libManager: LlamaEngineManager = LlamaEngineManager(context)
) : SelectableModelEngine {

    private val TAG = Strings.Tags.LOCAL_LLM_INTERPRETER
    private var handle: Long = 0
    private var loadedModelId: String? = null
    private var loadedEngineKey: String? = null
    private var cachedGrammar: String? = null
    private var cachedGrammarKey: String? = null
    @Volatile private var isProcessing = false

    /** Serializes every call that touches [handle] — this class is a single process-wide singleton
     *  (see AppContainer), and [setupLlm]'s `if (handle != 0L) return` is a check-then-act with no
     *  synchronization of its own. Without this, a burst of concurrent callers (confirmed
     *  on-device: Expenses' "Force-check notifications now" forwarding several matched
     *  notifications at once) each see no loaded model and each start a native, memory-heavy model
     *  load concurrently — N full copies of the model loading into RAM at once, which crashed the
     *  process outright in the observed repro, and meant every one of those N requests vanished
     *  with zero reply, since nothing ever reached `LlmHookWorker`'s `catch` block to send one.
     *  The [bridge] is documented not thread-safe for the same reason: this Mutex is its sole
     *  serializer ([LlamaBridge.cancel] excepted, by design). */
    private val mutex = Mutex()

    /**
     * The user's *active* intent selection — what a caller means when it does not say which model
     * it wants. Null when no model is selected at all.
     */
    private fun activeSelection(): EngineSelection? {
        val snapshot = settingsRepo.getSettingsSnapshot()
        val modelId = snapshot.activeIntentModelId ?: return null
        return EngineSelection(snapshot.aiProcessor, modelId)
    }

    /**
     * Loads [selection]'s model, replacing whatever is loaded.
     *
     * Takes the selection as a parameter rather than reading `activeIntentModelId`/`aiProcessor`
     * itself. That read is why a configured intent fallback produced nothing: this class is one
     * process-wide instance, so the fallback stage reached the same interpreter and it loaded the
     * *primary's* model — re-running, on the model that had just failed, the inference that had
     * just failed.
     */
    private suspend fun setupLlm(selection: EngineSelection) {
        val modelId = selection.modelId ?: return
        val engineKey = selection.engineKey

        // If model or engine changed, tear down everything and reload
        if (handle != 0L && (loadedModelId != modelId || loadedEngineKey != engineKey)) {
            Logger.log("LLM model changed ($loadedModelId -> $modelId), reloading", TAG)
            try { bridge.freeModel(handle) } catch (_: Exception) {}
            handle = 0
            loadedModelId = null
            loadedEngineKey = null
        }

        if (handle != 0L) return

        // The runtime first: same discipline as WhisperCppSttEngine.onLoad — staleness is settled
        // on the load path because it runs every time the library is consumed, nothing is fetched
        // over a metered connection, and bytes that exist but cannot load get one repair attempt.
        if (libManager.needsRefresh()) {
            if (NetworkMonitor.isMetered) {
                Logger.log("llama library needs refreshing, but the connection is metered — loading what is present", TAG)
            } else if (!libManager.downloadLibs()) {
                Logger.log("llama library refresh failed — loading what is present", TAG)
            }
        }
        if (!LibLlama.load(libManager.libDir)) {
            val repaired = !NetworkMonitor.isMetered &&
                libManager.repairLibs() &&
                LibLlama.load(libManager.libDir)
            if (!repaired) {
                Logger.log("llama native library failed to load", TAG)
                return
            }
        }

        // An imported gguf is selected under its `custom:` id and resolved from the import
        // store, exactly like the voice engines do; a registry id resolves from the download dir.
        val modelFile = if (com.voxapps.commander.domain.model.ImportedModelId.isImported(modelId)) {
            com.voxapps.commander.domain.engine.EngineSpecs.importedModel(
                settingsRepo, engineKey, langCode = null, importId = modelId
            )
        } else {
            modelDownloader.resolveLocalFile(modelId, engineKey)
        }
        if (modelFile == null || !modelFile.exists()) {
            Logger.log("LLM model not found for $modelId ($engineKey). Make sure it is downloaded.", TAG)
            return
        }

        Logger.log("Loading LLM model: ${modelFile.absolutePath}", TAG)
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
        handle = try {
            bridge.loadModel(modelFile.absolutePath, LLM_CONTEXT_TOKENS, threads)
        } catch (e: Exception) {
            Logger.log("Model failed to load: ${e.message}", TAG)
            0
        }
        if (handle != 0L) {
            loadedModelId = modelId
            loadedEngineKey = engineKey
        }
    }

    /** The NLU grammar for the current taxonomy, rebuilt only when the domain set changes (custom
     *  domains arrive with satellite installs). Fails closed: a grammar that cannot be built is an
     *  error return from [doProcessCommand], never a silent fall-through to unconstrained output. */
    private fun grammarFor(domains: List<String>): String? {
        val key = domains.joinToString(",")
        if (cachedGrammarKey == key && cachedGrammar != null) return cachedGrammar
        return try {
            buildNluGrammar(domains).also {
                cachedGrammar = it
                cachedGrammarKey = key
            }
        } catch (e: Exception) {
            Logger.log("NLU grammar build failed: ${e.message}", TAG)
            null
        }
    }

    /**
     * Warms the engine up front — runtime load, model mmap, and one dummy decode that leaves the
     * system prompt's KV prefix resident — so the user's first real command doesn't pay the full
     * prefill (~25s of prompt evaluation for the ~1900-token NLU prompt on a mid-range phone,
     * measured). The bridge reuses the longest common prompt prefix across calls, so the prefix
     * this leaves behind is exactly what the first command's prompt then starts from.
     */
    suspend fun preload(modelFilterLang: String? = null): Boolean =
        withContext(Dispatchers.IO) {
            val selection = activeSelection() ?: return@withContext false
            mutex.withLock {
                withTimeoutOrNull(LOCAL_LLM_TIMEOUT_MS) {
                    setupLlm(selection)
                    if (handle == 0L) return@withTimeoutOrNull false
                    val settings = settingsRepo.getSettingsSnapshot()
                    val systemPrompt = PromptProvider.getNluSystemPrompt(
                        "", settings, modelFilterLang, settingsRepo, emptyList(), selection.engineKey
                    )
                    try {
                        completeCancellable(systemPrompt, PromptProvider.formatUserInput(""), "", maxTokens = 1)
                        true
                    } catch (e: Exception) {
                        Logger.log("Preload prefill failed: ${e.message}", TAG)
                        false
                    }
                }
            } ?: false
        }

    /** Runs the user's active intent model — the [AssistantEngine] contract, which says nothing
     *  about which model, so it means "the selected one". */
    override suspend fun processCommand(spokenText: String, modelFilterLang: String?): NluIntent? {
        val selection = activeSelection() ?: return null
        return processCommand(spokenText, modelFilterLang, selection)
    }

    /**
     * Runs [selection]'s model, whether or not it is the active one.
     *
     * A selection that differs from what is loaded costs a full model swap — teardown and reload.
     * That is the price of a fallback to a *different* on-device model and it is paid only when
     * the levels above have already failed.
     */
    override suspend fun processCommand(
        spokenText: String,
        modelFilterLang: String?,
        selection: EngineSelection
    ): NluIntent? =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                withTimeoutOrNull(LOCAL_LLM_TIMEOUT_MS) {
                    doProcessCommand(spokenText, modelFilterLang, selection)
                }
            }
        }

    private suspend fun doProcessCommand(
        spokenText: String,
        modelFilterLang: String?,
        selection: EngineSelection
    ): NluIntent? {
        isProcessing = true
        try {
            setupLlm(selection)
            if (handle == 0L) return null

            val settings = settingsRepo.getSettingsSnapshot()
            // The system prompt is deliberately NOT scoped to the utterance. PromptProvider's
            // per-utterance domain/app scoping saves prompt tokens, which was the right trade when
            // every call re-prefilled the whole prompt (the LiteRT engine had no rewind). Under
            // the bridge's longest-common-prefix KV reuse the trade inverts: a prompt that varies
            // with the spoken text diverges early and repays most of the prefill per command,
            // while a stable prompt is prefilled once (preload) and every command pays only its
            // own "Input:" tail. The prompt still changes — and the cache rebuilds itself from
            // the divergence point — when its real inputs change: installed apps, custom domains,
            // search providers, the language hint, or the schema-served template.
            val systemPrompt = PromptProvider.getNluSystemPrompt(
                "", settings, modelFilterLang, settingsRepo, emptyList(), selection.engineKey
            )
            val userInput = PromptProvider.formatUserInput(spokenText)
            val domains = (IntentTaxonomy.Domains.ALL + settings.customDomains).distinct()
            val grammar = grammarFor(domains) ?: return null

            return try {
                val response = completeCancellable(systemPrompt, userInput, grammar, NLU_MAX_TOKENS)
                Logger.log("LLM response: $response", TAG)
                response?.let { NluIntentParser.parse(it) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.log("LLM generation failed: ${e.message}", TAG)
                null
            }
            // No per-call teardown: the bridge trims the context back to the shared prompt prefix
            // on the next call (longest-common-prefix reuse), which is what replaced LiteRT-LM's
            // close-and-rebuild workaround — that engine exposed no way to rewind a conversation,
            // so every call rebuilt one and repaid the system prompt's prefill.
        } finally {
            isProcessing = false
        }
    }

    /**
     * [LlamaBridge.complete] with cancellation wired through: the native call blocks this (IO)
     * thread, and cancelling the coroutine — the caller's scope dying, or [withTimeoutOrNull]'s
     * 90s backstop — flips the native abort flag so the call returns within ~a token instead of
     * holding the [mutex] for the rest of the decode.
     */
    private suspend fun completeCancellable(
        systemPrompt: String,
        userText: String,
        grammar: String,
        maxTokens: Int,
        temperature: Float = 0.1f
    ): String? = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { bridge.cancel(handle) }
        val result = runCatching {
            bridge.complete(handle, systemPrompt, userText, grammar, maxTokens, temperature)
        }
        if (cont.isActive) {
            cont.resumeWith(result)
        }
    }

    override suspend fun rawPrompt(promptText: String, imageUri: String?): String? =
        withContext(Dispatchers.IO) {
            val selection = activeSelection() ?: return@withContext null
            mutex.withLock {
                withTimeoutOrNull(LOCAL_LLM_TIMEOUT_MS) { doRawPrompt(promptText, selection) }
            }
        }

    private suspend fun doRawPrompt(promptText: String, selection: EngineSelection): String? {
        // On-device models aren't multimodal-capable here today — imageUri is always null here
        // (RemoteModelRegistry.isMultimodal never reports true for a local engine unless it
        // declares "multimodal" in models.json, which none currently do), silently ignored otherwise.
        isProcessing = true
        try {
            setupLlm(selection)
            if (handle == 0L) return null
            // No system prompt and no grammar: a raw-prompt call carries its own framing per task
            // (satellite LLM hook), and its output is free text the caller post-processes.
            return try {
                completeCancellable("", promptText, "", maxTokens = 512, temperature = 0.2f)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.log("LLM rawPrompt generation failed: ${e.message}", TAG)
                null
            }
        } finally {
            isProcessing = false
        }
    }

    /**
     * Releases the LLM model and context (~model-size of RAM) on system memory pressure while
     * keeping the interpreter alive. setupLlm() will transparently reload on the next
     * processCommand() call — the model is mmap'd, so a reload is dominated by page-in, not a
     * from-scratch parse. Skipped if a command is currently being processed.
     */
    override fun releaseForMemoryPressure() {
        if (isProcessing) {
            Logger.log("Skipping LLM release — actively processing", TAG)
            return
        }
        if (handle == 0L) return
        Logger.log("Releasing LLM engine for memory pressure", TAG)
        try { bridge.freeModel(handle) } catch (_: Exception) {}
        handle = 0
        loadedModelId = null
        loadedEngineKey = null
    }
}
