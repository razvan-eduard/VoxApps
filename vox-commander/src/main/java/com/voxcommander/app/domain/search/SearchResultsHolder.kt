package com.voxcommander.app.domain.search

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Singleton bridge between SearchIntentHandler (background) and UI.
 * SearchIntentHandler writes results here; MainViewModel observes and exposes to UI.
 */
object SearchResultsHolder {
    private val _searchResults = MutableStateFlow<String?>(null)
    val searchResults = _searchResults.asStateFlow()

    fun setResults(summary: String) {
        _searchResults.value = summary
    }

    fun clear() {
        _searchResults.value = null
    }
}
