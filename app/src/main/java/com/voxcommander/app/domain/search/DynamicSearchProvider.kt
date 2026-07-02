package com.voxcommander.app.domain.search

import com.google.gson.JsonParser
import com.voxcommander.app.utils.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

/** A single search result from any provider. */
data class SearchResult(
    val title: String,
    val url: String,
    val content: String,
    val engine: String = ""
)

// ---------------------------------------------------------------------------
// JSON definition data classes (parsed from search_definitions.json)
// ---------------------------------------------------------------------------

data class SearchDefinitionsSchema(
    val schema_version: Int = 2,
    val categories: List<CategoryDefinition> = emptyList()
)

data class CategoryDefinition(
    val category: String,
    val defaultProvider: String = "",
    val providers: List<ProviderDefinition> = emptyList()
)

data class ProviderDefinition(
    val name: String,
    val endpoint: String,
    val method: String = "GET",
    val requiresLocation: Boolean = false,
    val requiresApiKey: Boolean = false,
    val queryTemplate: String? = null,
    val postBodyTemplate: String? = null,
    val responseType: String = "json",
    val maxResults: Int = 5,
    // HTML parsing
    val resultPattern: String? = null,
    val titleGroup: Int = 0,
    val urlGroup: Int = 0,
    val contentGroup: Int = 0,
    val urlDecode: Boolean = false,
    val stripHtml: Boolean = false,
    // JSON parsing
    val resultPath: String? = null,
    val fieldMappings: List<FieldMapping>? = null,
    val jsonFields: Map<String, String>? = null,
    val urlTemplate: String? = null,
    // JSON follow-up extract (Wikipedia)
    val followUpExtract: Boolean = false,
    val extractEndpoint: String? = null,
    val extractPath: String? = null,
    val extractField: String? = null,
    val extractMaxChars: Int = 500,
    // Value transforms
    val valueTransforms: Map<String, String>? = null,
    // Custom User-Agent (some APIs like Wikipedia require a descriptive UA)
    val userAgent: String? = null
)

data class FieldMapping(
    val title: String,
    val content: String,
    val source: String = "",
    val index: Int = -1
)

// ---------------------------------------------------------------------------
// DynamicSearchProvider — one class handles all providers from JSON
// ---------------------------------------------------------------------------

