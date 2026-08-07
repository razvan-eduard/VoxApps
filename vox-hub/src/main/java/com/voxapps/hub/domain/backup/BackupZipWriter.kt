package com.voxapps.hub.domain.backup

import android.content.ContentResolver
import android.net.Uri
import com.voxapps.hub.domain.ExportImportUtil
import com.voxapps.logging.Logger
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

private const val TAG = "BackupZipWriter"

/**
 * Builds the `export.json` (+ optional per-domain attachment zips) archive shared by both the
 * interactive "Export" button ([com.voxapps.hub.ui.HubScreen]) and the scheduled [BackupWorker] —
 * the only difference between the two call sites is where [outputStream] points (a SAF-picked
 * `Uri` vs. a fixed on-disk `File`), so this is the one place the zip format itself is defined.
 *
 * [attachmentZipEntries] keys are the literal nested zip-entry names to write (not domain names) —
 * pushing that naming decision to the callers, which already know the difference between Expenses'
 * pre-existing receipts zip (`"expenses-receipts.zip"`, kept exactly as-is so already-created backup
 * files stay restorable) and any domain's newer attachments zip (`"$domain-attachments.zip"`, see
 * `:core:attachments`) — a single domain can legitimately contribute both as separate entries.
 */
object BackupZipWriter {
    fun write(
        outputStream: OutputStream,
        contentResolver: ContentResolver,
        perDomainJson: Map<String, String>,
        attachmentZipEntries: Map<String, String>,
        /** Labels of selected apps that contributed nothing — recorded inside export.json so the
         *  archive itself says it is partial. See ExportImportUtil.buildExportDocument. */
        missingApps: List<String> = emptyList()
    ) {
        val document = ExportImportUtil.buildExportDocument(perDomainJson, missingApps)
        ZipOutputStream(outputStream).use { zos ->
            zos.putNextEntry(ZipEntry("export.json"))
            zos.write(document.toByteArray())
            zos.closeEntry()
            for ((entryName, uriString) in attachmentZipEntries) {
                try {
                    contentResolver.openInputStream(Uri.parse(uriString))?.use { input ->
                        zos.putNextEntry(ZipEntry(entryName))
                        input.copyTo(zos)
                        zos.closeEntry()
                    }
                } catch (e: Exception) {
                    Logger.w(TAG, "Failed to bundle $entryName into export zip", e)
                }
            }
        }
    }
}
