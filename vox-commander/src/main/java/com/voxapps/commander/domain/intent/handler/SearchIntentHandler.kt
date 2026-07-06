package com.voxapps.commander.domain.intent.handler

import android.content.Context
import com.voxapps.commander.data.preferences.SettingsRepository
import com.voxapps.commander.domain.intent.model.NluIntent
import com.voxapps.commander.domain.intent.registry.AppRegistry
import com.voxapps.commander.domain.intent.taxonomy.IntentTaxonomy
import com.voxapps.commander.domain.search.LocationHelper
import com.voxapps.commander.domain.search.SearchProviderRouter
import com.voxapps.commander.domain.conversation.ConversationHandler
import com.voxapps.commander.utils.Logger
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
                val location = LocationHelper.getLocation(context)
                if (location != null) {
                    lat = location.latitude
                    lon = location.longitude
                } else {
                    Logger.log("Search requires location but none available", TAG)
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
