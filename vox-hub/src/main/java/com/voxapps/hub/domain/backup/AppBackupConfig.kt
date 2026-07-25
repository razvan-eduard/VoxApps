package com.voxapps.hub.domain.backup

import org.json.JSONObject

/**
 * Per-app backup configuration — replaces the old global 3-way scope radio + 2 global checkboxes +
 * app include/exclude checklist with one persisted, per-package setting shared by both the manual
 * Export button and [BackupWorker]. [includeSettings]/[includeData] both false means "skip this app
 * entirely" (subsumes the old checklist — no separate master toggle). [includeApiKeys] only matters
 * with [includeSettings] on (secrets live inside the settings blob); [includeAttachments] only
 * matters with [includeData] on (attachments are part of an app's record data).
 */
data class AppBackupConfig(
    val includeSettings: Boolean = true,
    val includeData: Boolean = true,
    val includeApiKeys: Boolean = false,
    val includeAttachments: Boolean = false
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("includeSettings", includeSettings)
        put("includeData", includeData)
        put("includeApiKeys", includeApiKeys)
        put("includeAttachments", includeAttachments)
    }

    companion object {
        val DEFAULT = AppBackupConfig()

        fun fromJson(json: JSONObject): AppBackupConfig = AppBackupConfig(
            includeSettings = json.optBoolean("includeSettings", true),
            includeData = json.optBoolean("includeData", true),
            includeApiKeys = json.optBoolean("includeApiKeys", false),
            includeAttachments = json.optBoolean("includeAttachments", false)
        )

        /** Encodes the full per-package map as one JSON string for DataStore storage. */
        fun encodeMap(map: Map<String, AppBackupConfig>): String {
            val root = JSONObject()
            map.forEach { (pkg, cfg) -> root.put(pkg, cfg.toJson()) }
            return root.toString()
        }

        /** Decodes a DataStore string back into a map. Malformed/blank input yields an empty map —
         *  every app then falls back to [DEFAULT] via [configFor], not a crash. */
        fun decodeMap(json: String): Map<String, AppBackupConfig> = try {
            val root = JSONObject(json)
            root.keys().asSequence().associateWith { pkg -> fromJson(root.getJSONObject(pkg)) }
        } catch (e: Exception) {
            emptyMap()
        }
    }
}

/** An app not yet in the map (newly installed, or before this feature existed) gets [AppBackupConfig.DEFAULT]
 *  — matches the pre-this-feature defaults (scope BOTH, secrets off, photos off, every app included). */
fun Map<String, AppBackupConfig>.configFor(packageName: String): AppBackupConfig =
    this[packageName] ?: AppBackupConfig.DEFAULT
