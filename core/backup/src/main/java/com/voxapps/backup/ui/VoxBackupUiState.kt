package com.voxapps.backup.ui

import com.voxapps.backup.VoxImportMode

/**
 * What [VoxBackupSettingsCard] renders. The four `include*` booleans deliberately mirror Hub's own
 * `AppBackupConfig` shape/names exactly — this card and Hub's per-app chips represent one shared
 * concept (what this app's next local backup/restore includes), not two independently-drifting
 * toggle sets.
 */
data class VoxBackupUiState(
    val includeSettings: Boolean = true,
    val includeData: Boolean = true,
    val includeApiKeys: Boolean = false,
    val includeAttachments: Boolean = false,
    /** Governs only this app's own local restore-from-file action — an IPC-triggered import
     *  (whether from Hub's manual button or its scheduler) always carries its own explicit mode,
     *  completely independent of this value. */
    val importMode: VoxImportMode = VoxImportMode.MERGE,
    val isBusy: Boolean = false,
    val lastResultMessage: String? = null
)

/** Every feature defaults to visible; each independently toggleable per host app — e.g. Commander
 *  has no attachments concept (showAttachmentsToggle=false), Notes/Calendar have no secrets
 *  (showApiKeysToggle=false). [showSettingsToggle]/[showDataToggle] exist for Hub's own card, which
 *  backs up nothing but its own settings blob — no "Data" concept of its own to toggle. */
data class VoxBackupCardFeatures(
    val showSettingsToggle: Boolean = true,
    val showDataToggle: Boolean = true,
    val showApiKeysToggle: Boolean = true,
    val showAttachmentsToggle: Boolean = true,
    val showImportModeSelector: Boolean = true,
    val showRestoreButton: Boolean = true
)

data class VoxBackupStrings(
    val sectionTitle: String = "Backup & Restore",
    val includeSettingsLabel: String = "Settings",
    val includeDataLabel: String = "Data",
    val includeApiKeysLabel: String = "API keys",
    val includeApiKeysDesc: String = "Includes real secrets in the backup file — only enable for a file you trust storing securely.",
    val includeAttachmentsLabel: String = "Attachments",
    val importModeLabel: String = "Restore mode",
    val importModeDesc: String = "How restoring a file reconciles it against what's already here. Only applies when Data is on.",
    val importModeFullOverride: String = "Full override",
    val importModeMerge: String = "Merge",
    val importModeAdditive: String = "Additive",
    val backupNowButton: String = "Back up now",
    val restoreButton: String = "Restore from file"
)

internal fun VoxBackupStrings.labelFor(mode: VoxImportMode): String = when (mode) {
    VoxImportMode.FULL_OVERRIDE -> importModeFullOverride
    VoxImportMode.MERGE -> importModeMerge
    VoxImportMode.ADDITIVE -> importModeAdditive
}
