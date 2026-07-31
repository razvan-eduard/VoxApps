package com.voxapps.hub.data.preferences

import androidx.compose.runtime.Immutable
import com.voxapps.hub.domain.backup.AppBackupConfig

/**
 * Immutable snapshot of persisted Vox Hub settings.
 *
 * - [themeDarkMode]/[themeColored]: same theme controls as vox-commander's AppSettings — "SYSTEM"/
 *   "LIGHT"/"DARK" and Material You dynamic color, fed into the shared `:core:design` VoxTheme.
 * - [debugLoggingEnabled]/[debugToastsEnabled]: gate `com.voxapps.logging.Logger` output to logcat
 *   and to on-screen toasts respectively. Both off by default (mirrors every other satellite app's
 *   identical pair).
 * - [backupInterval]: how often [com.voxapps.hub.domain.backup.BackupWorker] runs automatically
 *   ([INTERVAL_OFF] = manual "Export" button only, unchanged from before this feature existed).
 * - [backupRetentionCount]: how many scheduled backups to keep on disk before pruning the oldest —
 *   [RETENTION_UNLIMITED] disables pruning entirely (the settings UI pairs this with a static
 *   storage-growth warning, since nothing here bounds it).
 * - [lastBackupSuccess]/[lastBackupTimestamp]/[lastBackupError]: the outcome of the most recent
 *   scheduled run, null until the first one completes. Surfaced as a dismissible banner in
 *   [com.voxapps.hub.ui.HubScreen] when a run failed — there's no other way to learn about it since
 *   the worker runs with no UI visible.
 * - [lastBackupMissingApps]: labels of apps [com.voxapps.hub.domain.backup.BackupWorker] couldn't
 *   reach (never woke up in time) or whose export failed on the most recent run that still produced
 *   a zip for the apps that *did* respond. Distinct from [lastBackupSuccess] = false (no zip at all)
 *   — a non-empty list here means the run "succeeded" but is missing data, which gets its own
 *   warning banner in [com.voxapps.hub.ui.HubScreen] rather than being indistinguishable from a
 *   clean run.
 * - [appBackupConfigs]: per-package [AppBackupConfig] (Settings/Data/API keys/Attachments), the single
 *   shared configuration driving both the manual Export button and scheduled [com.voxapps.hub.domain.backup.BackupWorker]
 *   runs — replaces the old global scope radio + secrets/photos checkboxes + app checklist. An app
 *   missing from this map falls back to [AppBackupConfig.DEFAULT] (see [com.voxapps.hub.domain.backup.configFor]).
 * - [voxConnectEnabled]/[voxConnectPort]: whether the VoxConnect Bridge (an embedded HTTP+WebSocket
 *   server, see `core:voxconnect`'s `VoxConnectServer`) is running, and which port it listens on.
 *   Device-local runtime state, not portable user data.
 * - [voxConnectMediaControlEnabled]: whether media-control requests are relayed to Commander at
 *   all — off by default, since it's a capability distinct from "which apps are monitored" (media
 *   control isn't a satellite domain, it goes through Commander's own OS notification-listener
 *   grant — see [com.voxapps.ipc.VoxIpc.OP_MEDIA_CONTROL]).
 * - [voxConnectMonitoredApps]: per-domain opt-in for what a paired VoxConnect device may read/command
 *   via `GET /apps`/`POST /command` — mirrors [appBackupConfigs]' shape (a flattened JSON map), just
 *   a single boolean per domain since this slice doesn't need Backup's finer Settings/Data granularity.
 */
@Immutable
data class HubSettings(
    val themeDarkMode: String = THEME_SYSTEM,
    val themeColored: Boolean = true,
    val debugLoggingEnabled: Boolean = false,
    val debugToastsEnabled: Boolean = false,
    val backupInterval: String = INTERVAL_OFF,
    val backupRetentionCount: Int = RETENTION_5,
    val lastBackupSuccess: Boolean? = null,
    val lastBackupTimestamp: Long? = null,
    val lastBackupError: String? = null,
    val lastBackupMissingApps: List<String> = emptyList(),
    val appBackupConfigs: Map<String, AppBackupConfig> = emptyMap(),
    val voxConnectEnabled: Boolean = false,
    val voxConnectPort: Int = VOXCONNECT_DEFAULT_PORT,
    val voxConnectMediaControlEnabled: Boolean = false,
    val voxConnectMonitoredApps: Map<String, Boolean> = emptyMap()
) {
    companion object {
        const val THEME_SYSTEM = "SYSTEM"
        const val THEME_LIGHT = "LIGHT"
        const val THEME_DARK = "DARK"

        const val INTERVAL_OFF = "OFF"
        const val INTERVAL_DAILY = "DAILY"
        const val INTERVAL_WEEKLY = "WEEKLY"
        const val INTERVAL_MONTHLY = "MONTHLY"

        const val RETENTION_NONE = 1
        const val RETENTION_2 = 2
        const val RETENTION_5 = 5
        const val RETENTION_10 = 10
        const val RETENTION_UNLIMITED = -1

        const val VOXCONNECT_DEFAULT_PORT = 8787
    }
}
