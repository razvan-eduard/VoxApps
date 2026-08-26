package com.voxapps.vision.domain.liveview

import com.voxapps.textmatch.extract.LineEntities
import org.json.JSONArray
import org.json.JSONObject

/**
 * What the person has decided about each kind of recognised text, and the kinds they invented.
 *
 * The shape is Commander's custom domains, applied to text: every kind carries one **baked-in
 * action** — dial, write, open, map, search, copy — that always fires through the system default
 * and is always the first chip; after it come the **apps the person added**, any installed app at
 * all, one chip each in the float. A custom category is a name, the person's own regex, and its
 * own list of apps.
 *
 * The only other per-kind decision is how strict its reader is — the same exact/fuzzy switch the
 * duplicate rules carry, with the same meaning.
 *
 * Stored as JSON strings in the settings DataStore; this object owns both directions of that
 * encoding so the repository stays a plain key-value store.
 */
object LiveViewCategories {

    /** The per-kind choices: reader strictness, and the added apps' packages in float order. */
    data class Prefs(val fuzzy: Boolean = false, val apps: List<String> = emptyList())

    /** One category of the person's own. [pattern] is stored as text and compiled at use — an
     *  entry whose pattern no longer compiles is skipped, never a crash. */
    data class Custom(val name: String, val pattern: String, val apps: List<String> = emptyList())

    /** The kinds a settings screen offers choices for. ACCOUNT is absent by design — its checksum
     *  cannot be fuzzed and its baked-in copy needs no companions. */
    val CONFIGURABLE: List<LineEntities.Kind> = listOf(
        LineEntities.Kind.PHONE,
        LineEntities.Kind.EMAIL,
        LineEntities.Kind.URL,
        LineEntities.Kind.ADDRESS,
        LineEntities.Kind.GENERIC
    )

    /** Whether [kind] has a fuzzy tier at all. */
    fun fuzzable(kind: LineEntities.Kind): Boolean = kind != LineEntities.Kind.GENERIC

    // --- per-kind prefs <-> JSON ---

    fun prefsFromJson(json: String?): Map<LineEntities.Kind, Prefs> {
        if (json.isNullOrBlank()) return emptyMap()
        return try {
            val obj = JSONObject(json)
            buildMap {
                obj.keys().forEach { key ->
                    val kind = runCatching { LineEntities.Kind.valueOf(key) }.getOrNull() ?: return@forEach
                    val entry = obj.getJSONObject(key)
                    put(
                        kind,
                        Prefs(
                            fuzzy = entry.optBoolean("fuzzy", false),
                            apps = stringList(entry.optJSONArray("apps"))
                        )
                    )
                }
            }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    fun prefsToJson(prefs: Map<LineEntities.Kind, Prefs>): String {
        val obj = JSONObject()
        prefs.forEach { (kind, p) ->
            obj.put(kind.name, JSONObject().apply {
                put("fuzzy", p.fuzzy)
                put("apps", JSONArray(p.apps))
            })
        }
        return obj.toString()
    }

    // --- custom categories <-> JSON ---

    fun customFromJson(json: String?): List<Custom> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val array = JSONArray(json)
            (0 until array.length()).mapNotNull { i ->
                val o = array.optJSONObject(i) ?: return@mapNotNull null
                val name = o.optString("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val pattern = o.optString("pattern").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                Custom(name, pattern, stringList(o.optJSONArray("apps")))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun customToJson(custom: List<Custom>): String {
        val array = JSONArray()
        custom.forEach { c ->
            array.put(JSONObject().apply {
                put("name", c.name)
                put("pattern", c.pattern)
                put("apps", JSONArray(c.apps))
            })
        }
        return array.toString()
    }

    private fun stringList(array: JSONArray?): List<String> {
        array ?: return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            array.optString(i).takeIf { it.isNotBlank() }
        }
    }

    /**
     * The classifier options these choices amount to. Custom patterns compile here, once per
     * settings change rather than once per line; one that does not compile is dropped — a broken
     * regex should cost its own category, not the screen.
     */
    fun optionsOf(prefs: Map<LineEntities.Kind, Prefs>, custom: List<Custom>): LineEntities.Options =
        LineEntities.Options(
            fuzzyKinds = prefs.filterValues { it.fuzzy }.keys,
            custom = custom.mapNotNull { c ->
                runCatching { LineEntities.CustomCategory(c.name, Regex(c.pattern)) }.getOrNull()
            }
        )
}
