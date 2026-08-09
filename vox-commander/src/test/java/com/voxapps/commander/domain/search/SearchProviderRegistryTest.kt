package com.voxapps.commander.domain.search

import android.util.Log
import com.google.gson.Gson
import com.voxapps.logging.Logger
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.mockkStatic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * What the registry does with a schema it did not write.
 *
 * These files are served from a repository the user configures, so an incomplete one is ordinary
 * input rather than a bug in someone else's code — and the app has to keep working on the parts that
 * are complete.
 */
class SearchProviderRegistryTest {

    private val gson = Gson()

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>()) } returns 0
        mockkObject(Logger)
        every { Logger.log(any(), any()) } returns Unit
    }

    private fun ingest(json: String) =
        SearchProviderRegistry.rebuildProviders(
            gson.fromJson(json, SearchDefinitionsSchema::class.java)
        )

    /**
     * The one that used to throw. Gson skips the constructor for a class with a parameter that has
     * no default, so an omitted `providers` arrived as null behind `List<ProviderDefinition>` and
     * the ingest walked straight into it — taking every other category with it.
     */
    @Test
    fun `a category that declares no providers does not take the rest down`() {
        ingest(
            """
            {
              "schema_version": 9,
              "categories": [
                { "category": "empty" },
                { "category": "general", "providers": [
                    { "name": "DuckDuckGo", "endpoint": "https://api.duckduckgo.com/" }
                ]}
              ]
            }
            """.trimIndent()
        )

        assertTrue(SearchProviderRegistry.getProviderNames("empty").isEmpty())
        assertEquals(listOf("DuckDuckGo"), SearchProviderRegistry.getProviderNames("general"))
    }

    /** A provider with nothing to call, or nothing to be chosen by, costs itself and not its category. */
    @Test
    fun `an incomplete provider is dropped and its neighbours are kept`() {
        ingest(
            """
            {
              "schema_version": 9,
              "categories": [
                { "category": "news", "providers": [
                    { "name": "No Endpoint" },
                    { "endpoint": "https://example.com" },
                    { "name": "Google News", "endpoint": "https://news.google.com/rss/search" }
                ]}
              ]
            }
            """.trimIndent()
        )

        assertEquals(listOf("Google News"), SearchProviderRegistry.getProviderNames("news"))
    }

    /** With no default declared, the first usable provider answers — not the first declared one. */
    @Test
    fun `the default falls back to a provider that can actually answer`() {
        ingest(
            """
            {
              "schema_version": 9,
              "categories": [
                { "category": "weather", "providers": [
                    { "name": "Broken" },
                    { "name": "Open-Meteo", "endpoint": "https://api.open-meteo.com/v1/forecast" }
                ]}
              ]
            }
            """.trimIndent()
        )

        assertEquals("Open-Meteo", SearchProviderRegistry.getProvider("weather")?.name)
    }

    /** A category with no name cannot be asked for, so it is not kept under one. */
    @Test
    fun `a category with no name is skipped`() {
        ingest(
            """
            {
              "schema_version": 9,
              "categories": [
                { "providers": [ { "name": "X", "endpoint": "https://example.com" } ] },
                { "category": "general", "providers": [
                    { "name": "DuckDuckGo", "endpoint": "https://api.duckduckgo.com/" }
                ]}
              ]
            }
            """.trimIndent()
        )

        assertTrue(SearchProviderRegistry.getProviderNames("").isEmpty())
        assertEquals(listOf("DuckDuckGo"), SearchProviderRegistry.getProviderNames("general"))
    }
}
