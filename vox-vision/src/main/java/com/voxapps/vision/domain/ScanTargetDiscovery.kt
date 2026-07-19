package com.voxapps.vision.domain

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.voxapps.ipc.VoxIpc

/** A same-signature app that can receive a scan's raw OCR text (see [VoxIpc.META_OCR_TASK]). */
data class ScanTarget(val packageName: String, val task: String, val label: String)

/**
 * Discovers installed apps that can receive a Vision scan — mirrors Commander's
 * `VoxAppsDiscovery.discover()` (`queryBroadcastReceivers` + `GET_META_DATA`), just scoped to the
 * OCR-result contract instead of the command contract. A new satellite (e.g. a future Calendar app)
 * needs zero Vision code changes to show up here: it only has to declare an `OcrResultReceiver` with
 * the `com.voxapps.vox.ocr.task` meta-data, guarded by the shared `:core:ipc`-declared
 * `com.voxapps.vox.permission.OCR_RESULT` permission.
 */
object ScanTargetDiscovery {
    fun discover(context: Context): List<ScanTarget> {
        val pm = context.packageManager
        // Defense in depth only — :core:ipc guarantees Vision holds this once it's wired, so this
        // should never actually filter anything out in practice.
        if (pm.checkPermission(VoxIpc.PERMISSION_OCR_RESULT, context.packageName) != PackageManager.PERMISSION_GRANTED) {
            return emptyList()
        }
        return pm.queryBroadcastReceivers(Intent(VoxIpc.ACTION_OCR_RESULT), PackageManager.GET_META_DATA)
            .mapNotNull { ri ->
                val info = ri.activityInfo ?: return@mapNotNull null
                if (info.packageName == context.packageName) return@mapNotNull null
                val task = info.metaData?.getString(VoxIpc.META_OCR_TASK) ?: return@mapNotNull null
                // Strip the "Vox " prefix every app's launcher label carries (e.g. "Vox Calendar" ->
                // "Calendar") — this screen is already Vision's own Vox-scoped scan flow, so the
                // prefix is redundant, and the shorter label is what actually fits the equal-width
                // send buttons without truncating (three targets sharing a row leaves little room).
                val fullLabel = info.applicationInfo?.loadLabel(pm)?.toString() ?: info.packageName
                val label = fullLabel.removePrefix("Vox ").trim().ifEmpty { fullLabel }
                ScanTarget(info.packageName, task, label)
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }
}
