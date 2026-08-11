package com.voxapps.vision.domain

import android.content.Context
import org.json.JSONObject

/**
 * Deliberately simpler than Commander's `models.json`/`RemoteModelRegistry` (which tracks several
 * engines with per-model metadata) — Vision only ever needs "which file for which zone". Loaded
 * from `assets/ocr_models.json`, a flat map of `"det"` (the one universal detection model),
 * `"<zone>"` (a recognition model, e.g. `"latin"`) and `"<zone>_config"` (that recognition model's
 * character-dict config) to `{url, sha256}` objects. Adding a new zone (cyrillic, japan, ...)
 * later is just two more JSON entries — no code changes, since the Settings picklist reads [zones]
 * directly.
 *
 * The asset is covered by the APK's signature, so the sha256 beside each URL is what binds the
 * address to the bytes that must arrive there — a digest served from the same host as the model
 * would prove nothing.
 */
class OcrModelRegistry(context: Context) {

    /** A downloadable file: where from, and — when recorded — what it must hash to. */
    data class Entry(val url: String, val sha256: String?)

    private val entries: Map<String, Entry> = run {
        val json = context.assets.open("ocr_models.json").bufferedReader().use { it.readText() }
        val o = JSONObject(json)
        o.keys().asSequence().associateWith { key ->
            val value = o.getJSONObject(key)
            Entry(value.getString("url"), value.optString("sha256").ifBlank { null })
        }
    }

    /** All recognition zone names (excludes "det" and "*_config" entries). */
    fun zones(): List<String> = entries.keys.filter { it != "det" && !it.endsWith("_config") }

    fun det(): Entry? = entries["det"]

    fun rec(zone: String): Entry? = entries[zone]

    fun config(zone: String): Entry? = entries["${zone}_config"]
}
