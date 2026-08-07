package com.voxapps.commander.domain.search

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Singleton bridge between SearchIntentHandler (background) and UI.
 * SearchIntentHandler writes results here; MainViewModel observes and exposes to UI.
 *
 * Deliberately a [MutableStateFlow] and not a `SharedFlow`: this holds the *latest search summary*
 * as displayed state, not a one-shot event. MainScreen renders it in a card that stays on screen
 * until the next search replaces it, falling back to a "no search results" placeholder while it is
 * null — so a late subscriber, a recomposition, or a rotation all need the current value replayed,
 * which is what a StateFlow gives and a SharedFlow (without replay) would drop.
 */
object SearchResultsHolder {
    private val _searchResults = MutableStateFlow<String?>(null)
    val searchResults = _searchResults.asStateFlow()

    fun setResults(summary: String) {
        _searchResults.value = summary
    }
}
