package com.voxapps.commander.domain.intent.interpreter

import com.voxapps.commander.data.preferences.AppSettings
import com.voxapps.commander.data.remote.RemoteModelRegistry
import com.voxapps.ipc.VoxAppInfo
import com.voxapps.commander.domain.integration.VoxSatelliteRegistry
import com.voxapps.commander.domain.intent.model.FastMapRule
import com.voxapps.commander.domain.intent.registry.AppRegistry
import com.voxapps.commander.domain.intent.taxonomy.IntentTaxonomy
import com.voxapps.commander.domain.search.SearchProviderRegistry
import com.voxapps.commander.data.preferences.SettingsRepository
import com.voxapps.logging.Logger

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
     *
     * [spokenText] and [fastMapRules] exist purely to scope the apps/domains sections down to
     *  what's actually relevant to this utterance — see [relevantDomains]'s doc comment for why
     *  an empty/missing value here just falls back to "include everything" rather than breaking
     *  anything. [fastMapRules] should be the caller's *active* rules (already filtered by
     *  [FastMapRule.isActive]); this function doesn't filter them again.
     */
    fun getNluSystemPrompt(
        spokenText: String = "",
        settings: AppSettings? = null,
        modelFilterLang: String? = null,
        settingsRepo: SettingsRepository? = null,
        fastMapRules: List<FastMapRule> = emptyList()
    ): String {
        val template = RemoteModelRegistry.getPrompt(ID_STANDARD_NLU)
        if (template == null) {
            Logger.log("NLU prompt template '$ID_STANDARD_NLU' not found (models.json not loaded?) — returning empty system prompt", TAG)
            return ""
        }
        val langHint = modelFilterLang?.let { "\nInput language: $it." } ?: ""
        val systemPart = stripToRules(template)
        val allDomains = (IntentTaxonomy.Domains.ALL + (settings?.customDomains ?: emptyList())).distinct()
        val domainKeywords = buildDomainKeywords(allDomains, settings, fastMapRules)
        val scopedDomainSet = relevantDomains(spokenText, domainKeywords)
        // Preserves allDomains' original order rather than the Set's iteration order — the
        // ${domains} placeholder is read by a human-adjacent LLM prompt, not just machine-parsed.
        val scopedDomains = allDomains.filter { it in scopedDomainSet }
        return systemPart
            .replace(PLACEHOLDER_DOMAINS, scopedDomains.joinToString(", ") { "\"$it\"" })
            .replace(PLACEHOLDER_ACTIONS, IntentTaxonomy.Actions.ALL.joinToString(", ") { "\"$it\"" })
            .replace(PLACEHOLDER_APPS, buildAppsSection(settings, scopedDomains))
            .replace(PLACEHOLDER_SEARCH, buildSearchSection(settingsRepo))
            .plus(buildSatelliteHints(VoxSatelliteRegistry.apps.value))
            .plus(langHint)
    }

    /** Which of [domainKeywords]' domains are relevant to [spokenText] — a token-bloat
     *  optimization for the apps/domains prompt sections, not a routing gate: matching is a pure
     *  case-insensitive substring test against each domain's own already-domain-tagged keywords
     *  (selected-app names/aliases, active FastMap rule trigger words — see
     *  [buildDomainKeywords]), and if *nothing* matches (e.g. "play some music" names no specific
     *  app), this falls back to every domain in [domainKeywords] rather than guessing wrong —
     *  a false negative here would silently break app-targeting for that command, which is worse
     *  than the token bloat this exists to avoid. Pure function (no Android/DAO access) so it's
     *  directly unit-testable. */
    internal fun relevantDomains(spokenText: String, domainKeywords: Map<String, List<String>>): Set<String> {
        val text = spokenText.lowercase()
        val matched = domainKeywords.filterValues { keywords ->
            keywords.any { it.isNotBlank() && text.contains(it.lowercase()) }
        }.keys
        return matched.ifEmpty { domainKeywords.keys }
    }

    /** Builds each domain's keyword set from data that's already domain-tagged elsewhere in the
     *  app — no new classifier, per the same reasoning as [relevantDomains]: the selected apps'
     *  display names/aliases for that domain ([AppSettings.domainAppPackages]/[AppSettings.appAliasRules]),
     *  plus the trigger words/groups of [fastMapRules] whose own [FastMapRule.domain] matches —
     *  e.g. an audio-domain rule's "play"/"music" trigger words are exactly the kind of
     *  domain-associated vocabulary app names alone don't cover. */
    private fun buildDomainKeywords(domains: List<String>, settings: AppSettings?, fastMapRules: List<FastMapRule>): Map<String, List<String>> {
        return domains.associateWith { domain ->
            val assignedPackages = settings?.domainAppPackages?.get(domain) ?: emptyList()
            val appNames = assignedPackages.mapNotNull { AppRegistry.resolveByPackage(it)?.displayName }
            val aliasNames = settings?.appAliasRules
                ?.filter { it.enabled && it.packageName in assignedPackages }
                ?.flatMap { it.aliases } ?: emptyList()
            val ruleWords = fastMapRules
                .filter { it.domain == domain }
                .flatMap { it.triggerWords + it.triggerGroups.flatten() }
            (appNames + aliasNames + ruleWords).filter { it.isNotBlank() }
        }
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
     * Lists, per domain in [domains], only the apps the user actually selected for it in App
     * Manager ([AppSettings.domainAppPackages]) — not every OS-installed app matching that
     * domain's intent probe. Mirrors `AppResolver`'s own runtime resolution order (explicit
     * targetApp/alias > user default > user-selected > unfiltered-probe default), so the LLM's
     * suggested targetApp lines up with what would actually get resolved: a domain with nothing
     * configured yet falls back to a *single* best-guess app ([AppRegistry.getDefaultAppForDomain])
     * rather than dumping every installed alternative, so first-run app-targeting doesn't go
     * silent while still not bloating the prompt with apps the user never selected.
     */
    private fun buildAppsSection(settings: AppSettings?, domains: List<String>): String {
        val sb = StringBuilder()
        sb.appendLine("Available installed apps (use the exact name as targetApp):")

        // Build alias map: packageName -> list of aliases
        val aliasMap = settings?.appAliasRules
            ?.filter { it.enabled }
            ?.flatMap { rule -> rule.aliases.map { alias -> alias to rule.packageName } }
            ?.toMap() ?: emptyMap()

        for (domain in domains) {
            val selectedPackages = settings?.domainAppPackages?.get(domain) ?: emptyList()
            val apps = if (selectedPackages.isNotEmpty()) {
                selectedPackages.mapNotNull { AppRegistry.resolveByPackage(it) }
            } else {
                listOfNotNull(AppRegistry.getDefaultAppForDomain(domain))
            }
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
