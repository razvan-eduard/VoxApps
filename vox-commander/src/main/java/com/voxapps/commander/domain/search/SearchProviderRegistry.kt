package com.voxapps.commander.domain.search

import android.content.Context
import androidx.annotation.VisibleForTesting
import com.voxapps.commander.data.preferences.Credentials
import com.voxapps.commander.data.preferences.SettingsRepository
import com.voxapps.services.RemoteSchema
import com.voxapps.logging.Logger

object SearchProviderRegistry {

    private const val TAG = "SearchProviderRegistry"

    /** Held so the chosen provider can be *read* where it is used rather than pushed in from
     *  startup and from the settings screen — the API keys below are pushed, and every push site is
     *  another place that can be forgotten when a third one appears. */
    @Volatile private var settingsRepo: SettingsRepository? = null

    /** Written whenever the schema is adopted (Main via init, IO via fetchRemote); read from Compose
     *  on Main and from SearchIntentHandler on IO. */
    @Volatile private var providersByCategory: Map<String, Map<String, DynamicSearchProvider>> = emptyMap()
    /** Same write/read threads as providersByCategory — the two are rebuilt together. */
    @Volatile private var defaultProviderNames: Map<String, String> = emptyMap()

    private val schema = RemoteSchema(
        fileName = "search_definitions.json",
        type = SearchDefinitionsSchema::class.java,
        usable = { it.categories.isNotEmpty() },
        tag = TAG,
        onLoaded = { rebuildProviders(it) }
    )

    fun init(context: Context, repo: SettingsRepository? = null) {
        settingsRepo = repo
        schema.init(context)
    }


    /**
     * Turns a parsed schema into the providers the app uses, dropping what it cannot use.
     *
     * A provider with no name has nothing to be selected by and one with no endpoint has nothing to
     * call, so both are dropped and said so — a schema served from a repository is not something to
     * assume is complete, and a bad entry should cost that entry rather than the category.
     */
    @VisibleForTesting
    internal fun rebuildProviders(loaded: SearchDefinitionsSchema) {
        val newProviders = mutableMapOf<String, Map<String, DynamicSearchProvider>>()
        val newDefaults = mutableMapOf<String, String>()

        for (catDef in loaded.categories) {
            if (catDef.category.isBlank()) {
                Logger.log("Skipping a category that declares no name", TAG)
                continue
            }
            val usable = catDef.providers.filter { provDef ->
                val ok = provDef.name.isNotBlank() && provDef.endpoint.isNotBlank()
                if (!ok) Logger.log("Skipping an incomplete provider in '${catDef.category}'", TAG)
                ok
            }
            val providerMap = mutableMapOf<String, DynamicSearchProvider>()
            for (provDef in usable) {
                providerMap[provDef.name] = DynamicSearchProvider(provDef, catDef.category) {
                    settingsRepo?.getCredentialsSnapshot() ?: Credentials()
                }
            }
            newProviders[catDef.category] = providerMap
            newDefaults[catDef.category] = catDef.defaultProvider.ifBlank {
                usable.firstOrNull()?.name ?: ""
            }
        }

        providersByCategory = newProviders
        defaultProviderNames = newDefaults
        Logger.log("Rebuilt search providers: ${newProviders.map { "${it.key}=[${it.value.keys}]" }}", TAG)
    }

    /**
     * Whichever provider answers this category — the user's choice when they made one, the
     * category's declared default when they did not.
     *
     * The choice used to reach no further than the settings screen's own test box: a spoken query
     * always went to the declared default, so picking a provider changed nothing that mattered.
     */
    fun getProvider(category: String): DynamicSearchProvider? {
        val providers = providersByCategory[category] ?: return generalProvider()
        val chosen = chosenName(category)?.let { providers[it] }
            ?: defaultProviderNames[category]?.let { providers[it] }
            ?: return providers.values.firstOrNull()

        // A provider that needs a key it has not been given cannot answer, and a category with no
        // answer at all is worse than one answered by something else.
        if (chosen.requiresApiKey && !chosen.hasApiKey()) {
            providers.values.firstOrNull { !it.requiresApiKey }?.let { return it }
        }
        return chosen
    }

    /** The stored choice for [category], or null when none was ever made. */
    private fun chosenName(category: String): String? =
        settingsRepo?.getSettingsSnapshot()?.searchProviderSelections?.get(category)

    /** What answers a category the schema does not describe. */
    private fun generalProvider(): DynamicSearchProvider? {
        val generalProviders = providersByCategory["general"] ?: return null
        val name = chosenName("general") ?: defaultProviderNames["general"]
        return name?.let { generalProviders[it] } ?: generalProviders.values.firstOrNull()
    }

    fun getProvider(category: String, providerName: String): DynamicSearchProvider? {
        return providersByCategory[category]?.get(providerName)
    }

    fun getProviderNames(category: String): List<String> {
        return providersByCategory[category]?.keys?.toList() ?: emptyList()
    }

    val categories: List<String>
        get() = schema.value?.categories?.map { it.category } ?: listOf("general")

    val isInitialized: Boolean
        get() = schema.isLoaded && providersByCategory.isNotEmpty()
}
