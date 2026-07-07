package com.voxapps.commander.domain.intent.interpreter

import com.voxapps.commander.data.preferences.AppSettings
import com.voxapps.commander.data.remote.RemoteModelRegistry
import com.voxapps.commander.domain.integration.VoxAppInfo
import com.voxapps.commander.domain.integration.VoxSatelliteRegistry
import com.voxapps.commander.domain.intent.registry.AppRegistry
import com.voxapps.commander.domain.intent.taxonomy.IntentTaxonomy
import com.voxapps.commander.domain.search.SearchProviderRegistry
import com.voxapps.commander.data.preferences.SettingsRepository
import com.voxapps.commander.utils.Logger

/**
 * Provides hydrated prompt templates for AI agents.
 * Loads the static template from models.json and injects dynamic placeholders:
 * - ${installedApps}: list of installed apps per domain (with user default markers)
 * - ${spokenText}: user's voice/text command (stripped for system prompt)
 */
object PromptProvider {

    private const val TAG = "PromptProvider"
    private const val ID_STANDARD_NLU = "standard_nlu"
    private const val PLACEHOLDER_TEXT = "\${spokenText}"
    private const val PLACEHOLDER_APPS = "\${installedApps}"
    private const val PLACEHOLDER_SEARCH = "\${searchProviders}"
    private const val PLACEHOLDER_DOMAINS = "\${domains}"
    private const val PLACEHOLDER_ACTIONS = "\${actions}"

    /**
     * Returns only the system instructions (without the input line).
     * Used by all engines (OpenAI, Gemini Cloud, Local LLM) — they add user input separately.
     */
    fun getNluSystemPrompt(settings: AppSettings? = null, modelFilterLang: String? = null, settingsRepo: SettingsRepository? = null): String {
        val template = RemoteModelRegistry.getPrompt(ID_STANDARD_NLU)
        if (template == null) {
            Logger.log("NLU prompt template '$ID_STANDARD_NLU' not found (models.json not loaded?) — returning empty system prompt", TAG)
            return ""
        }
        val langHint = modelFilterLang?.let { "\nInput language: $it." } ?: ""
        val systemPart = stripToRules(template)
        val allDomains = (IntentTaxonomy.Domains.ALL + (settings?.customDomains ?: emptyList())).distinct()
        return systemPart
            .replace(PLACEHOLDER_DOMAINS, allDomains.joinToString(", ") { "\"$it\"" })
            .replace(PLACEHOLDER_ACTIONS, IntentTaxonomy.Actions.ALL.joinToString(", ") { "\"$it\"" })
            .replace(PLACEHOLDER_APPS, buildAppsSection(settings))
            .replace(PLACEHOLDER_SEARCH, buildSearchSection(settingsRepo))
            .plus(buildSatelliteHints(VoxSatelliteRegistry.apps.value))
            .plus(langHint)
    }

    /**
     * Domain-specific extraction hints declared by installed satellites (via meta-data). Lets a rich
     * companion app teach the LLM its own fields without any edit to Commander/models.json. Returns an
     * empty string (nothing appended) when no installed satellite declares a hint.
     */
    internal fun buildSatelliteHints(apps: List<VoxAppInfo>): String {
        val hinted = apps.filter { !it.nluHint.isNullOrBlank() && !it.domain.isNullOrBlank() }
        if (hinted.isEmpty()) return ""
        return buildString {
            append("\n\nDomain-specific extraction:")
            for (app in hinted) append("\n- ${app.domain}: ${app.nluHint!!.trim()}")
        }
    }

    /**
     * Returns the rules-only portion of the template: everything before the "Examples:" section
     * (and its trailing `Input: "${spokenText}"` placeholder). The anatomy rules are
     * self-describing, so few-shot examples are intentionally excluded. Cutting at the first
     * "Input:" (old behaviour) left a dangling "Examples:" header — a malformed tail.
     */
    internal fun stripToRules(template: String): String {
        val examplesCut = template.indexOf("Examples:")
        if (examplesCut > 0) return template.substring(0, examplesCut).trim()
        // Fallback (no Examples: section): drop only the trailing input placeholder line.
        val inputCut = template.indexOf("Input: \"$PLACEHOLDER_TEXT\"")
        return if (inputCut > 0) template.substring(0, inputCut).trim()
               else template.replace(PLACEHOLDER_TEXT, "").trim()
    }

    /**
     * Formats the user command as an input line.
     */
    fun formatUserInput(spokenText: String): String {
        return "Input: \"$spokenText\"\nJSON:"
    }

    /**
     * Builds a section listing installed apps per domain and user default app selections.
     * This helps the LLM generate more refined targetApp values.
     */
    private fun buildAppsSection(settings: AppSettings?): String {
        val sb = StringBuilder()
        sb.appendLine("Available installed apps (use the exact name as targetApp):")

        // Build alias map: packageName -> list of aliases
        val aliasMap = settings?.appAliasRules
            ?.filter { it.enabled }
            ?.flatMap { rule -> rule.aliases.map { alias -> alias to rule.packageName } }
            ?.toMap() ?: emptyMap()

        val domains = (IntentTaxonomy.Domains.ALL + (settings?.customDomains ?: emptyList())).distinct()
        for (domain in domains) {
            // Probed apps for the domain, plus any the user manually assigned (custom categories
            // only live in domainAppPackages — they have no probed domain).
            val assigned = settings?.domainAppPackages?.get(domain)
                ?.mapNotNull { AppRegistry.resolveByPackage(it) } ?: emptyList()
            val apps = (AppRegistry.getInstalledAppsForDomain(domain) + assigned).distinctBy { it.packageName }
            if (apps.isEmpty()) continue

            val defaultPkg = settings?.defaultAppPackages?.get(domain)
            val defaultApp = apps.find { it.packageName == defaultPkg }

            sb.appendLine("  $domain:")
            for (app in apps) {
                val isDefault = defaultApp != null && app.packageName == defaultApp.packageName
                val marker = if (isDefault) " [USER DEFAULT]" else ""
                val aliases = aliasMap.entries.filter { it.value == app.packageName }.map { it.key }
                val aliasStr = if (aliases.isNotEmpty()) " (also: ${aliases.joinToString(", ")})" else ""
                sb.appendLine("    - ${app.displayName}$marker$aliasStr")
            }
        }

        return sb.toString().trim()
    }

    /**
     * Builds a section listing available search categories and their providers.
     * Only includes providers that don't require an API key, or have one configured.
     * This helps the LLM choose the right category for search intents.
     */
    private fun buildSearchSection(settingsRepo: SettingsRepository?): String {
        val sb = StringBuilder()
        sb.appendLine("Available search categories and providers:")

        for (category in SearchProviderRegistry.categories) {
            val allNames = SearchProviderRegistry.getProviderNames(category)
            // Filter out API-key providers that don't have a key configured
            val availableNames = allNames.filter { name ->
                val provider = SearchProviderRegistry.getProvider(category, name)
                if (provider?.requiresApiKey == true) {
                    settingsRepo?.getSearchProviderApiKeySync(name)?.isNotBlank() == true
                } else {
                    true
                }
            }
            if (availableNames.isEmpty()) continue

            sb.appendLine("  $category: ${availableNames.joinToString(", ")}")
        }

        return sb.toString().trim()
    }
}
