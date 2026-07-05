package com.voxcommander.app.domain.intent.interpreter

import com.voxcommander.app.data.local.dao.FastMapDao
import com.voxcommander.app.domain.intent.model.NluIntent
import com.voxcommander.app.utils.RegexGenerator

import kotlinx.coroutines.flow.first

class FastMapEngine(
    private val fastMapDao: FastMapDao
) : AssistantEngine {

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

            // If no trigger, always match (query-only rule)
            val triggerMatched = if (!hasTrigger) {
                true
            } else {
                Regex(triggerRegexStr, RegexOption.IGNORE_CASE).containsMatchIn(spokenText)
            }

            if (triggerMatched) {
                // If this is a pure transport control (no query, no lazyQuery, no uriTemplate)
                // but the spoken text has extra words beyond the trigger, skip it —
                // the user likely wants to search/play something specific, not just press play.
                if (hasTrigger && !rule.lazyQuery && rule.queryWords.isEmpty() && rule.uriTemplate == null &&
                    rule.mediaControlType == "audio_button" && rule.action == "play") {
                    val triggerRegex = Regex(triggerRegexStr, RegexOption.IGNORE_CASE)
                    val remaining = spokenText.replace(triggerRegex, "").trim()
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
                        remaining = remaining.replace(Regex(triggerRegexStr, RegexOption.IGNORE_CASE), " ")
                    }
                    // Remove app display name if present
                    val appEntry = com.voxcommander.app.domain.intent.registry.AppRegistry.resolveByPackage(rule.targetPackage)
                    if (appEntry != null) {
                        // (?U) makes \b Unicode-aware so app names with diacritics/non-ASCII
                        // letters still get a valid word boundary (same fix as RegexGenerator).
                        val appNamePattern = Regex("(?iU)\\b${Regex.escape(appEntry.displayName)}\\b")
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
}
