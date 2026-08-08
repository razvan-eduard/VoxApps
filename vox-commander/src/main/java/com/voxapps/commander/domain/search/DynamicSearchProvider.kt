package com.voxapps.commander.domain.search

import androidx.compose.runtime.Immutable

import com.google.gson.JsonParser
import com.google.gson.annotations.SerializedName
import com.voxapps.commander.domain.service.AuthDeclaration
import com.voxapps.commander.domain.service.ProbeSpec
import com.voxapps.logging.Logger
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
@Immutable
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
    /** A cheap URL that proves the service answers and accepts the credential, relative to
     *  [endpoint] — see ProbeSpec.from. A search endpoint is usually complete already and needs
     *  only arguments (`?q=…`), which is why most of these are queries rather than paths. */
    @SerializedName("probe_url") val probeUrl: String? = null,
    /** How the credential attaches, in the vocabulary every schema shares. The working call carries
     *  its key inline in [queryTemplate]; this says the same thing in a form the prober can use
     *  without knowing what a query template is. */
    val auth: AuthDeclaration? = null,
    val method: String = "GET",
    val requiresLocation: Boolean = false,
    val requiresApiKey: Boolean = false,
    // "http" (default) = the generic GET/POST + regex/JSON-scrape path below; "openai_chat" = a
    // direct OpenAI chat-completion call (see DynamicSearchProvider.searchViaOpenAi) — a single
    // synthesized answer rather than a list of scraped/API results.
    val providerType: String = "http",
    // Instruction sent to the model for an "openai_chat" provider, with a {query} placeholder.
    val promptTemplate: String? = null,
    // True = this provider's key comes from the shared Settings → Models API key (reused, not
    // re-entered) rather than its own per-provider key store — see SearchProviderRegistry.applySharedOpenAiKey.
    val usesSharedApiKey: Boolean = false,
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
    val userAgent: String? = null,
    // Language format: "short" (default, e.g. "ro"), "region-lang" (e.g. "ro-ro")
    val langFormat: String? = null,
    // Weather code map for Open-Meteo style numeric codes: Map<lang, Map<code, text>>
    val weatherCodeMap: Map<String, Map<String, String>>? = null
)

