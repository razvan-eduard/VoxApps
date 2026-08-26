package com.voxapps.vision.domain.liveview

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.voxapps.apppicker.AppPickerEntry

/**
 * The apps a LiveView handler can be pointed at: all of them.
 *
 * Deliberately the full installed list rather than the resolvers of one intent — a chat partner
 * may be Signal, Viber, WhatsApp or something this code has never heard of, and a closed intent
 * query cannot enumerate that (and is OEM-filtered besides — see LauncherAppsCache's identical
 * finding in vox-expenses, whose approach this mirrors). The picker's own search does the
 * narrowing; a pick that cannot take the intent falls back to the system default at fire time
 * rather than failing the tap.
 */
object LiveViewApps {

    @Volatile private var cached: List<AppPickerEntry> = emptyList()

    /** Every installed app, sorted by name. Scanned once per process and reused — the pickers on
     *  one settings page should not re-enumerate the package manager five times. */
    fun installedApps(context: Context): List<AppPickerEntry> {
        cached.takeIf { it.isNotEmpty() }?.let { return it }
        val pm = context.packageManager
        val scanned = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .map { app ->
                AppPickerEntry(
                    packageName = app.packageName,
                    displayName = pm.getApplicationLabel(app).toString(),
                    isSystemApp = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                )
            }
            .distinctBy { it.packageName }
            .filter { it.packageName != context.packageName }
            .sortedBy { it.displayName.lowercase() }
        cached = scanned
        return scanned
    }
}
