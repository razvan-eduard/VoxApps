package com.voxapps.commander.domain.intent

import com.voxapps.commander.domain.intent.interpreter.AssistantEngine
import com.voxapps.commander.domain.intent.model.NluIntent
import com.voxapps.commander.data.preferences.AppSettings
import com.voxapps.commander.data.preferences.SettingsRepository
import com.voxapps.commander.utils.Strings
import com.voxapps.logging.Logger

/**
 * Master orchestrator for command interpretation.
 * Implements the Advanced Triple AI Architecture:
 * L1 (Fast Trigger Map - Regex) 
 *  -> L2 (Primary Selected Model - Could be Cloud or Local)
 *  -> L3 (User-defined Default Offline Fallback)
 */
class IntentDecisionMap(
    private val l1Engine: AssistantEngine,
    private val l2CloudEngine: AssistantEngine,
    private val l3LocalEngine: AssistantEngine,
    private val geminiNanoEngine: AssistantEngine,
    private val geminiCloudEngine: AssistantEngine,
    private val settingsRepo: SettingsRepository
) : AssistantEngine {

    private val TAG = Strings.Tags.INTENT_DECISION_MAP

    /** Single source of truth for processor-key -> engine, shared by L2, L3 and [rawPrompt]. */
    private val resolver = AiEngineResolver(
        openAiEngine = l2CloudEngine,
        geminiCloudEngine = geminiCloudEngine,
        geminiNanoEngine = geminiNanoEngine,
        localLlmEngine = l3LocalEngine
    )

    override suspend fun processCommand(spokenText: String, modelFilterLang: String?): NluIntent? {
        if (spokenText.isBlank()) return null

        Logger.log("🧠 Processing '$spokenText'", TAG)

        val snapshot = settingsRepo.getSettingsSnapshot()
        val stages = stagesFor(snapshot)

        // firstNotNullOfOrNull over an explicit stage list, rather than three inline
        // `if (result != null) return` stanzas: the levels differ only in which engine they ask and
        // what they log, so the cascade is data. It also removes the "is the fallback the same as
        // the primary?" special case — a duplicate stage simply never gets built (see stagesFor),
        // instead of being detected and skipped after the fact.
        val match = stages.firstNotNullOfOrNull { stage ->
            Logger.log("🔍 Trying ${stage.label}...", TAG)
            val result = try {
                stage.engine.processCommand(spokenText, modelFilterLang)
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Never swallowed: this is the caller abandoning the command, not an engine failing.
                // Treating it as a miss would send the request on to the next level after the scope
                // that wanted it is already gone.
                throw e
            } catch (e: Exception) {
                // A failing level is a miss, not a failed command — the next level still gets a go.
                // Note this is wider than the code it replaces, which guarded only L2: a throw from
                // the L1 trigger map or the L3 fallback used to propagate to the caller. Uniform is
                // the intended behaviour — one engine blowing up shouldn't cost the user the other
                // two — but it is a behaviour change, not a pure refactor.
                Logger.log("${stage.label} failed: ${e.message}", TAG)
                null
            }
            result?.also { Logger.log("✅ ${stage.label} MATCH: $it", TAG) }
        }

        if (match == null) Logger.log("🚫 NO INTENT DETECTED at any level.", TAG)
        return match
    }

    private data class Stage(val label: String, val engine: AssistantEngine)

    /**
     * The engines to try, in order: the local regex trigger map, then the user's primary AI
     * processor, then their configured offline fallback.
     *
     * A level is simply absent when it can't run — an unset fallback, an engine whose key resolves
     * to nothing, or a cloud engine while cloud intelligence is off (see [AiEngineResolver]) — so
     * the loop above never has to ask why. The fallback is also dropped when its processor key
     * equals the primary's — the one case the old code handled with an explicit equality check
     * partway down the cascade. (Keys, not resolved engines: two different local model ids both
     * resolve to the same local engine and still produce two stages, exactly as before.)
     */
    private fun stagesFor(snapshot: AppSettings): List<Stage> {
        val cloudOk = snapshot.cloudIntelligenceEnabled
        val primary = snapshot.aiProcessor
        val fallback = snapshot.defaultIntentFallbackProcessor

        val stages = mutableListOf(Stage("L1 (trigger map)", l1Engine))
        resolver.engineFor(primary, cloudOk)?.let { stages += Stage("L2 ($primary)", it) }
        if (fallback != null && fallback != primary && snapshot.defaultIntentFallbackModel != null) {
            resolver.engineFor(fallback, cloudOk)?.let { stages += Stage("L3 fallback ($fallback)", it) }
        }
        return stages
    }

    /**
     * Delegates to whichever engine the current `aiProcessor` selects — the same engine L2 would
     * use, without the L1/L3 cascade, since a raw prompt has no regex or fallback stage to run.
     *
     * This used to hard-return null on the grounds that raw prompts belong to
     * [LlmHookEngineSelector]. Nothing calls it today, so that returned nothing rather than breaking
     * anything — but as an [AssistantEngine] this type refused an operation every one of its leaves
     * implements, so any future caller holding the interface would have silently got no answer from
     * this one implementation. Delegating costs nothing and makes the type honest.
     */
    override suspend fun rawPrompt(promptText: String, imageUri: String?): String? {
        val snapshot = settingsRepo.getSettingsSnapshot()
        return resolver.engineFor(snapshot.aiProcessor, snapshot.cloudIntelligenceEnabled)
            ?.rawPrompt(promptText, imageUri)
    }
}
