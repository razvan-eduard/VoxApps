package com.voxapps.backup

import org.json.JSONObject

/**
 * The outer wrapper document format Vox Hub's `ExportImportUtil` defines
 * (`{"exported_at":..., "schema_version":1, "apps":{"<domain>":{...}}}`). Deliberately duplicated
 * here rather than shared as a dependency — Hub's own code isn't touched by this module at all, so
 * its behavior stays at zero risk. A cross-compatibility test (see this module's test sources)
 * keeps the two copies from silently drifting apart.
 */
object VoxBackupDocument {
    const val SCHEMA_VERSION = 1

    /** Wraps [domain]'s export JSON in the same shape Hub produces (just a single "apps" entry),
     *  so a file written locally by one app is importable via Hub without Hub needing any
     *  awareness of where it came from. */
    fun build(domain: String, exportJson: String): String {
        val root = JSONObject()
        root.put("exported_at", System.currentTimeMillis())
        root.put("schema_version", SCHEMA_VERSION)
        val appsObj = JSONObject()
        appsObj.put(domain, JSONObject(exportJson))
        root.put("apps", appsObj)
        return root.toString(2)
    }

    /**
     * Returns [domain]'s sub-object with the outer `exported_at` injected (needed by the
     * createdAt-filtered delete pass in each app's import handler), or null if [domain] isn't
     * present — e.g. a Hub backup that didn't include this app, or [text] isn't this document
     * shape at all (an old raw per-app JSON file, or a completely unrelated file).
     */
    fun parseForDomain(text: String, domain: String): JSONObject? {
        val root = JSONObject(text)
        val exportedAt = root.optLong("exported_at", 0L)
        val appsObj = root.optJSONObject("apps") ?: return null
        val domainObj = appsObj.optJSONObject(domain) ?: return null
        return domainObj.apply { put("exported_at", exportedAt) }
    }
}
