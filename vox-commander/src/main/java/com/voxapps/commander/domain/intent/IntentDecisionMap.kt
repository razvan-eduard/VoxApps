package com.voxapps.commander.domain.intent

import com.voxapps.commander.domain.intent.interpreter.AssistantEngine
import com.voxapps.commander.domain.intent.model.NluIntent
import com.voxapps.commander.data.preferences.SettingsRepository
import com.voxapps.commander.utils.Strings
import com.voxapps.commander.utils.Logger

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

    override suspend fun processCommand(spokenText: String, modelFilterLang: String?): NluIntent? {
        if (spokenText.isBlank()) return null
        
        Logger.log("🧠 Triple AI Brain: Processing '$spokenText'", TAG)

        // --- LEVEL 1: Fast Trigger Map (Local Regex) ---
        val l1Result = l1Engine.processCommand(spokenText, modelFilterLang)
        if (l1Result != null) {
            Logger.log("✅ L1 MATCH: $l1Result", TAG)
            return l1Result
        }

        // --- LEVEL 2: Primary Selected Model ---
        val snapshot = settingsRepo.getSettingsSnapshot()
        val isCloudIntelligenceEnabled = snapshot.cloudIntelligenceEnabled
        val primaryProcessor = snapshot.aiProcessor
        
        Logger.log("🔍 L1 Miss. Trying Primary L2 AI ($primaryProcessor)...", TAG)
        
        val l2Result = try {
            when (primaryProcessor) {
                Strings.AiProcessors.OPENAI -> {
                    if (isCloudIntelligenceEnabled) l2CloudEngine.processCommand(spokenText, modelFilterLang) else null
                }
                Strings.AiProcessors.GEMINI_NATIVE -> {
                    geminiNanoEngine.processCommand(spokenText, modelFilterLang)
                }
                Strings.AiProcessors.GEMINI_CLOUD -> {
                    if (isCloudIntelligenceEnabled) geminiCloudEngine.processCommand(spokenText, modelFilterLang) else null
                }
                else -> {
                    // JSON-defined LLM engines
                    if (com.voxapps.commander.data.remote.RemoteModelRegistry.isLlmEngine(primaryProcessor)) {
                        l3LocalEngine.processCommand(spokenText, modelFilterLang)
                    } else null
                }
            }
        } catch (e: Exception) {
            Logger.log("L2 Processing failed: ${e.message}", TAG)
            null
        }

        if (l2Result != null) {
            Logger.log("✅ L2 MATCH ($primaryProcessor): $l2Result", TAG)
            return l2Result
        }

        // --- LEVEL 3: Default Offline Fallback ---
        // Triggered if L2 fails (e.g., no internet for Cloud, or Llama failed/not present)
        val fallbackModel = snapshot.defaultIntentFallbackModel
        val fallbackProcessor = snapshot.defaultIntentFallbackProcessor

        if (fallbackModel != null && fallbackProcessor != null) {
            // Avoid re-running the same model if it was already tried in L2
            if (fallbackProcessor == primaryProcessor) {
                Logger.log("ℹ️ Fallback is same as Primary ($fallbackProcessor). Skipping redundant check.", TAG)
            } else {
                Logger.log("🏠 L2 Miss/Failure. Triggering L3 Offline Fallback ($fallbackProcessor)...", TAG)
                val l3Result = when (fallbackProcessor) {
                    Strings.AiProcessors.OPENAI -> {
                        if (isCloudIntelligenceEnabled) l2CloudEngine.processCommand(spokenText, modelFilterLang) else null
                    }
                    Strings.AiProcessors.GEMINI_CLOUD -> {
                        if (isCloudIntelligenceEnabled) geminiCloudEngine.processCommand(spokenText, modelFilterLang) else null
                    }
                    Strings.AiProcessors.GEMINI_NATIVE -> geminiNanoEngine.processCommand(spokenText, modelFilterLang)
                    else -> {
                        if (com.voxapps.commander.data.remote.RemoteModelRegistry.isLlmEngine(fallbackProcessor)) {
                            l3LocalEngine.processCommand(spokenText, modelFilterLang)
                        } else null
                    }
                }
                
                if (l3Result != null) {
                    Logger.log("✅ L3 FALLBACK MATCH: $l3Result", TAG)
                    return l3Result
                }
            }
        }

        Logger.log("🚫 NO INTENT DETECTED at any level.", TAG)
        return null
    }
}
