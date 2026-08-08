package com.voxapps.commander.domain.search

import android.content.Context
import com.google.gson.Gson
import com.voxapps.commander.data.preferences.SettingsRepository
import com.voxapps.logging.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URL

object SearchProviderRegistry {

    private const val TAG = "SearchProviderRegistry"
    private const val LOCAL_FILE_NAME = "search_definitions.json"

    private val gson = Gson()

    /** Assigned on Main in init(), read on Dispatchers.IO throughout fetchRemote. */
    @Volatile private var appContext: Context? = null
    /** Held so the chosen provider can be *read* where it is used rather than pushed in from
     *  startup and from the settings screen — the API keys below are pushed, and every push site is
     *  another place that can be forgotten when a third one appears. */
    @Volatile private var settingsRepo: SettingsRepository? = null
    /** Written on Main (init) and on Dispatchers.IO (fetchRemote); read from Compose on Main. */
    @Volatile private var cachedSchema: SearchDefinitionsSchema? = null
    /** Rebuilt on whichever thread last refreshed the schema (Main via init, IO via fetchRemote);
     *  read on Main from the search settings UI and on IO from SearchIntentHandler. */
    @Volatile private var providersByCategory: Map<String, Map<String, DynamicSearchProvider>> = emptyMap()
    /** Same write/read threads as providersByCategory — the two are rebuilt together. */
    @Volatile private var defaultProviderNames: Map<String, String> = emptyMap()

    fun init(context: Context, repo: SettingsRepository? = null) {
        appContext = context.applicationContext
        settingsRepo = repo
        loadFromFilesDir()
    }

    suspend fun fetchRemote(repo: SettingsRepository, force: Boolean = false): Boolean =
        withContext(Dispatchers.IO) {
            if (!force && cachedSchema != null) return@withContext true

            val baseUrl = repo.getSettingsSnapshot().modelRepoBaseUrl
            val rawUrlBase = if (baseUrl.contains("github.com") && !baseUrl.contains("raw.githubusercontent.com")) {
                baseUrl.replace("github.com", "raw.githubusercontent.com").removeSuffix("/") + "/main/search_definitions.json"
            } else {
                if (baseUrl.endsWith("/")) "${baseUrl}search_definitions.json" else "$baseUrl/search_definitions.json"
            }

            val rawUrl = "$rawUrlBase?t=${System.currentTimeMillis()}"
            Logger.log("Fetching remote search definitions from: $rawUrl", TAG)

            return@withContext try {
                val jsonText = URL(rawUrl).readText()
                val schema = gson.fromJson(jsonText, SearchDefinitionsSchema::class.java)
                if (schema != null && schema.categories.isNotEmpty()) {
                    // Never downgrade — if assets has higher schema_version, use assets
                    val assetVersion = getAssetSchemaVersion()
                    if (assetVersion > schema.schema_version) {
                        Logger.log("Remote schema_version=${schema.schema_version} < assets=$assetVersion — skipping remote (no downgrade)", TAG)
                        ensureLocalFile()
                        loadFromFilesDir()
                    } else {
                        saveLocalFile(jsonText)
                        cachedSchema = schema
                        rebuildProviders()
                        Logger.log("Remote search definitions parsed. Categories: ${schema.categories.map { "${it.category}(${it.providers.size})" }}", TAG)
                    }
                    true
                } else {
                    Logger.log("Failed to parse remote search definitions", TAG)
                    false
                }
            } catch (e: Exception) {
                Logger.log("Remote search definitions fetch failed: ${e.message}. Falling back to assets.", TAG)
                ensureLocalFile()
                loadFromFilesDir()
                cachedSchema != null
            }
        }

    private fun getAssetSchemaVersion(): Int {
        val ctx = appContext ?: return 0
        return try {
            ctx.assets.open(LOCAL_FILE_NAME).use { input ->
                val text = input.readBytes().decodeToString()
                gson.fromJson(text, SearchDefinitionsSchema::class.java)?.schema_version ?: 0
            }
        } catch (e: Exception) { 0 }
    }

    /**
     * Copies search_definitions.json from assets to filesDir if local is missing
     * or assets has a newer schema_version. Called as fallback when repo download fails.
     */
    private fun ensureLocalFile() {
        val ctx = appContext ?: return
        val localFile = java.io.File(ctx.filesDir, LOCAL_FILE_NAME)

        val assetText = try {
            ctx.assets.open(LOCAL_FILE_NAME).use { it.readBytes().decodeToString() }
        } catch (e: Exception) {
            Logger.log("Failed to read search_definitions.json from assets: ${e.message}", TAG)
            return
        }

        val localVersion = if (localFile.exists()) {
            try {
                val localSchema = gson.fromJson(localFile.readText(), SearchDefinitionsSchema::class.java)
                localSchema?.schema_version ?: 0
            } catch (e: Exception) { 0 }
        } else 0

        val assetVersion = try {
            val assetSchema = gson.fromJson(assetText, SearchDefinitionsSchema::class.java)
            assetSchema?.schema_version ?: 0
        } catch (e: Exception) { 0 }

        if (!localFile.exists() || assetVersion > localVersion) {
            try {
                localFile.writeText(assetText)
                Logger.log("Copied search_definitions.json from assets to filesDir (asset v$assetVersion > local v$localVersion)", TAG)
            } catch (e: Exception) {
                Logger.log("Failed to copy search_definitions.json from assets: ${e.message}", TAG)
            }
        }
    }

