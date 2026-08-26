package com.voxapps.backup

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The one filename convention every backup zip follows: `<app>-backup-yyyy-MM-dd_HH-mm-ss.zip`.
 * Zero-padded and datetime-ordered, so a plain lexicographic sort of a backup folder is a
 * chronological sort — which is exactly what retention pruning leans on.
 */
object VoxBackupNames {
    private const val TIMESTAMP_PATTERN = "yyyy-MM-dd_HH-mm-ss"

    fun timestampedZip(appName: String, at: Date = Date()): String =
        "$appName-backup-${SimpleDateFormat(TIMESTAMP_PATTERN, Locale.US).format(at)}.zip"
}
