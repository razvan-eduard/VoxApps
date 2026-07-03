package com.voxcommander.app.domain.intent.interpreter

import com.voxcommander.app.data.preferences.AppSettings
import com.voxcommander.app.data.remote.RemoteModelRegistry
import com.voxcommander.app.domain.intent.registry.AppRegistry
import com.voxcommander.app.domain.intent.taxonomy.IntentTaxonomy
import com.voxcommander.app.domain.search.SearchProviderRegistry
import com.voxcommander.app.data.preferences.SettingsRepository

/**
 * Provides hydrated prompt templates for AI agents.
 * Loads the static template from models.json and injects dynamic placeholders:
 * - ${installedApps}: list of installed apps per domain (with user default markers)
 * - ${spokenText}: user's voice/text command (stripped for system prompt)
 */
object PromptProvider {

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
        val template = RemoteModelRegistry.getPrompt(ID_STANDARD_NLU) ?: return ""
        val langHint = modelFilterLang?.let { "\nInput language: $it." } ?: ""
        // Strip the Input/JSON suffix — engines add their own user message
        val inputIndex = template.indexOf("Input:")
        val systemPart = if (inputIndex > 0) {
            template.substring(0, inputIndex).trim()
        } else {
            template.replace(PLACEHOLDER_TEXT, "")
        }
        return systemPart
            .replace(PLACEHOLDER_DOMAINS, IntentTaxonomy.Domains.ALL.joinToString(", ") { "\"$it\"" })
            .replace(PLACEHOLDER_ACTIONS, IntentTaxonomy.Actions.ALL.joinToString(", ") { "\"$it\"" })
            .replace(PLACEHOLDER_APPS, buildAppsSection(settings))
            .replace(PLACEHOLDER_SEARCH, buildSearchSection(settingsRepo))
            .plus(langHint)
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

        for (domain in IntentTaxonomy.Domains.ALL) {
            val apps = AppRegistry.getInstalledAppsForDomain(domain)
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
