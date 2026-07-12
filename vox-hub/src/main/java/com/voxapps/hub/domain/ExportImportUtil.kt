package com.voxapps.hub.domain

import org.json.JSONObject

/**
 * Pure JSON assembly/parsing for the combined export document, extracted from the UI so it's
 * unit-testable without Android. Document shape:
 * `{ "exported_at": <millis>, "schema_version": 1, "apps": { "<domain>": {...per-app payload...} } }`
 * — `<domain>` matches the same string each satellite advertises via `com.voxapps.vox.domain`
 * meta-data (e.g. "notes", "expenses"), so the export/import side and NLU-routing side agree on
 * naming for free.
 */
object ExportImportUtil {
    const val SCHEMA_VERSION = 1

    /** [appsData] maps domain -> that app's raw [com.voxapps.ipc.VoxResult.text] JSON from OP_EXPORT. */
    fun buildExportDocument(appsData: Map<String, String>): String {
        val root = JSONObject()
        root.put("exported_at", System.currentTimeMillis())
        root.put("schema_version", SCHEMA_VERSION)
        val appsObj = JSONObject()
        for ((domain, json) in appsData) {
            appsObj.put(domain, JSONObject(json))
        }
        root.put("apps", appsObj)
        return root.toString(2)
    }

    /** Returns domain -> that app's sub-object, ready to hand to [com.voxapps.ipc.VoxDataTransferClient.requestImport]. */
    fun parseImportDocument(text: String): Map<String, JSONObject> {
        val root = JSONObject(text)
        val appsObj = root.optJSONObject("apps") ?: return emptyMap()
        return appsObj.keys().asSequence().associateWith { appsObj.getJSONObject(it) }
    }

    /** Per-domain record counts for the confirm-before-import summary UI (e.g. "42 notes, 3 categories"). */
    fun summarize(data: JSONObject): Map<String, Int> {
        val counts = mutableMapOf<String, Int>()
        for (key in listOf("notes", "expenses", "categories", "spendingLimits")) {
            data.optJSONArray(key)?.let { if (it.length() > 0) counts[key] = it.length() }
        }
        return counts
    }
}
