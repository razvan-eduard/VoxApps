package com.voxapps.hub.domain

import org.json.JSONArray
import org.json.JSONObject

/**
 * Pure JSON assembly/parsing for the combined export document, extracted from the UI so it's
 * unit-testable without Android. Document shape:
 * `{ "exported_at": <millis>, "schema_version": 1, "missing_apps": [...], "apps": { "<domain>": {...} } }`
 * — `<domain>` matches the same string each satellite advertises via `com.voxapps.vox.domain`
 * meta-data (e.g. "notes", "expenses"), so the export/import side and NLU-routing side agree on
 * naming for free.
 */
object ExportImportUtil {
    const val SCHEMA_VERSION = 1

    private const val KEY_MISSING_APPS = "missing_apps"

    /**
     * @param appsData domain -> that app's raw [com.voxapps.ipc.VoxResult.text] JSON from OP_EXPORT.
     * @param missingApps labels of apps that were *selected* for this backup but produced nothing —
     *   unreachable, or their export failed. Recorded in the document itself, not just in the UI of
     *   the moment: a backup missing an app is otherwise indistinguishable from a complete one once
     *   the screen is dismissed, and the file may be restored months later on another device. Read
     *   back by [missingAppsIn] and shown before an import runs.
     *
     * Additive and optional — a document written before this field existed simply has no
     * `missing_apps` key and reads back as an empty list, so [SCHEMA_VERSION] is unchanged.
     */
    fun buildExportDocument(appsData: Map<String, String>, missingApps: List<String> = emptyList()): String {
        val root = JSONObject()
        root.put("exported_at", System.currentTimeMillis())
        root.put("schema_version", SCHEMA_VERSION)
        if (missingApps.isNotEmpty()) root.put(KEY_MISSING_APPS, JSONArray(missingApps))
        val appsObj = JSONObject()
        for ((domain, json) in appsData) {
            appsObj.put(domain, JSONObject(json))
        }
        root.put("apps", appsObj)
        return root.toString(2)
    }

    /** Returns domain -> that app's sub-object, ready to hand to [com.voxapps.ipc.VoxDataTransferClient.requestImport].
     *  Also injects the outer wrapper's `exported_at` into each per-domain object — it would
     *  otherwise be discarded here and never reach a satellite's import() call, which needs it to
     *  distinguish "existed at export time, safe to replace" from "created after export, must
     *  survive" (see ExpensesExportImportHandler/NotesExportImportHandler's createdAt-filtered
     *  delete). */
    fun parseImportDocument(text: String): Map<String, JSONObject> {
        val root = JSONObject(text)
        val exportedAt = root.optLong("exported_at", 0L)
        val appsObj = root.optJSONObject("apps") ?: return emptyMap()
        return appsObj.keys().asSequence().associateWith { key ->
            appsObj.getJSONObject(key).apply { put("exported_at", exportedAt) }
        }
    }

    /** Labels recorded by [buildExportDocument] for apps that were selected but contributed nothing.
     *  Empty for a complete backup, and for any document written before the field existed. */
    fun missingAppsIn(text: String): List<String> {
        val arr = JSONObject(text).optJSONArray(KEY_MISSING_APPS) ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
    }

    /** Per-domain record counts for the confirm-before-import summary UI (e.g. "42 notes, 3 categories"). */
    fun summarize(data: JSONObject): Map<String, Int> {
        val counts = mutableMapOf<String, Int>()
        for (key in listOf(
            "notes", "expenses", "categories", "spendingLimits", "events", "layers",
            "merchantCategoryMemory", "remapRules", "learnedFieldCorrections", "fastMapRules", "todoLists", "todoItems", "duplicateRules"
        )) {
            data.optJSONArray(key)?.let { if (it.length() > 0) counts[key] = it.length() }
        }
        return counts
    }
}
