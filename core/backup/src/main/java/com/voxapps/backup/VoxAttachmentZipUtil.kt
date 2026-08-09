package com.voxapps.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.voxapps.ipc.VoxIpc
import com.voxapps.logging.Logger
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

private const val TAG = "VoxAttachmentZipUtil"

/**
 * Generalizes the near-identical attachment zip build/extract pairs every export/import handler in
 * this codebase used to hand-roll (Notes/Calendar/Expenses' two pairs). Best-effort throughout:
 * bundling attachments must never block the JSON export/import itself.
 */
object VoxAttachmentZipUtil {

    /**
     * Zips [fileNames] found under `context.filesDir/[dirName]` into a fresh file under
     * `context.cacheDir/exports` and grants [grantToPackage] read access. [sidecarSuffix] (e.g.
     * ".txt") additionally bundles each file's same-stem sidecar if present — used for Expenses'
     * OCR-text-retry files. Returns null (no attachment) on any failure or if nothing was found.
     */
    fun build(
        context: Context,
        dirName: String,
        fileNames: List<String>,
        fileProviderAuthority: String,
        grantToPackage: String = VoxIpc.HUB_PACKAGE,
        sidecarSuffix: String? = null,
        zipFilePrefix: String = "export_attachments"
    ): Uri? {
        if (fileNames.isEmpty()) return null
        val sourceDir = File(context.filesDir, dirName)
        return try {
            val exportsDir = File(context.cacheDir, "exports").apply { mkdirs() }
            val zipFile = File(exportsDir, "${zipFilePrefix}_${UUID.randomUUID()}.zip")
            var wroteAny = false
            ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
                for (name in fileNames) {
                    val file = File(sourceDir, name)
                    if (file.exists()) {
                        zos.putNextEntry(ZipEntry(name))
                        file.inputStream().use { it.copyTo(zos) }
                        zos.closeEntry()
                        wroteAny = true
                    }
                    if (sidecarSuffix != null) {
                        val sidecar = File(sourceDir, name.substringBeforeLast('.') + sidecarSuffix)
                        if (sidecar.exists()) {
                            zos.putNextEntry(ZipEntry(sidecar.name))
                            sidecar.inputStream().use { it.copyTo(zos) }
                            zos.closeEntry()
                        }
                    }
                }
            }
            if (!wroteAny) {
                zipFile.delete()
                return null
            }
            val uri = FileProvider.getUriForFile(context, fileProviderAuthority, zipFile)
            context.grantUriPermission(grantToPackage, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            uri
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to build attachments export zip", e)
            null
        }
    }

    /**
     * Extracts every entry of the zip at [uri] into `context.filesDir/[dirName]`, flattening every
     * entry name to its bare filename first (zip-slip defense) — identical shape in every
     * pre-existing per-app copy.
     */
    fun extract(context: Context, dirName: String, uri: Uri) {
        val targetDir = File(context.filesDir, dirName).apply { mkdirs() }
        context.contentResolver.openInputStream(uri)?.use { input ->
            ZipInputStream(input).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val safeName = File(entry.name).name
                        if (safeName.isNotBlank()) {
                            FileOutputStream(File(targetDir, safeName)).use { fos -> zis.copyTo(fos) }
                        }
                    }
                    entry = zis.nextEntry
                }
            }
        }
    }
}
