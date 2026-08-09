package com.voxapps.commander.domain.intent

import com.voxapps.commander.data.remote.RemoteModelRegistry
import com.voxapps.commander.domain.engine.EngineSelection
import com.voxapps.commander.domain.intent.interpreter.AssistantEngine
import com.voxapps.commander.domain.intent.interpreter.SelectableModelEngine
import com.voxapps.commander.domain.intent.model.NluIntent
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

    /**
     * The engine for [processor] *and* the model it should run — everything needed to make the
     * call, resolved together.
     *
     * Together, because they were resolved apart: the cascade picked an engine here and the engine
     * picked a model from global settings, so a level running a non-active selection was not
     * expressible. Only an engine that can be told which model to run gets a selection; for the
     * rest, [Call.selection] is null because the model is the service's, not ours.
     */
    fun callFor(processor: String, modelId: String?, cloudIntelligenceEnabled: Boolean): Call? {
        val engine = engineFor(processor, cloudIntelligenceEnabled) ?: return null
        val selection = if (engine is SelectableModelEngine && modelId != null) {
            EngineSelection(processor, modelId)
        } else {
            null
        }
        return Call(engine, selection)
    }

    /**
     * A resolved level of the cascade: which engine, running which model.
     *
     * Two calls are the same work when both fields match — same instance *and* same selection. That
     * is what a level has to compare against the one before it, not engine identity alone: every
     * on-device LLM key resolves to the same interpreter, so identity alone cannot tell "the same
     * model again, which just failed" from "a different model, which is the whole point".
     */
    data class Call(val engine: AssistantEngine, val selection: EngineSelection?) {
        suspend fun invoke(spokenText: String, modelFilterLang: String?): NluIntent? =
            if (engine is SelectableModelEngine && selection != null) {
                engine.processCommand(spokenText, modelFilterLang, selection)
            } else {
                engine.processCommand(spokenText, modelFilterLang)
            }

        /** Identity by instance, not by `equals`: two engines are the same engine when they are the
         *  same object — mocks and stateless engines can otherwise compare equal. */
        fun isSameWorkAs(other: Call): Boolean =
            engine === other.engine && selection == other.selection
    }
}
