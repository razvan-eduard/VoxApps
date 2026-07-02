package com.voxcommander.app.domain.intent.handler

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.voxcommander.app.utils.Logger
import com.voxcommander.app.utils.NetworkMonitor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Searches for videos using a Piped API instance and opens the first result
 * in LibreTube (which intercepts YouTube URLs and plays them).
 *
 * This enables "play direct" functionality: search → get first video → play.
 */
object PipedSearchHelper {

    private const val TAG = "PipedSearch"

    /** Hardcoded list of popular Piped API instances. */
    val PIPED_INSTANCES = listOf(
        "https://inv.thepixora.com",
        "https://pipedapi.kavin.rocks",
        "https://pipedapi.adminforge.de",
        "https://api.piped.private.coffee"
    )

    /** Region codes for Piped search. null = system default. */
    val PIPED_REGIONS = listOf(
        null to "System Default",
        "RO" to "Romania",
        "US" to "United States",
        "GB" to "United Kingdom",
        "DE" to "Germany",
        "FR" to "France",
        "IT" to "Italy",
        "ES" to "Spain",
        "NL" to "Netherlands",
        "CA" to "Canada",
        "AU" to "Australia",
        "JP" to "Japan",
        "KR" to "South Korea",
        "IN" to "India",
        "BR" to "Brazil",
        "MX" to "Mexico",
        "RU" to "Russia",
        "PL" to "Poland",
        "SE" to "Sweden",
        "NO" to "Norway",
        "FI" to "Finland",
        "DK" to "Denmark",
        "AT" to "Austria",
        "BE" to "Belgium",
        "CH" to "Switzerland",
        "CZ" to "Czech Republic",
        "HU" to "Hungary",
        "PT" to "Portugal",
        "GR" to "Greece",
        "TR" to "Turkey",
        "UA" to "Ukraine",
        "BG" to "Bulgaria",
        "RS" to "Serbia",
        "IL" to "Israel",
        "AE" to "UAE",
        "SA" to "Saudi Arabia",
        "CN" to "China",
        "HK" to "Hong Kong",
        "TW" to "Taiwan",
        "TH" to "Thailand",
        "ID" to "Indonesia",
        "MY" to "Malaysia",
        "PH" to "Philippines",
        "SG" to "Singapore",
        "VN" to "Vietnam",
        "AR" to "Argentina",
        "CL" to "Chile",
        "CO" to "Colombia",
        "PE" to "Peru",
        "ZA" to "South Africa",
        "EG" to "Egypt",
        "NG" to "Nigeria",
        "KE" to "Kenya",
        "MA" to "Morocco"
    )

    private var selectedInstance: String = PIPED_INSTANCES[0]
    private var pipedRegion: String? = null

    fun setPipedApiUrl(url: String?) {
        selectedInstance = url?.takeIf { it.isNotBlank() } ?: PIPED_INSTANCES[0]
    }

    fun setPipedRegion(region: String?) {
        pipedRegion = region?.takeIf { it.isNotBlank() }
    }

    private val pipedInstances: List<String>
        get() = listOf(selectedInstance)

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    /**
     * Tests connectivity to a Piped API instance by hitting /health.
     * Returns true if the instance responds with 200 OK.
     */
    suspend fun testInstance(url: String): Boolean = withContext(Dispatchers.IO) {
        val baseUrl = url.trimEnd('/')
        try {
            val request = Request.Builder().url("$baseUrl/health").build()
            client.newCall(request).execute().use { response ->
                val ok = response.isSuccessful
                Logger.log("Piped instance $baseUrl health check: ${response.code} ${if (ok) "OK" else "FAIL"}", TAG)
                ok
            }
        } catch (e: Exception) {
            Logger.log("Piped instance $baseUrl health check failed: ${e.message}", TAG)
            false
        }
    }

    /**
     * Searches for a query on Piped API, gets the first video ID,
     * and opens it as a youtu.be URL that LibreTube will intercept and play.
     *
     * Returns true if a video was found and the intent was launched.
     */
    suspend fun searchAndPlay(context: Context, query: String, targetPackage: String? = null): Boolean = withContext(Dispatchers.IO) {
        if (!NetworkMonitor.isOnline) {
            Logger.log("Piped search: no internet connection, skipping", TAG)
            return@withContext false
        }
        val videoId = searchFirstVideoId(query)
        if (videoId == null) {
            Logger.log("Piped search returned no results for: $query", TAG)
            return@withContext false
        }

        Logger.log("Piped search found video: $videoId for query: $query", TAG)

        // Open as youtu.be URL — target app intercepts this and plays directly
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse("https://youtu.be/$videoId")
            if (targetPackage != null) setPackage(targetPackage)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        return@withContext try {
            context.startActivity(intent)
            Logger.log("Launched ${targetPackage ?: "default"} with video: $videoId", TAG)
            true
        } catch (e: Exception) {
            Logger.log("Failed to launch ${targetPackage ?: "default"}: ${e.message}", TAG)
            false
        }
    }

    /**
     * Searches the selected Piped instance (or falls back to defaults) and returns the first video ID.
     */
    private fun searchFirstVideoId(query: String): String? {
        for (instance in pipedInstances) {
            try {
                val result = searchOnInstance(instance, query)
                if (result != null) {
                    Logger.log("Piped search succeeded on $instance", TAG)
                    return result
                }
            } catch (e: Exception) {
                Logger.log("Piped instance $instance failed: ${e.message}", TAG)
            }
        }
        return null
    }

    private val gson = Gson()

    private data class PipedSearchItem(
        val videoId: String? = null,
        val url: String? = null,
        val title: String? = null,
        val author: String? = null
    )

    private fun searchOnInstance(instance: String, query: String): String? {
        val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
        val url = buildString {
            append(instance)
            append("/api/v1/search?q=")
            append(encodedQuery)
            append("&type=video")
            append("&sort_by=relevance")
            if (pipedRegion != null) {
                append("&region=")
                append(pipedRegion)
            }
        }
        val request = Request.Builder().url(url).build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body?.string() ?: return null

            // Piped API returns a JSON array directly: [{...}, {...}]
            // Some instances wrap in {"items": [...]}
            val items: List<PipedSearchItem> = try {
                val element = JsonParser.parseString(body)
                if (element.isJsonArray) {
                    gson.fromJson(element, Array<PipedSearchItem>::class.java).toList()
                } else if (element.isJsonObject) {
                    val itemsArray = element.asJsonObject.get("items")
                    if (itemsArray != null && itemsArray.isJsonArray) {
                        gson.fromJson(itemsArray, Array<PipedSearchItem>::class.java).toList()
                    } else emptyList()
                } else emptyList()
            } catch (e: Exception) {
                Logger.log("Piped parse error on $instance: ${e.message}", TAG)
                emptyList()
            }

            for (item in items) {
                if (!item.videoId.isNullOrBlank()) return item.videoId
                if (!item.url.isNullOrBlank()) {
                    extractVideoId(item.url)?.let { return it }
                }
            }
        }
        return null
    }

    private fun extractVideoId(url: String): String? {
        return try {
            val fullUrl = if (url.startsWith("http")) url else "https://piped.video$url"
            fullUrl.toHttpUrlOrNull()?.queryParameter("v")
        } catch (e: Exception) {
            null
        }
    }
}
