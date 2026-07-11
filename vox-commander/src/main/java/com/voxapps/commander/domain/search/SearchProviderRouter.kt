package com.voxapps.commander.domain.search

import com.voxapps.commander.utils.Logger

/**
 * Routes search queries to the appropriate provider based on category.
 * Delegates to SearchProviderRegistry for provider resolution.
 *
 * Categories are defined in search_definitions.json and can be hot-reloaded
 * from the remote repo. Adding a new provider = just update the JSON.
 */
object SearchProviderRouter {

    private const val TAG = "SearchProviderRouter"

    /**
     * Executes a search using the appropriate provider for the category.
     * @param query The search query
     * @param category Search category: "weather", "general", "news", "knowledge"
     * @param lat Latitude (for weather provider)
     * @param lon Longitude (for weather provider)
     * @return List of SearchResult, empty list on failure
     */
    suspend fun search(
        query: String,
        category: String = "general",
        lat: Double? = null,
        lon: Double? = null,
        lang: String = "en"
    ): List<SearchResult> {
        val provider = SearchProviderRegistry.getProvider(category)
        if (provider == null) {
            Logger.log("No provider found for category='$category'", TAG)
            return emptyList()
        }

        Logger.log("Routing search '$query' to ${provider.name} (category=$category, lang=$lang)", TAG)

        if (provider.requiresLocation && lat == null) {
            Logger.log("${provider.name} requires location but none provided", TAG)
            return emptyList()
        }

        return provider.search(query, lat, lon, lang)
    }

    /**
     * Formats search results as a plain text summary suitable for TTS or LLM input.
     */
    fun formatResultsForSummary(query: String, results: List<SearchResult>): String {
        if (results.isEmpty()) return "No results found for: $query"

        val sb = StringBuilder()
        sb.appendLine("Search results for: $query")
        results.forEachIndexed { index, result ->
            sb.appendLine("${index + 1}. ${result.title}")
            if (result.content.isNotBlank()) {
                sb.appendLine("   ${result.content}")
            }
        }
        return sb.toString().trim()
    }

    /**
     * Formats search results as clean text suitable for TTS playback.
     * Strips the "Search results for:" header and number prefixes.
     */
    fun formatResultsForTTS(query: String, results: List<SearchResult>): String {
        if (results.isEmpty()) return "No results found for $query"

        val sb = StringBuilder()
        results.forEachIndexed { index, result ->
            // A blank title (e.g. the OpenAI provider's single synthesized answer, which has no
            // real title) would otherwise speak as a bare "1. " before the actual content.
            if (result.title.isNotBlank()) {
                sb.appendLine("${index + 1}. ${result.title}.")
            }
            if (result.content.isNotBlank()) {
                sb.appendLine(result.content.trim())
            }
        }
        return sb.toString().trim()
    }

    /** All available category names from the registry */
    val categories: List<String>
        get() = SearchProviderRegistry.categories
}
