package com.voxapps.commander.domain.intent.interpreter

/**
 * An on-device LLM interpreter, of which there is now more than one.
 *
 * The suite ships two local backends with different characteristics, and every engine key the
 * schema marks `local_llm` names one of them through its `backend` field. This interface is what
 * lets the rest of the app hold "the on-device LLM" without naming a backend: [AiEngineResolver]
 * maps a key to the implementation whose [backendId] matches, and callers that used to reach for a
 * concrete class — the hook's failure text, the startup warm-up, the benchmark rows — ask here
 * instead.
 */
interface LocalLlmEngine : SelectableModelEngine {

    /** Matches an engine's `backend` field in models.json; the resolver dispatches on it. */
    val backendId: String

    /**
     * Why the last request produced nothing, in words a satellite app can show its user. Null when
     * the last request succeeded, or when nothing has run yet. Failures on this path are ordinary
     * (no model downloaded, engine busy, generation refused) and a bare "it failed" leaves the user
     * with nothing to act on.
     */
    val lastErrorReason: String?

    /**
     * Loads the selected model and pays its warm-up cost up front, so the user's first command does
     * not. Returns whether the engine ended up ready. Safe to call speculatively at startup, and
     * cheap when the engine is already warm.
     */
    suspend fun preload(modelFilterLang: String? = null): Boolean
}
