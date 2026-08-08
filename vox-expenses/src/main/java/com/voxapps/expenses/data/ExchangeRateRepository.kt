package com.voxapps.expenses.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.voxapps.expenses.data.preferences.DataStoreProvider
import com.voxapps.logging.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

private const val TAG = "ExchangeRateRepository"
private val STALE_AFTER_MILLIS = TimeUnit.HOURS.toMillis(24)

/**
 * Converts an expense's [com.voxapps.expenses.data.Expense.totalAmount] from its own currency to the
 * user's home currency, for reporting-time aggregation only — never per-expense. Rates are cached in
 * DataStore with a fetch timestamp and only re-fetched when stale (>24h) *and* a report actually needs
 * a cross-currency conversion, keeping request volume low (well within the free tier). Uses OkHttp,
 * the client already established elsewhere in this monorepo (e.g. vox-commander's
 * DynamicSearchProvider) rather than introducing a second HTTP stack.
 */
class ExchangeRateRepository(private val context: Context) {

    private val dataStore = DataStoreProvider.get(context)

    private object Keys {
        val CACHED_BASE = stringPreferencesKey("exchange_rate_cached_base")
        val CACHED_RATES_JSON = stringPreferencesKey("exchange_rate_cached_rates_json")
        val CACHED_AT = longPreferencesKey("exchange_rate_cached_at")
    }

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    sealed interface RatesResult {
        data class Success(val rates: Map<String, Double>, val fetchedAt: Long) : RatesResult
        data class Error(val message: String) : RatesResult
    }

    /**
     * Rates keyed by currency code, relative to [homeCurrency] as the base (`rates["EUR"] = 0.92`
     * means 1 [homeCurrency] = 0.92 EUR) — i.e. exactly exchangerate-api.com's `conversion_rates`
     * shape for that base. Serves the cache unless it's stale or for a different base currency.
     */
    suspend fun getRates(homeCurrency: String, forceRefresh: Boolean = false): RatesResult = withContext(Dispatchers.IO) {
        val prefs = dataStore.data.first()
        val cachedBase = prefs[Keys.CACHED_BASE]
        val cachedAt = prefs[Keys.CACHED_AT] ?: 0L
        val cachedJson = prefs[Keys.CACHED_RATES_JSON]
        val isFresh = cachedBase == homeCurrency && cachedJson != null &&
            (System.currentTimeMillis() - cachedAt) < STALE_AFTER_MILLIS

        if (!forceRefresh && isFresh) {
            return@withContext RatesResult.Success(decodeRates(cachedJson!!), cachedAt)
        }

        val apiKey = ExchangeRateApiKeyStore.get(context)
            ?: return@withContext RatesResult.Error("No exchange-rate API key configured")
        val service = ExternalServiceConfig.exchangeRateService(context)
            ?: return@withContext RatesResult.Error("external_services.json missing exchangerate_api entry")

        try {
            val url = "${service.serviceUrl}/$apiKey/latest/$homeCurrency"
            val request = Request.Builder().url(url).get().build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext RatesResult.Error("HTTP ${response.code}")
                }
                val body = response.body?.string() ?: return@withContext RatesResult.Error("Empty response")
                val root = JSONObject(body)
                if (root.optString("result") != "success") {
                    return@withContext RatesResult.Error(root.optString("error-type", "Unknown API error"))
                }
                val ratesObj = root.optJSONObject("conversion_rates")
                    ?: return@withContext RatesResult.Error("Response missing conversion_rates")
                val rates = ratesObj.keys().asSequence().associateWith { ratesObj.optDouble(it) }
                val now = System.currentTimeMillis()
                dataStore.edit {
                    it[Keys.CACHED_BASE] = homeCurrency
                    it[Keys.CACHED_RATES_JSON] = JSONObject(rates).toString()
                    it[Keys.CACHED_AT] = now
                }
                RatesResult.Success(rates, now)
            }
        } catch (e: Exception) {
            Logger.w(TAG, "Exchange-rate fetch failed: ${e.message}")
            RatesResult.Error(e.message ?: "Unknown error")
        }
    }

    /**
     * Converts [amount] from [fromCurrency] into [homeCurrency]. Same-currency is a no-op (no
     * network call needed regardless of cache state). Returns null if conversion isn't possible
     * (no API key configured, fetch failed, or the currency code isn't in the response).
     */
    suspend fun convertToHome(amount: Double, fromCurrency: String, homeCurrency: String): Double? {
        if (fromCurrency.equals(homeCurrency, ignoreCase = true)) return amount
        val result = getRates(homeCurrency)
        val rates = (result as? RatesResult.Success)?.rates ?: return null
        val rate = rates[fromCurrency.uppercase()] ?: return null
        if (rate == 0.0) return null
        return amount / rate
    }

    private fun decodeRates(json: String): Map<String, Double> = try {
        val o = JSONObject(json)
        o.keys().asSequence().associateWith { o.optDouble(it) }
    } catch (e: Exception) {
        emptyMap()
    }
}
