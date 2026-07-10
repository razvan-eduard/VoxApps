package com.voxapps.vision.domain

import android.content.Context
import org.json.JSONObject

/**
 * Deliberately simpler than Commander's `models.json`/`RemoteModelRegistry` (which tracks several
 * engines with per-model metadata) — Vision only ever needs "which URL for which zone". Loaded from
 * `assets/ocr_models.json`, a flat map: `"det"` (the one universal detection model), `"<zone>"` (a
 * recognition model, e.g. `"latin"`), `"<zone>_config"` (that recognition model's character-dict
 * config). Adding a new zone (cyrillic, japan, ...) later is just two more JSON entries — no code
 * changes, since the Settings picklist reads [zones] directly.
 */
class OcrModelRegistry(context: Context) {

    private val entries: Map<String, String> = run {
        val json = context.assets.open("ocr_models.json").bufferedReader().use { it.readText() }
        val o = JSONObject(json)
        o.keys().asSequence().associateWith { o.getString(it) }
    }

    /** All recognition zone names (excludes "det" and "*_config" entries). */
    fun zones(): List<String> = entries.keys.filter { it != "det" && !it.endsWith("_config") }

    fun detUrl(): String? = entries["det"]

    fun recUrl(zone: String): String? = entries[zone]

    fun configUrl(zone: String): String? = entries["${zone}_config"]
}