data class FieldMapping(
    val title: Any? = null,
    val content: Any? = null,
    val source: String = "",
    val index: Int = -1
) {
    fun resolveTitle(lang: String): String = resolveLocalized(title, lang)
    fun resolveContent(lang: String): String = resolveLocalized(content, lang)

    private fun resolveLocalized(value: Any?, lang: String): String {
        return when (value) {
            is String -> value
            is Map<*, *> -> {
                @Suppress("UNCHECKED_CAST")
                val map = value as Map<String, String>
                map[lang] ?: map["en"] ?: map.values.firstOrNull() ?: ""
            }
            else -> ""
        }
    }
}

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
    }

    val category: String get() = categoryName
    val name: String get() = def.name
    val requiresLocation: Boolean get() = def.requiresLocation
    val requiresApiKey: Boolean get() = def.requiresApiKey
    val usesSharedApiKey: Boolean get() = def.usesSharedApiKey
    val endpoint: String get() = def.endpoint

    private var apiKey: String? = null
    private var currentLang: String = "en"

    fun setApiKey(key: String?) { apiKey = key }
    fun hasApiKey(): Boolean = !apiKey.isNullOrBlank()

    /**
     * What this provider's declaration says about testing it, or null when it says nothing.
     *
     * The test used to be a real search with a dummy term — a Bucharest latitude and the word
     * "test" written into the code — and OpenAI needed an exception on top of it, since a GET
     * against chat-completions can only ever fail. Both are declarations now: the arguments that
     * make a cheap answer live beside the endpoint they belong to, and the credential attaches the
     * way the schema says it does.
     */
    fun probeSpec(lang: String = currentLang): ProbeSpec? = ProbeSpec.from(
        id = def.name,
        endpoint = def.endpoint.replace("{lang}", formatLang(lang)),
        probeUrl = def.probeUrl,
        auth = def.auth?.probeStyle() ?: ProbeSpec.AuthStyle.None,
        credential = apiKey
    )

    suspend fun search(query: String, lat: Double? = null, lon: Double? = null, lang: String = "en"): List<SearchResult> =
        withContext(Dispatchers.IO) {
            Logger.log("$name search: query='$query', category='$categoryName', lang='$lang'", tag)

            if (requiresLocation && lat == null) {
                Logger.log("$name requires location but none provided", tag)
                return@withContext emptyList()
            }

            if (def.providerType == "openai_chat") {
                return@withContext searchViaOpenAi(query)
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

    /**
     * A single OpenAI chat-completion call, returned as one synthesized [SearchResult] (title/url
     * blank, `content` = the model's answer) rather than a list of scraped/API results — this
     * provider has no real search index behind it, just the model's own training knowledge. Builds
     * its own request directly rather than going through [buildUrl]/[buildRequest], which are shaped
     * for the generic GET/POST + regex/JSON-scrape path and have no header-injection hook for the
     * `Authorization: Bearer` auth OpenAI requires.
     */
    private fun searchViaOpenAi(query: String): List<SearchResult> {
        val key = apiKey
        if (key.isNullOrBlank()) {
            Logger.log("$name: no API key configured", tag)
            return emptyList()
        }
        val prompt = (def.promptTemplate ?: "{query}").replace("{query}", query)
        return try {
            val messages = org.json.JSONArray().put(
                org.json.JSONObject().put("role", "user").put("content", prompt)
            )
            val bodyJson = org.json.JSONObject()
                .put("model", "gpt-4o-mini")
                .put("temperature", 0.3)
                .put("messages", messages)
            val request = Request.Builder()
                .url(def.endpoint)
                .header("Authorization", "Bearer $key")
                .post(bodyJson.toString().toRequestBody("application/json".toMediaType()))
                .build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Logger.log("$name error: HTTP ${response.code}", tag)
                return emptyList()
            }
            val responseBody = response.body?.string()
            if (responseBody.isNullOrBlank()) {
                Logger.log("$name: empty response", tag)
                return emptyList()
            }
            val answer = JsonParser.parseString(responseBody).asJsonObject
                .getAsJsonArray("choices")
                ?.firstOrNull()?.asJsonObject
                ?.getAsJsonObject("message")
                ?.get("content")?.asString
                ?.trim()
            if (answer.isNullOrBlank()) {
                Logger.log("$name: no answer content in response", tag)
                emptyList()
            } else {
                listOf(SearchResult(title = "", url = "", content = answer, engine = "openai"))
            }
        } catch (e: Exception) {
            Logger.log("$name search failed: ${e.message}", tag)
            emptyList()
        }
    }

    private fun formatLang(lang: String): String {
        return when (def.langFormat) {
            "region-lang" -> "${lang}-${lang}"
            else -> lang
        }
    }

    private fun buildUrl(query: String, lat: Double?, lon: Double?, lang: String = "en"): String {
        val formattedLang = formatLang(lang)
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val template = def.queryTemplate ?: ""
        val resolvedEndpoint = def.endpoint.replace("{lang}", formattedLang)
        return resolvedEndpoint + template
            .replace("{query}", encodedQuery)
            .replace("{lat}", lat?.toString() ?: "0.0")
            .replace("{lon}", lon?.toString() ?: "0.0")
            .replace("{apiKey}", apiKey ?: "")
            .replace("{lang}", formattedLang)
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

        for (mapping in def.fieldMappings ?: return emptyList()) {
            val sourceObj = resolveJsonPath(root, mapping.source) ?: continue

            // If index >= 0, each field in sourceObj is an array — pick element at index (Open-Meteo daily style)
            val fieldSource: FieldSource = if (mapping.index >= 0 && sourceObj.isJsonObject) {
                DailyElementWrapper(sourceObj.asJsonObject, mapping.index)
            } else if (sourceObj.isJsonObject) {
                JsonObjectWrapper(sourceObj.asJsonObject)
            } else continue

            val title = mapping.resolveTitle(currentLang)
            val content = applyTemplate(mapping.resolveContent(currentLang), fieldSource)
            results.add(SearchResult(title, "", content, def.name.lowercase().replace(" ", "_")))
        }

        return results
    }

    /**
     * Resolves a JSON path like "forecast.forecastday[0].day" from a root object.
     * Supports dot notation and array indexing.
     */
    private fun resolveJsonPath(root: com.google.gson.JsonElement, path: String): com.google.gson.JsonElement? {
        var current: com.google.gson.JsonElement = root
        val parts = path.split('.')
        for (part in parts) {
            // Extract array index if present (e.g. "forecastday[0]")
            val arrayMatch = Regex("^(\\w+)\\[(\\d+)]$").matchEntire(part)
            if (arrayMatch != null) {
                val key = arrayMatch.groupValues[1]
                val idx = arrayMatch.groupValues[2].toInt()
                if (!current.isJsonObject) return null
                val arr = current.asJsonObject.get(key) ?: return null
                if (!arr.isJsonArray || idx >= arr.asJsonArray.size()) return null
                current = arr.asJsonArray[idx]
            } else {
                if (!current.isJsonObject) return null
                current = current.asJsonObject.get(part) ?: return null
            }
        }
        return current
    }

    private fun parseJsonWithSimpleFields(
        root: com.google.gson.JsonObject,
        rawBody: String
    ): List<SearchResult> {
        val results = mutableListOf<SearchResult>()
        val array = navigatePath(root, def.resultPath ?: return emptyList())
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
            val fields = def.jsonFields ?: emptyMap()
            val title = getJsonField(item, fields["title"] ?: "title")
            val contentRaw = getJsonField(item, fields["content"] ?: "content")
            val urlField = getJsonField(item, fields["url"] ?: "pageid")

            val url = if (def.urlTemplate != null) {
                def.urlTemplate.replace("{url}", urlField).replace("{lang}", currentLang)
            } else urlField

            var content = if (def.stripHtml) stripTags(contentRaw) else contentRaw

            // Follow-up extract (Wikipedia intros)
            if (def.followUpExtract && def.extractEndpoint != null && title.isNotBlank()) {
                val extract = fetchExtract(title, currentLang)
                if (extract.isNotBlank()) {
                    content = truncateAtBoundary(extract, def.extractMaxChars)
                }
            }

            results.add(SearchResult(title, url, content, def.name.lowercase().replace(" ", "_")))
        }

        return results
    }

    private fun fetchExtract(title: String, lang: String = "en"): String {
        return try {
            val formattedLang = formatLang(lang)
            val encodedTitle = URLEncoder.encode(title, "UTF-8")
            val endpoint = def.extractEndpoint ?: return ""
            val url = endpoint.replace("{title}", encodedTitle).replace("{lang}", formattedLang)
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
            firstPage.get(def.extractField ?: "extract")?.asString?.let { truncateAtBoundary(it, def.extractMaxChars) } ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    // --- Helpers ---

    /**
     * Truncates text to maxChars, breaking at the last sentence boundary (. ! ?)
     * within the limit. Falls back to last word boundary, or hard cut if no space found.
     */
    private fun truncateAtBoundary(text: String, maxChars: Int): String {
        if (text.length <= maxChars) return text
        val sub = text.substring(0, maxChars)
        // Try last sentence boundary
        val sentenceEnd = sub.lastIndexOfAny(charArrayOf('.', '!', '?'))
        if (sentenceEnd > maxChars * 0.5) {
            return sub.substring(0, sentenceEnd + 1).trim()
        }
        // Fall back to last word boundary
        val wordEnd = sub.lastIndexOf(' ')
        if (wordEnd > 0) {
            return sub.substring(0, wordEnd).trim() + "…"
        }
        return sub.trim()
    }

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
                val codeMap = def.weatherCodeMap
                val langMap = codeMap?.get(currentLang) ?: codeMap?.get("en")
                langMap?.get(value) ?: value
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
        override fun get(field: String): String? {
            // Support dot notation for nested fields (e.g. condition.text)
            if (field.contains('.')) {
                val parts = field.split('.')
                var current: com.google.gson.JsonElement = obj
                for (part in parts) {
                    if (current.isJsonObject) {
                        current = current.asJsonObject.get(part) ?: return null
                    } else return null
                }
                return current.asString
            }
            return obj.get(field)?.asString
        }
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
