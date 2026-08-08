package com.voxapps.commander.domain.intent.interpreter

import com.voxapps.commander.domain.engine.EngineSelection
import com.voxapps.commander.domain.engine.MemoryManagedComponent
import com.voxapps.commander.domain.intent.model.NluIntent

interface AssistantEngine : MemoryManagedComponent {
    suspend fun processCommand(spokenText: String, modelFilterLang: String? = null): NluIntent?

    /**
     * Generic raw-prompt passthrough for satellite LLM-hook requests (no NLU system prompt, no
     * NluIntent parsing) — sends [promptText] to this engine and returns its raw text output.
     * Returns null if this engine can't currently serve requests (missing key, cloud disabled,
     * model not downloaded, or simply unsupported).
     *
     * [imageUri] optionally attaches an image alongside [promptText] — only engines that report
     * `"multimodal"` support (see `RemoteModelRegistry.isMultimodal`) do anything with it; every other
     * implementation ignores it and behaves exactly as it does for a plain text-only call.
     */
    suspend fun rawPrompt(promptText: String, imageUri: String? = null): String?
}

/**
 * An engine that runs whichever model it is handed, rather than whichever one the user has made
 * active.
 *
 * Only the on-device engines can answer to this — a cloud engine's model belongs to the service —
 * which is why it is a separate interface and not a parameter on [AssistantEngine]: an argument
 * every cloud implementation is obliged to accept and then ignore is not a contract, it is a
 * convention waiting to be broken. Asking `is SelectableModelEngine` asks about a capability; the
 * type tests this codebase has been removing asked about an implementation.
 */
interface SelectableModelEngine : AssistantEngine {
    suspend fun processCommand(
        spokenText: String,
        modelFilterLang: String?,
        selection: EngineSelection
    ): NluIntent?
}