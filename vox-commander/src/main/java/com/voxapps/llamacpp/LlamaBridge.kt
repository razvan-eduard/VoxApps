package com.voxapps.llamacpp

/**
 * The native llama.cpp surface, one JVM round-trip per operation. An interface so the interpreter
 * can be unit-tested against a fake without native libraries on the test JVM.
 *
 * NOT thread-safe: the caller serializes every call touching a handle (LocalLlmInterpreter's
 * Mutex is the sole owner today). [cancel] is the one exception — it only flips an atomic and is
 * safe to call from any thread while [complete] runs.
 */
interface LlamaBridge {

    /** Loads the model and creates its context. Returns an opaque handle; throws on failure. */
    fun loadModel(path: String, nCtx: Int, nThreads: Int): Long

    /** Frees the context and model behind [handle]. The handle is dead afterwards. */
    fun freeModel(handle: Long)

    /**
     * Converts a JSON-Schema string to GBNF via llama.cpp's own converter. Throws on a schema the
     * converter cannot express — never returns an empty grammar, so a silent fall-through to
     * unconstrained sampling is not representable.
     */
    fun jsonSchemaToGrammar(schemaJson: String): String

    /**
     * Runs one chat-templated completion (system + user), sampling under [grammarGbnf] when
     * non-empty. Returns the generated text, or null when [cancel] interrupted the call — the
     * caller maps null to its own cancellation type. Throws on real failures.
     *
     * The system prompt's KV prefix is reused across calls (longest-common-prefix), per [slot]:
     * a repeated system prompt costs one decode of the user tail rather than a full re-prefill.
     * Slots exist because the two kinds of caller share no prompt prefix — under one sequence
     * each evicted the other's resident prompt on every alternation.
     */
    fun complete(
        handle: Long,
        systemPrompt: String,
        userText: String,
        grammarGbnf: String,
        maxTokens: Int,
        temperature: Float,
        slot: Int = SLOT_NLU
    ): String?

    /** Interrupts a running [complete] from any thread; observed both per-token and mid-graph. */
    fun cancel(handle: Long)

    /** Drops every token resident in the context's memory. */
    fun clearMemory(handle: Long)

    /** Tokens currently resident in the context — the testability seam for KV-clear assertions. */
    fun contextTokenCount(handle: Long): Int

    companion object {
        /** KV slot for grammar-constrained NLU completions — the stable system-prompt prefix. */
        const val SLOT_NLU = 0
        /** KV slot for free-text raw-prompt completions (satellite LLM hooks). */
        const val SLOT_RAW = 1
    }
}

/** The real JNI binding. [LibLlama.load] must have succeeded before any call. */
object LlamaBridgeImpl : LlamaBridge {

    override fun loadModel(path: String, nCtx: Int, nThreads: Int): Long =
        nativeLoadModel(path, nCtx, nThreads)

    override fun freeModel(handle: Long) = nativeFreeModel(handle)

    override fun jsonSchemaToGrammar(schemaJson: String): String {
        val grammar = nativeJsonSchemaToGrammar(schemaJson)
        check(grammar.isNotBlank()) { "schema converted to a blank grammar" }
        return grammar
    }

    override fun complete(
        handle: Long,
        systemPrompt: String,
        userText: String,
        grammarGbnf: String,
        maxTokens: Int,
        temperature: Float,
        slot: Int
    ): String? = nativeComplete(handle, systemPrompt, userText, grammarGbnf, maxTokens, temperature, slot)

    override fun cancel(handle: Long) = nativeCancel(handle)

    override fun clearMemory(handle: Long) = nativeClearMemory(handle)

    override fun contextTokenCount(handle: Long): Int = nativeContextTokenCount(handle)

    private external fun nativeLoadModel(path: String, nCtx: Int, nThreads: Int): Long
    private external fun nativeFreeModel(handle: Long)
    private external fun nativeJsonSchemaToGrammar(schemaJson: String): String
    private external fun nativeComplete(
        handle: Long,
        systemPrompt: String,
        userText: String,
        grammarGbnf: String,
        maxTokens: Int,
        temperature: Float,
        slot: Int
    ): String?
    private external fun nativeCancel(handle: Long)
    private external fun nativeClearMemory(handle: Long)
    private external fun nativeContextTokenCount(handle: Long): Int
}
