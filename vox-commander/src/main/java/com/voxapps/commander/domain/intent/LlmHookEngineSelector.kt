package com.voxapps.commander.domain.intent

import com.voxapps.commander.data.preferences.SettingsRepository
import com.voxapps.commander.data.remote.RemoteModelRegistry
import com.voxapps.commander.domain.intent.interpreter.AssistantEngine
import com.voxapps.commander.domain.intent.interpreter.OpenAiInterpreter
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
    private val localLlmEngine: AssistantEngine,
    private val settingsRepo: SettingsRepository
) {
    /**
     * [imageUri] is forwarded as-is to whichever engine is selected — only engines that report
     * multimodal support (see [RemoteModelRegistry.isMultimodal]) do anything with it; the caller is
     * expected to have already checked capability before setting this (e.g. via the
     * [com.voxapps.ipc.VoxCapabilityClient] query), not rely on this call to gate it.
     */
    private val resolver = AiEngineResolver(
        openAiEngine = openAiEngine,
        localLlmEngine = localLlmEngine
    )

    suspend fun run(promptText: String, imageUri: String? = null): RawPromptOutcome {
        val snapshot = settingsRepo.getSettingsSnapshot()
        val processor = snapshot.aiProcessor

        // Engine *selection* is shared with IntentDecisionMap via the resolver; only the mapping of
        // a failure to a user-visible reason is this class's own, which is why it uses resolve()
        // rather than engineFor() — it needs to tell "no engine configured" apart from "the engine
        // is cloud and cloud is off".
        val choice = resolver.resolve(processor)
            ?: return RawPromptOutcome.Error("No LLM engine configured")
        if (choice.requiresCloud && !snapshot.cloudIntelligenceEnabled) {
            return RawPromptOutcome.Error("Cloud intelligence disabled")
        }

        val text = choice.engine.rawPrompt(promptText, imageUri)
        return if (text != null) RawPromptOutcome.Success(text) else RawPromptOutcome.Error(failureReason(processor))
    }

    private fun failureReason(processor: String): String = when (processor) {
        Strings.AiProcessors.OPENAI ->
            "OpenAI request failed: ${(openAiEngine as? OpenAiInterpreter)?.lastErrorReason ?: "unknown error"}"
        else -> "Local model unavailable (not downloaded or failed to load)"
    }
}
