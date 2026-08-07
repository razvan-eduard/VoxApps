package com.voxapps.commander.domain.intent

import com.voxapps.commander.data.remote.RemoteModelRegistry
import com.voxapps.commander.domain.intent.interpreter.AssistantEngine
import com.voxapps.commander.utils.Strings

/**
 * Maps an `aiProcessor` settings key to the engine that serves it.
 *
 * This mapping was written out three times — [IntentDecisionMap]'s L2 step, its L3 fallback step
 * (a near-verbatim copy of L2), and [LlmHookEngineSelector] — each restating the same four branches
 * *and* the same "cloud processors only run when cloud intelligence is enabled" gate. Three copies
 * of a `when` over the same keys means adding an engine is three edits, and the two inside
 * [IntentDecisionMap] had already drifted into a different branch order.
 *
 * The gate is expressed as data ([Choice.requiresCloud]) rather than repeated `if` branches, so a
 * caller decides once what an unavailable engine means to it: the cascade treats it as "skip to the
 * next level", the LLM hook turns it into a user-visible reason string.
 */
class AiEngineResolver(
    private val openAiEngine: AssistantEngine,
    private val geminiCloudEngine: AssistantEngine,
    private val geminiNanoEngine: AssistantEngine,
    private val localLlmEngine: AssistantEngine
) {
    /** @property requiresCloud whether this engine is gated on `cloudIntelligenceEnabled`. */
    data class Choice(val engine: AssistantEngine, val requiresCloud: Boolean)

    /**
     * The engine registered for [processor], ignoring availability, or `null` when the key names no
     * engine at all — an unrecognised value, or a model id that isn't an LLM.
     */
    fun resolve(processor: String): Choice? = when (processor) {
        Strings.AiProcessors.OPENAI -> Choice(openAiEngine, requiresCloud = true)
        Strings.AiProcessors.GEMINI_CLOUD -> Choice(geminiCloudEngine, requiresCloud = true)
        Strings.AiProcessors.GEMINI_NATIVE -> Choice(geminiNanoEngine, requiresCloud = false)
        // Everything else is a model id from models.json rather than one of the built-in keys above.
        else -> if (RemoteModelRegistry.isLlmEngine(processor)) {
            Choice(localLlmEngine, requiresCloud = false)
        } else {
            null
        }
    }

    /**
     * [resolve] plus the cloud gate applied: `null` if the processor names no engine *or* names a
     * cloud engine while [cloudIntelligenceEnabled] is off. Callers that need to tell those two
     * cases apart should use [resolve] directly.
     */
    fun engineFor(processor: String, cloudIntelligenceEnabled: Boolean): AssistantEngine? =
        resolve(processor)?.takeIf { !it.requiresCloud || cloudIntelligenceEnabled }?.engine
}
