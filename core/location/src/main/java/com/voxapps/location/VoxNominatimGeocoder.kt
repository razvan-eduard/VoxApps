package com.voxapps.location

import com.voxapps.identity.VoxRepo
import com.voxapps.logging.Logger
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

private const val TAG = "VoxNominatimGeocoder"

/** Nominatim's usage policy requires callers to identify themselves. One definition, so every
 *  caller says the same thing and a policy complaint has a single place to be answered. */
const val VOX_NOMINATIM_USER_AGENT = VoxRepo.USER_AGENT

/**
 * OpenStreetMap Nominatim reverse geocoding — free, keyless, no Google. A `User-Agent` identifying
 * the calling app is required by Nominatim's usage policy (unauthenticated requests without one
 * may be blocked). Public (not just used internally by [VoxLocationResolver]) so a settings
 * card's manual refresh button can resolve a display name directly.
 */
/** The pieces of a reverse-geocoded address a caller can ask for — one getter, a parts parameter,
 *  instead of one method per combination. Parts render in this order, comma-joined, each silently
 *  absent when the address doesn't carry it: "Ploiești, PH, RO" with everything, "Ploiești" with
 *  [CITY] alone. */
enum class LocationPart { CITY, SUBDIVISION, COUNTRY }

/** One forward-search hit. [shortName] is the suite's location format ("Ploiești, PH, RO");
 *  [fullName] is Nominatim's own display line, kept for disambiguating similar hits in a picker. */
data class VoxPlace(
    val shortName: String,
    val fullName: String,
    val lat: Double,
    val lon: Double
)

class VoxNominatimGeocoder(private val userAgent: String = VOX_NOMINATIM_USER_AGENT) {

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    fun reverseGeocode(
        lat: Double,
        lon: Double,
        parts: Set<LocationPart> = setOf(LocationPart.CITY, LocationPart.SUBDIVISION, LocationPart.COUNTRY)
    ): String? = try {
        val url = "https://nominatim.openstreetmap.org/reverse" +
            "?format=json&lat=$lat&lon=$lon&zoom=10&addressdetails=1"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .get()
            .build()
        httpClient.newCall(request).execute().use { response ->
            // Every way of coming back empty says so. Nominatim rate-limits and blocks clients by
            // policy, and both answers arrive as an ordinary non-200 — so a silent `return null`
            // here left a screen showing bare coordinates with nothing anywhere explaining why, and
            // no way to tell "blocked" from "this spot genuinely has no name".
            if (!response.isSuccessful) {
                Logger.w(TAG, "Reverse geocoding refused: HTTP ${'$'}{response.code}")
                return null
            }
            val body = response.body?.string()
            if (body == null) {
                Logger.w(TAG, "Reverse geocoding returned an empty body")
                return null
            }
            val address = JSONObject(body).optJSONObject("address")
            if (address == null) {
                Logger.w(TAG, "Reverse geocoding returned no address for ${'$'}lat, ${'$'}lon")
                return null
            }
            val resolved = listOfNotNull(
                if (LocationPart.CITY in parts) cityName(address) else null,
                if (LocationPart.SUBDIVISION in parts) subdivisionCode(address) else null,
                if (LocationPart.COUNTRY in parts) address.optStringOrNull("country_code")?.uppercase() else null
            )
            if (resolved.isEmpty()) {
                Logger.w(TAG, "None of the requested parts in the address for ${'$'}lat, ${'$'}lon")
                return null
            }
            resolved.joinToString(", ")
        }
    } catch (e: Exception) {
        Logger.w(TAG, "Reverse geocoding failed", e)
        null
    }

    /**
     * Forward geocoding — free-text place search. Callers MUST rate-limit themselves to
     * Nominatim's policy (max one request per second); the picker composable debounces for this.
     *
     * The query is folded ([foldQuery]) before it leaves the device: diacritics stripped and case
     * lowered. Nominatim matches accent-less queries reliably, while an accented query only
     * matches when the mark is the exact codepoint the map data uses — Romanian keyboards produce
     * s-cedilla (U+015F) where OpenStreetMap writes s-comma (U+0219), so the typed "Ploieşti"
     * missed the "Ploiești" the user meant. Results keep their proper spelling; only the
     * outbound query is folded.
     */
    fun search(query: String, limit: Int = 5): List<VoxPlace> = try {
        val url = "https://nominatim.openstreetmap.org/search" +
            "?format=json&q=${android.net.Uri.encode(foldQuery(query))}&limit=$limit&addressdetails=1"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .get()
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Logger.w(TAG, "Place search refused: HTTP ${'$'}{response.code}")
                return emptyList()
            }
            val body = response.body?.string() ?: return emptyList()
            val results = org.json.JSONArray(body)
            (0 until results.length()).mapNotNull { i ->
                val o = results.optJSONObject(i) ?: return@mapNotNull null
                val lat = o.optString("lat").toDoubleOrNull() ?: return@mapNotNull null
                val lon = o.optString("lon").toDoubleOrNull() ?: return@mapNotNull null
                val address = o.optJSONObject("address") ?: JSONObject()
                val name = o.optStringOrNull("name") ?: cityName(address) ?: return@mapNotNull null
                val short = listOfNotNull(
                    name,
                    subdivisionCode(address),
                    address.optStringOrNull("country_code")?.uppercase()
                ).joinToString(", ")
                VoxPlace(shortName = short, fullName = o.optString("display_name"), lat = lat, lon = lon)
            }.distinctBy { it.shortName to it.fullName }
        }
    } catch (e: Exception) {
        Logger.w(TAG, "Place search failed", e)
        emptyList()
    }

    private fun cityName(address: JSONObject): String? =
        address.optStringOrNull("city")
            ?: address.optStringOrNull("town")
            ?: address.optStringOrNull("village")
            ?: address.optStringOrNull("municipality")
            ?: address.optStringOrNull("county")

    /** The short subdivision code ("PH" for Prahova, "CA" for California) from the ISO3166-2 keys
     *  Nominatim attaches per admin level. Counties/states arrive at level 4 for most countries;
     *  the fallback levels cover the ones that structure their subdivisions a step away. */
    private fun subdivisionCode(address: JSONObject): String? =
        listOf("ISO3166-2-lvl4", "ISO3166-2-lvl3", "ISO3166-2-lvl5", "ISO3166-2-lvl6")
            .firstNotNullOfOrNull { address.optStringOrNull(it) }
            ?.substringAfter('-', "")
            ?.takeIf { it.isNotEmpty() }

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (has(key) && !isNull(key)) optString(key) else null

    companion object {
        /** Diacritic- and case-insensitive form of a search query: canonical decomposition, every
         *  combining mark dropped, lowercased. "Ploieşti", "PLOIEȘTI" and "ploiesti" all fold to
         *  the same bytes. */
        fun foldQuery(query: String): String =
            java.text.Normalizer.normalize(query, java.text.Normalizer.Form.NFD)
                .replace(COMBINING_MARKS, "")
                .lowercase()

        private val COMBINING_MARKS = Regex("\\p{Mn}+")
    }
}
