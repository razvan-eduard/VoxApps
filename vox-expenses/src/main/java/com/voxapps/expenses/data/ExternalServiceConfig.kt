package com.voxapps.expenses.data

import android.content.Context
import com.voxapps.logging.Logger
import org.json.JSONObject

private const val TAG = "ExternalServiceConfig"

/** One entry from the repo-root `external_services.json` (copied to assets at build time). */
data class ExternalService(
    val id: String,
    val name: String,
    val docsUrl: String,
    val baseEndpoint: String,
    val requiresApiKey: Boolean,
    val category: String
)

/**
 * Reads `external_services.json` from assets — the same repo-root JSON, copied at build time, that
 * vox-commander's config ecosystem also lists (see `docs/TECHNICAL_DOCUMENTATION.md` §17). Expenses
 * only needs the "exchangerate_api" entry today, so this is deliberately minimal rather than a full
 * registry — a second consumer would be the point to generalize it.
 */
object ExternalServiceConfig {
    private const val ASSET_FILE = "external_services.json"
    private const val EXCHANGE_RATE_API_ID = "exchangerate_api"

    fun exchangeRateService(context: Context): ExternalService? {
        return try {
            val json = context.assets.open(ASSET_FILE).bufferedReader().use { it.readText() }
            val root = JSONObject(json)
            val services = root.optJSONArray("services") ?: return null
            for (i in 0 until services.length()) {
                val o = services.optJSONObject(i) ?: continue
                if (o.optString("id") == EXCHANGE_RATE_API_ID) {
                    return ExternalService(
                        id = o.optString("id"),
                        name = o.optString("name"),
                        docsUrl = o.optString("docsUrl"),
                        baseEndpoint = o.optString("baseEndpoint"),
                        requiresApiKey = o.optBoolean("requiresApiKey", false),
                        category = o.optString("category")
                    )
                }
            }
            null
        } catch (e: Exception) {
            Logger.w(TAG, "Failed to read $ASSET_FILE: ${e.message}")
            null
        }
    }
}
