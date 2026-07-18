package com.voxapps.commander.domain.intent

import com.voxapps.commander.data.preferences.SettingsRepository
import com.voxapps.commander.data.remote.RemoteModelRegistry
import com.voxapps.commander.domain.intent.interpreter.AssistantEngine
import com.voxapps.commander.utils.Strings

/**
 * Routes a satellite's generic LLM-hook raw prompt to whichever engine is currently configured as
 * the user's primary AI processor (`aiProcessor` setting) — the same selection [IntentDecisionMap]
 * uses for its L2 step, but calling [AssistantEngine.rawPrompt] instead of `processCommand`, with no
 * L1/L3 fallback cascade (this hook always targets a single, currently-selected engine).
 */
sealed class RawPromptOutcome {
    data class Success(val rawText: String) : RawPromptOutcome()
    data class Error(val reason: String) : RawPromptOutcome()
}

class LlmHookEngineSelector(
    private val openAiEngine: AssistantEngine,
    private val geminiCloudEngine: AssistantEngine,
    private val localLlmEngine: AssistantEngine,
    private val geminiNanoEngine: AssistantEngine,
    private val settingsRepo: SettingsRepository
) {
    /**
     * [imageUri] is forwarded as-is to whichever engine is selected — only engines that report
     * multimodal support (see [RemoteModelRegistry.isMultimodal]) do anything with it; the caller is
     * expected to have already checked capability before setting this (e.g. via the
     * [com.voxapps.ipc.VoxCapabilityClient] query), not rely on this call to gate it.
     */
    suspend fun run(promptText: String, imageUri: String? = null): RawPromptOutcome {
        val snapshot = settingsRepo.getSettingsSnapshot()
        val processor = snapshot.aiProcessor
        val cloudOk = snapshot.cloudIntelligenceEnabled

        return when (processor) {
            Strings.AiProcessors.OPENAI -> {
                if (!cloudOk) RawPromptOutcome.Error("Cloud intelligence disabled")
                else openAiEngine.rawPrompt(promptText, imageUri)?.let { RawPromptOutcome.Success(it) }
                    ?: RawPromptOutcome.Error("OpenAI request failed (check API key)")
            }
            Strings.AiProcessors.GEMINI_CLOUD -> {
                if (!cloudOk) RawPromptOutcome.Error("Cloud intelligence disabled")
                else geminiCloudEngine.rawPrompt(promptText, imageUri)?.let { RawPromptOutcome.Success(it) }
                    ?: RawPromptOutcome.Error("Gemini Cloud request failed (check API key)")
            }
            Strings.AiProcessors.GEMINI_NATIVE -> {
                // Special-cased rather than relying on GeminiNanoInterpreter.rawPrompt()'s generic
                // null — on-device inference isn't implemented yet, this gives the caller a clearer
                // error than a bare "no engine" message.
                RawPromptOutcome.Error("Gemini Nano on-device is not yet supported for generic LLM requests")
            }
            else -> {
                if (RemoteModelRegistry.isLlmEngine(processor)) {
                    localLlmEngine.rawPrompt(promptText, imageUri)?.let { RawPromptOutcome.Success(it) }
                        ?: RawPromptOutcome.Error("Local model unavailable (not downloaded or failed to load)")
                } else {
                    RawPromptOutcome.Error("No LLM engine configured")
                }
            }
        }
    }
}
