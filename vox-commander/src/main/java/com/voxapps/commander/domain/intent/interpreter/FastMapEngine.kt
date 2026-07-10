package com.voxapps.commander.domain.intent.interpreter

import com.voxapps.commander.data.local.dao.FastMapDao
import com.voxapps.commander.domain.intent.model.NluIntent
import com.voxapps.commander.utils.Logger
import com.voxapps.commander.utils.RegexGenerator

import kotlinx.coroutines.flow.first

class FastMapEngine(
    private val fastMapDao: FastMapDao
) : AssistantEngine {

    private val TAG = "FastMapEngine"

    override suspend fun processCommand(spokenText: String, modelFilterLang: String?): NluIntent? {
        val rules = fastMapDao.getAllRules().first().filter { it.isActive }

        for (rule in rules) {
            // Build trigger regex from triggerWords + triggerGroups (if any)
            val allTriggerGroups = buildList {
                if (rule.triggerWords.isNotEmpty()) add(rule.triggerWords)
                addAll(rule.triggerGroups.filter { it.isNotEmpty() })
            }
            val triggerRegexStr = if (allTriggerGroups.size <= 1) {
                RegexGenerator.fromWords(rule.triggerWords)
            } else {
                RegexGenerator.fromWordGroups(allTriggerGroups)
            }
            val hasTrigger = triggerRegexStr.isNotBlank()
            val hasQuery = rule.queryWords.isNotEmpty()

            if (!hasTrigger && !hasQuery) continue

            // Compile the trigger regex once, tolerantly: a single malformed rule must not
            // crash L1 (which would block L2/L3). Skip the rule if its pattern is invalid.
            val triggerRegex: Regex? = if (hasTrigger) {
                try {
                    Regex(triggerRegexStr, RegexOption.IGNORE_CASE)
                } catch (e: Exception) {
                    Logger.log("Skipping FastMap rule ${rule.id}: invalid trigger regex '$triggerRegexStr' — ${e.message}", TAG)
                    continue
                }
            } else null

            // If no trigger, always match (query-only rule)
            val triggerMatched = triggerRegex?.containsMatchIn(spokenText) ?: true

            if (triggerMatched) {
                // If this is a pure transport control (no query, no lazyQuery, no uriTemplate)
                // but the spoken text has extra words beyond the trigger, skip it —
                // the user likely wants to search/play something specific, not just press play.
                if (hasTrigger && !rule.lazyQuery && rule.queryWords.isEmpty() && rule.uriTemplate == null &&
                    rule.mediaControlType == "audio_button" && rule.action == "play") {
                    val remaining = triggerRegex?.let { spokenText.replace(it, "").trim() } ?: spokenText.trim()
                    if (remaining.isNotEmpty()) {
                        // Extra words beyond trigger — skip this rule, let L2 handle it
                        continue
                    }
                }

                // Build query
                val query = if (rule.lazyQuery) {
                    // Lazy: extract everything from spokenText except trigger words + app name
                    var remaining = spokenText
                    if (hasTrigger) {
                        triggerRegex?.let { remaining = remaining.replace(it, " ") }
                    }
                    // Remove app display name if present
                    val appEntry = com.voxapps.commander.domain.intent.registry.AppRegistry.resolveByPackage(rule.targetPackage)
                    if (appEntry != null) {
                        val appNamePattern = Regex("(?i)\\b${Regex.escape(appEntry.displayName)}\\b")
                        remaining = remaining.replace(appNamePattern, " ")
                    }
                    remaining.trim().replace(Regex("\\s+"), " ").ifBlank { null }
                } else {
                    rule.queryWords.joinToString(" ").ifBlank { null }
                }

                return NluIntent(
                    actionVerb = rule.action,
                    logicalSubject = query,
                    domain = rule.domain,
                    action = rule.action,
                    targetApp = rule.targetPackage.ifBlank { null },
                    confidence = 1.0f,
                    intentAction = rule.intentAction.ifBlank { null },
                    uriTemplate = rule.uriTemplate,
                    mediaControlType = rule.mediaControlType.ifBlank { null }
                )
            }
        }

        return null
    }

    // Regex fast-path has no concept of a raw prompt — always a miss.
    override suspend fun rawPrompt(promptText: String): String? = null
}