class DynamicSearchProvider(
    private val def: ProviderDefinition,
    private val categoryName: String
) {

    private val tag = "SearchProvider_${def.name}"

    companion object {
        private const val BROWSER_UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Mobile Safari/537.36"

        private val client by lazy {
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .followRedirects(true)
                .build()
        }

        private val WEATHER_CODES = mapOf(
            0 to "Clear sky", 1 to "Partly cloudy", 2 to "Partly cloudy", 3 to "Partly cloudy",
            45 to "Foggy", 48 to "Foggy",
            51 to "Drizzle", 53 to "Drizzle", 55 to "Drizzle",
            56 to "Freezing drizzle", 57 to "Freezing drizzle",
            61 to "Rain", 63 to "Rain", 65 to "Rain",
            66 to "Freezing rain", 67 to "Freezing rain",
            71 to "Snow", 73 to "Snow", 75 to "Snow", 77 to "Snow grains",
            80 to "Rain showers", 81 to "Rain showers", 82 to "Rain showers",
            85 to "Snow showers", 86 to "Snow showers",
            95 to "Thunderstorm", 96 to "Thunderstorm with hail", 99 to "Thunderstorm with hail"
        )
    }

    val category: String get() = categoryName
    val name: String get() = def.name
    val requiresLocation: Boolean get() = def.requiresLocation
    val requiresApiKey: Boolean get() = def.requiresApiKey
    val endpoint: String get() = def.endpoint

    private var apiKey: String? = null
    private var currentLang: String = "en"

    fun setApiKey(key: String?) { apiKey = key }

    suspend fun testConnection(): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = if (def.method == "GET" && def.queryTemplate != null) {
                buildUrl("test", null, null)
            } else {
                def.endpoint
            }
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", def.userAgent ?: BROWSER_UA)
                .get()
                .build()
            val response = client.newCall(request).execute()
            val ok = response.isSuccessful
            Logger.log("$name connection test: ${if (ok) "OK" else "HTTP ${response.code}"}", tag)
            ok
        } catch (e: Exception) {
            Logger.log("$name connection test failed: ${e.message}", tag)
            false
        }
    }

    suspend fun search(query: String, lat: Double? = null, lon: Double? = null, lang: String = "en"): List<SearchResult> =
        withContext(Dispatchers.IO) {
            Logger.log("$name search: query='$query', category='$categoryName', lang='$lang'", tag)

            if (requiresLocation && lat == null) {
                Logger.log("$name requires location but none provided", tag)
                return@withContext emptyList()
            }

            try {
                currentLang = lang
                val url = buildUrl(query, lat, lon, lang)
                val request = buildRequest(url, query, lat, lon)
                val response = client.newCall(request).execute()

                if (!response.isSuccessful) {
                    Logger.log("$name error: HTTP ${response.code}", tag)
                    return@withContext emptyList()
                }

                val body = response.body?.string()
                if (body.isNullOrBlank()) {
                    Logger.log("$name: empty response", tag)
                    return@withContext emptyList()
                }

                val results = when (def.responseType) {
                    "html", "xml" -> parseHtml(body)
                    "json" -> parseJson(body, query)
                    else -> {
                        Logger.log("$name: unknown responseType '${def.responseType}'", tag)
                        emptyList()
                    }
                }

                Logger.log("$name returned ${results.size} results", tag)
                results
            } catch (e: Exception) {
                Logger.log("$name search failed: ${e.message}", tag)
                emptyList()
            }
        }

    private fun buildUrl(query: String, lat: Double?, lon: Double?, lang: String = "en"): String {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val template = def.queryTemplate ?: ""
        return def.endpoint + template
            .replace("{query}", encodedQuery)
            .replace("{lat}", lat?.toString() ?: "0.0")
            .replace("{lon}", lon?.toString() ?: "0.0")
            .replace("{apiKey}", apiKey ?: "")
            .replace("{lang}", lang)
    }

    private fun buildRequest(url: String, query: String, lat: Double?, lon: Double?): Request {
        val ua = def.userAgent ?: BROWSER_UA
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", ua)
            .header("Accept", "text/html,application/xhtml+xml,application/json,*/*")
            .header("Accept-Language", "en-US,en;q=0.9")

        if (def.method == "POST" && def.postBodyTemplate != null) {
            val body = def.postBodyTemplate
                .replace("{query}", URLEncoder.encode(query, "UTF-8"))
                .replace("{lat}", lat?.toString() ?: "0.0")
                .replace("{lon}", lon?.toString() ?: "0.0")
                .replace("{apiKey}", apiKey ?: "")
            builder.post(body.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
        }

        return builder.build()
    }

    // --- HTML parsing (DuckDuckGo) ---

    private fun parseHtml(html: String): List<SearchResult> {
        val pattern = def.resultPattern ?: return emptyList()
        val matcher = Pattern.compile(pattern, Pattern.DOTALL).matcher(html)
        val results = mutableListOf<SearchResult>()

        if (!matcher.find()) {
            Logger.log("$name: regex matched 0 results. HTML preview: ${html.take(1000)}", tag)
            return emptyList()
        }

        // Reset matcher to iterate from start
        matcher.reset()

        while (matcher.find() && results.size < def.maxResults) {
            val title = safeGroup(matcher, def.titleGroup)
            val rawUrl = safeGroup(matcher, def.urlGroup)
            val content = safeGroup(matcher, def.contentGroup)

            val finalUrl = if (def.urlDecode) {
                try { java.net.URLDecoder.decode(rawUrl, "UTF-8") } catch (e: Exception) { rawUrl }
            } else rawUrl

            results.add(SearchResult(
                title = if (def.stripHtml) stripTags(title) else title,
                url = finalUrl,
                content = if (def.stripHtml) stripTags(content) else content,
                engine = def.name.lowercase().replace(" ", "_")
            ))
        }

        return results
    }

    // --- JSON parsing (Open-Meteo, Wikipedia) ---

    private fun parseJson(body: String, query: String): List<SearchResult> {
        val root = JsonParser.parseString(body).asJsonObject

        // Field-mapping mode (Open-Meteo): templates with {field} placeholders
        if (def.fieldMappings != null) {
            return parseJsonWithFieldMappings(root)
        }

        // Simple field extraction mode (Wikipedia search results)
        if (def.jsonFields != null && def.resultPath != null) {
            return parseJsonWithSimpleFields(root, body)
        }

        return emptyList()
    }

    private fun parseJsonWithFieldMappings(root: com.google.gson.JsonObject): List<SearchResult> {
        val results = mutableListOf<SearchResult>()

        for (mapping in def.fieldMappings!!) {
            val sourceObj = if (mapping.source == "current") {
                root.getAsJsonObject("current")
            } else if (mapping.source == "daily") {
                root.getAsJsonObject("daily")
            } else null ?: continue

            // For array sources (daily), pick the element at index
            val fieldSource = if (mapping.index >= 0) {
                // It's an array — we need to get element at index from each field
                DailyElementWrapper(sourceObj, mapping.index)
            } else {
                JsonObjectWrapper(sourceObj)
            }

            val title = mapping.title
            val content = applyTemplate(mapping.content, fieldSource)
            results.add(SearchResult(title, "", content, def.name.lowercase().replace(" ", "_")))
        }

        return results
    }

    private fun parseJsonWithSimpleFields(
        root: com.google.gson.JsonObject,
        rawBody: String
    ): List<SearchResult> {
        val results = mutableListOf<SearchResult>()
        val array = navigatePath(root, def.resultPath!!)
        if (array == null || !array.isJsonArray) return emptyList()

        // Flatten nested topic groups (DuckDuckGo RelatedTopics has mixed entries:
        // direct results with Text/FirstURL, and topic groups with a Topics sub-array)
        val flatItems = mutableListOf<com.google.gson.JsonObject>()
        for (i in 0 until array.asJsonArray.size()) {
            val elem = array.asJsonArray[i]
            if (!elem.isJsonObject) continue
            val obj = elem.asJsonObject
            if (obj.has("Topics") && obj.get("Topics").isJsonArray) {
                for (j in 0 until obj.getAsJsonArray("Topics").size()) {
                    val sub = obj.getAsJsonArray("Topics")[j]
                    if (sub.isJsonObject) flatItems.add(sub.asJsonObject)
                }
            } else {
                flatItems.add(obj)
            }
        }

        val effectiveMaxResults = if (def.maxResults > 0) def.maxResults else 5
        for (i in 0 until minOf(flatItems.size, effectiveMaxResults)) {
            val item = flatItems[i]
            val title = getJsonField(item, def.jsonFields!!["title"] ?: "title")
            val contentRaw = getJsonField(item, def.jsonFields["content"] ?: "content")
            val urlField = getJsonField(item, def.jsonFields["url"] ?: "pageid")

            val url = if (def.urlTemplate != null) {
                def.urlTemplate.replace("{url}", urlField).replace("{lang}", currentLang)
            } else urlField

            var content = if (def.stripHtml) stripTags(contentRaw) else contentRaw

            // Follow-up extract (Wikipedia intros)
            if (def.followUpExtract && def.extractEndpoint != null && title.isNotBlank()) {
                val extract = fetchExtract(title, currentLang)
                if (extract.isNotBlank()) {
                    content = extract.take(def.extractMaxChars)
                }
            }

            results.add(SearchResult(title, url, content, def.name.lowercase().replace(" ", "_")))
        }

        return results
    }

    private fun fetchExtract(title: String, lang: String = "en"): String {
        return try {
            val encodedTitle = URLEncoder.encode(title, "UTF-8")
            val url = def.extractEndpoint!!.replace("{title}", encodedTitle).replace("{lang}", lang)
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", def.userAgent ?: "VoxCommander/1.0 (Android Voice Assistant)")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return ""

            val body = response.body?.string() ?: return ""
            val root = JsonParser.parseString(body).asJsonObject
            val pagesObj = navigatePath(root, def.extractPath ?: "") ?: return ""
            if (!pagesObj.isJsonObject) return ""

            val pages = pagesObj.asJsonObject
            if (pages.size() == 0) return ""

            val firstPage = pages.getAsJsonObject(pages.keySet().first())
            firstPage.get(def.extractField ?: "extract")?.asString?.take(def.extractMaxChars) ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    // --- Helpers ---

    private fun safeGroup(matcher: java.util.regex.Matcher, group: Int): String {
        return try { matcher.group(group) ?: "" } catch (e: Exception) { "" }
    }

    private fun stripTags(html: String): String =
        html.replace(Regex("<[^>]+>"), "")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#x27;", "'")
            .replace("&#39;", "'")
            .trim()

    private fun navigatePath(root: com.google.gson.JsonObject, path: String): com.google.gson.JsonElement? {
        var current: com.google.gson.JsonElement = root
        for (key in path.split(".")) {
            if (key.isBlank()) continue
            if (!current.isJsonObject) return null
            current = current.asJsonObject.get(key) ?: return null
        }
        return current
    }

    private fun getJsonField(obj: com.google.gson.JsonObject, field: String): String {
        return obj.get(field)?.asString ?: ""
    }

    private fun applyTemplate(template: String, source: FieldSource): String {
        var result = template
        val placeholderRegex = Regex("\\{([^}]+)\\}")
        // Build reverse map: weather_code_text → weather_code
        val transformReverse = mutableMapOf<String, String>()
        def.valueTransforms?.forEach { (srcField, dstName) ->
            transformReverse[dstName] = srcField
        }
        result = placeholderRegex.replace(result) { match ->
            val placeholder = match.groupValues[1]
            // Check if this placeholder is a transformed field (e.g. weather_code_text → weather_code)
            val sourceField = transformReverse[placeholder] ?: placeholder
            val value = source.get(sourceField) ?: ""
            // Apply transform if one was defined for this field
            if (transformReverse.containsKey(placeholder) && sourceField == "weather_code") {
                WEATHER_CODES[value.toIntOrNull() ?: 0] ?: value
            } else {
                value
            }
        }
        return result
    }

    // --- Wrapper interfaces for field access ---

    private interface FieldSource {
        fun get(field: String): String?
    }

    private class JsonObjectWrapper(private val obj: com.google.gson.JsonObject) : FieldSource {
        override fun get(field: String): String? = obj.get(field)?.asString
    }

    private class DailyElementWrapper(
        private val dailyObj: com.google.gson.JsonObject,
        private val index: Int
    ) : FieldSource {
        override fun get(field: String): String? {
            val elem = dailyObj.get(field) ?: return null
            if (elem.isJsonArray) {
                val arr = elem.asJsonArray
                return if (index < arr.size()) arr[index].asString else null
            }
            // Field is a primitive, not an array — return as-is
            return elem.asString
        }
    }
}