    private fun loadFromFilesDir() {
        val ctx = appContext ?: return
        val localFile = java.io.File(ctx.filesDir, LOCAL_FILE_NAME)
        if (!localFile.exists()) {
            // No local file — copy from assets before loading
            ensureLocalFile()
            if (!localFile.exists()) return
        }

        try {
            val jsonText = localFile.readText()
            val schema = gson.fromJson(jsonText, SearchDefinitionsSchema::class.java)
            if (schema != null && schema.categories.isNotEmpty()) {
                cachedSchema = schema
                Logger.log("Loaded search_definitions.json from filesDir. Categories: ${schema.categories.map { it.category }}", TAG)
                rebuildProviders()
            } else {
                Logger.log("Local search_definitions.json has empty categories. Overwriting from assets.", TAG)
                throw com.google.gson.JsonParseException("Empty categories — likely outdated schema")
            }
        } catch (e: Exception) {
            Logger.log("Failed to parse local search_definitions.json: ${e.message}. Recovering from assets.", TAG)
            try {
                ctx.assets.open(LOCAL_FILE_NAME).use { input ->
                    localFile.outputStream().use { output -> input.copyTo(output) }
                }
                val freshText = localFile.readText()
                Logger.log("Assets content preview: ${freshText.take(200)}", TAG)
                val freshSchema = gson.fromJson(freshText, SearchDefinitionsSchema::class.java)
                if (freshSchema != null && freshSchema.categories.isNotEmpty()) {
                    cachedSchema = freshSchema
                    rebuildProviders()
                    Logger.log("Recovered search_definitions.json from assets. Categories: ${freshSchema.categories.map { it.category }}", TAG)
                } else {
                    Logger.log("Assets file also has empty categories!", TAG)
                }
            } catch (e2: Exception) {
                Logger.log("Failed to recover search_definitions.json from assets: ${e2.message}", TAG)
            }
        }
    }

    private fun saveLocalFile(jsonText: String) {
        val ctx = appContext ?: return
        try {
            java.io.File(ctx.filesDir, LOCAL_FILE_NAME).writeText(jsonText)
            Logger.log("Saved updated search_definitions.json to filesDir", TAG)
        } catch (e: Exception) {
            Logger.log("Failed to save search_definitions.json: ${e.message}", TAG)
        }
    }

    private fun rebuildProviders() {
        val schema = cachedSchema ?: return
        val newProviders = mutableMapOf<String, Map<String, DynamicSearchProvider>>()
        val newDefaults = mutableMapOf<String, String>()

        for (catDef in schema.categories) {
            val providerMap = mutableMapOf<String, DynamicSearchProvider>()
            for (provDef in catDef.providers) {
                providerMap[provDef.name] = DynamicSearchProvider(provDef, catDef.category)
            }
            newProviders[catDef.category] = providerMap
            newDefaults[catDef.category] = catDef.defaultProvider.ifBlank {
                catDef.providers.firstOrNull()?.name ?: ""
            }
        }

        providersByCategory = newProviders
        defaultProviderNames = newDefaults
        Logger.log("Rebuilt search providers: ${newProviders.map { "${it.key}=[${it.value.keys}]" }}", TAG)
    }

    fun applyApiKeys(apiKeys: Map<String, String>) {
        for ((_, providerMap) in providersByCategory) {
            for ((name, provider) in providerMap) {
                if (!provider.usesSharedApiKey) provider.setApiKey(apiKeys[name])
            }
        }
        Logger.log("Applied API keys to search providers: ${apiKeys.keys}", TAG)
    }

    /**
     * Pushes the shared Settings → Models API key into every provider that opted into reusing it
     * (`usesSharedApiKey = true` in its JSON definition, e.g. the OpenAI general/knowledge provider)
     * instead of asking the user to paste the same key a second time into a provider-specific field.
     * Must be re-called after [rebuildProviders] runs (e.g. after [fetchRemote]), since that replaces
     * every `DynamicSearchProvider` instance — same reason [applyApiKeys] gets re-called there too.
     */
    fun applySharedOpenAiKey(key: String?) {
        var count = 0
        for ((_, providerMap) in providersByCategory) {
            for (provider in providerMap.values) {
                if (provider.usesSharedApiKey) {
                    provider.setApiKey(key)
                    count++
                }
            }
        }
        Logger.log("Applied shared OpenAI key to $count provider(s)", TAG)
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
        get() = cachedSchema?.categories?.map { it.category } ?: listOf("general")

    val isInitialized: Boolean
        get() = cachedSchema != null && providersByCategory.isNotEmpty()
}
