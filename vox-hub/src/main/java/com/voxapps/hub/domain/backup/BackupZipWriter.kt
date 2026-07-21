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
 * Builds the `export.json` (+ optional `expenses-receipts.zip`) archive shared by both the
 * interactive "Export" button ([com.voxapps.hub.ui.HubScreen]) and the scheduled [BackupWorker] —
 * the only difference between the two call sites is where [outputStream] points (a SAF-picked
 * `Uri` vs. a fixed on-disk `File`), so this is the one place the zip format itself is defined.
 */
object BackupZipWriter {
    fun write(
        outputStream: OutputStream,
        contentResolver: ContentResolver,
        perDomainJson: Map<String, String>,
        attachmentUriString: String?
    ) {
        val document = ExportImportUtil.buildExportDocument(perDomainJson)
        ZipOutputStream(outputStream).use { zos ->
            zos.putNextEntry(ZipEntry("export.json"))
            zos.write(document.toByteArray())
            zos.closeEntry()
            // "expenses" is the only domain that ever populates attachmentUriString today — matches
            // ExportImportUtil.summarize()'s existing convention of hardcoding known domain literals.
            attachmentUriString?.let { attachUriString ->
                try {
                    contentResolver.openInputStream(Uri.parse(attachUriString))?.use { input ->
                        zos.putNextEntry(ZipEntry("expenses-receipts.zip"))
                        input.copyTo(zos)
                        zos.closeEntry()
                    }
                } catch (e: Exception) {
                    Logger.w(TAG, "Failed to bundle receipt photos into export zip", e)
                }
            }
        }
    }
}
