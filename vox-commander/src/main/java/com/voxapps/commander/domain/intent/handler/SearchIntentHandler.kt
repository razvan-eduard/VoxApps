package com.voxapps.commander.domain.intent.handler

import android.content.Context
import com.voxapps.commander.data.preferences.SettingsRepository
import com.voxapps.commander.domain.intent.model.NluIntent
import com.voxapps.commander.domain.intent.registry.AppRegistry
import com.voxapps.commander.domain.intent.taxonomy.IntentTaxonomy
import com.voxapps.commander.domain.location.CommanderLocationStore
import com.voxapps.location.VoxLocationResolver
import com.voxapps.commander.domain.search.SearchProviderRouter
import com.voxapps.commander.domain.conversation.ConversationHandler
import com.voxapps.logging.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Handles search-domain intents by routing to the appropriate search provider
 * based on the "category" parameter in the NluIntent.
 *
 * Executes the search asynchronously, stores results, and speaks them via TTS
 * with barge-in support (user can interrupt with wake word).
 */
class SearchIntentHandler(
    private val settingsRepository: SettingsRepository? = null
) : IntentHandler {

    companion object {
        private const val TAG = "SearchIntentHandler"
    }

    private val searchScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun canHandle(intent: NluIntent): Boolean {
        return intent.domain == IntentTaxonomy.Domains.SEARCH
    }

    override fun execute(context: Context, intent: NluIntent, resolvedApp: AppRegistry.AppEntry?): Boolean {
        val query = intent.logicalSubject ?: return false
        val category = intent.category ?: "general"

        Logger.log("SearchIntentHandler: query='$query', category='$category'", TAG)

        // Launch async search — execute() is synchronous but search is IO-bound
        searchScope.launch {
            // Get location if the category requires it
            var lat: Double? = null
            var lon: Double? = null
            val provider = com.voxapps.commander.domain.search.SearchProviderRegistry.getProvider(category)
            if (provider?.requiresLocation == true) {
                val repo = settingsRepository
                val location = if (repo != null) {
                    VoxLocationResolver.create(context, CommanderLocationStore(context, repo)).resolveLocation()
                } else null
                if (location != null) {
                    lat = location.lat
                    lon = location.lon
                } else {
                    // Say what actually went wrong. Running the search anyway returns nothing, and
                    // the caller then reports "no results found" — which reads as a broken provider
                    // or a bad API key and sends the user to check both, when the provider is fine
                    // and simply has no location to search from.
                    Logger.log("Search requires location but none available", TAG)
                    val message = "I need your location for $category searches. " +
                        "Allow location access, or set a home town in settings."
                    com.voxapps.commander.domain.search.SearchResultsHolder.setResults(message)
                    ConversationHandler.speakResponse(message)
                    return@launch
                }
            }

            val lang = settingsRepository?.getSettingsSnapshot()?.voiceLanguage ?: "en"

            val results = SearchProviderRouter.search(query, category, lat, lon, lang)
            val summary = SearchProviderRouter.formatResultsForSummary(query, results)
            val ttsText = SearchProviderRouter.formatResultsForTTS(query, results)

            Logger.log("Search results:\n$summary", TAG)
            com.voxapps.commander.domain.search.SearchResultsHolder.setResults(summary)

            // Speak the search results via TTS with barge-in support
            ConversationHandler.speakResponse(ttsText)
        }

        return true
    }
}
