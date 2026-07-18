package com.voxapps.commander.domain.intent.interpreter

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