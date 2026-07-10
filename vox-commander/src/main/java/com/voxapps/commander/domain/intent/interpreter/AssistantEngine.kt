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
     */
    suspend fun rawPrompt(promptText: String): String?
}