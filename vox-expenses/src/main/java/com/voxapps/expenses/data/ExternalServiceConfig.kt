package com.voxapps.expenses.data

import android.content.Context
import com.google.gson.annotations.SerializedName
import com.voxapps.services.ProbeSpec
import com.voxapps.services.RemoteSchema

/**
 * One entry from `external_services.json` — a service this app talks to, described the way every
 * other declared service in VoxApps is.
 *
 * Every field has a default, and that is load-bearing rather than tidy: Gson skips the Kotlin
 * constructor entirely for a class with a required parameter, and then *every* absent field arrives
 * null regardless of what its type says.
 */
data class ExternalService(
    val id: String = "",
    val name: String = "",
    val category: String = "",
    /** Where the service lives. `baseEndpoint` is the older spelling, still read, since this file
     *  can be served from a repository that has not caught up. */
    val endpoint: String = "",
    @SerializedName("baseEndpoint") val legacyBaseEndpoint: String? = null,
    /** A cheap URL that proves the service answers and accepts the key, relative to [endpoint].
     *  This one carries `{key}` because the credential travels in the path. */
    @SerializedName("probe_url") val probeUrl: String? = null,
    /** The call that returns rates, relative to [endpoint]. `{key}` is the credential and `{base}`
     *  the currency everything is quoted against — written where the service expects them, since
     *  one provider takes its key in the path and another in a query parameter. */
    @SerializedName("rates_url") val ratesUrl: String? = null,
    /** Where the rates live in the answer: `conversion_rates` for one provider, `rates` for most. */
    @SerializedName("rates_path") val ratesPath: String? = null,
    /** For a provider that wraps its answer in a status: the field, and what it says when all is
     *  well. Absent means the HTTP status was the whole answer. */
    @SerializedName("success_field") val successField: String? = null,
    @SerializedName("success_value") val successValue: String? = null,

    @SerializedName("requires_api_key") val requiresApiKey: Boolean = false,
    @SerializedName("requiresApiKey") val legacyRequiresApiKey: Boolean = false,
    /** Where the user obtains a key. `docsUrl` is the older spelling. */
    @SerializedName("api_key_url") val apiKeyUrl: String? = null,
    @SerializedName("docsUrl") val legacyDocsUrl: String? = null
) {
    /** The endpoint under either spelling. */
    val serviceUrl: String get() = endpoint.ifBlank { legacyBaseEndpoint.orEmpty() }

    val needsApiKey: Boolean get() = requiresApiKey || legacyRequiresApiKey

    val helpUrl: String? get() = apiKeyUrl ?: legacyDocsUrl

    /**
     * The URL that returns rates for [base], with the credential where this provider wants it.
     *
     * Null when the service declares no `rates_url`: something is declared here that this app does
     * not know how to ask, which is better than guessing a shape and failing at the parse.
     */
    fun ratesUrl(apiKey: String, base: String): String? {
        val template = ratesUrl?.takeIf { it.isNotBlank() } ?: return null
        // Resolved by the same rule as a probe — one syntax, one meaning, whichever field carries it.
        return ProbeSpec.resolve(
            serviceUrl,
            template.replace("{key}", apiKey).replace("{base}", base.uppercase())
        )
    }

    /** How to test this service with [apiKey], or null when it declares nothing to reach. */
    fun probeSpec(apiKey: String?): ProbeSpec? =
        ProbeSpec.from(id = id, endpoint = serviceUrl, probeUrl = probeUrl, credential = apiKey)
}

data class ExternalServicesSchema(
    @SerializedName("schema_version") val schemaVersion: Int = 1,
    val services: List<ExternalService> = emptyList()
)

/**
 * The services this app depends on but does not own.
 *
 * Loaded through the same [RemoteSchema] as Commander's catalogues, which means the entry can be
 * corrected from the repository rather than by shipping a release — the rate provider's endpoint and
 * the URL where a key is obtained are that provider's to change, not ours. It used to be read
 * straight from assets on every call, so a moved endpoint meant an app update.
 */
object ExternalServiceConfig {

    private const val TAG = "ExternalServiceConfig"
    private const val EXCHANGE_RATE_API_ID = "exchangerate_api"
    private const val CURRENCY_EXCHANGE = "currency_exchange"

    private val schema = RemoteSchema(
        fileName = "external_services.json",
        type = ExternalServicesSchema::class.java,
        usable = { it.services.any { service -> service.serviceUrl.isNotBlank() } },
        tag = TAG
    )

    fun init(context: Context) = schema.init(context)

    /** Asks the repository for a newer copy. Nothing else in this app decides to. */
    suspend fun refresh(baseUrl: String) = schema.refresh(baseUrl)

    fun all(): List<ExternalService> = schema.value?.services.orEmpty()

    fun byId(id: String): ExternalService? = all().firstOrNull { it.id == id }

    /**
     * The rate provider, loading the bundled copy first if the app never initialised the registry.
     *
     * The [context] fallback keeps every existing caller working unchanged — this used to read the
     * asset on each call, so callers hand one over anyway.
     */
    fun exchangeRateService(context: Context): ExternalService? {
        if (!schema.isLoaded) init(context)
        return byId(EXCHANGE_RATE_API_ID) ?: currencyServices(context).firstOrNull()
    }

    /**
     * Every declared currency service, for a screen that lets the user choose between them.
     *
     * The category is the declaration's own word for what it is, so adding a provider is a schema
     * edit — nothing here lists them.
     */
    fun currencyServices(context: Context): List<ExternalService> {
        if (!schema.isLoaded) init(context)
        return all().filter { it.category == CURRENCY_EXCHANGE }
    }

    /** The chosen service, falling back to the first declared one when the choice is empty or gone
     *  — a repository can stop serving a provider the user had selected. */
    fun chosenCurrencyService(context: Context, chosenId: String): ExternalService? {
        val services = currencyServices(context)
        return services.firstOrNull { it.id == chosenId } ?: services.firstOrNull()
    }
}
