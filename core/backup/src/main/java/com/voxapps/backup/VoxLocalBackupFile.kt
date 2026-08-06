package com.voxapps.backup

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import com.voxapps.logging.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

private const val TAG = "VoxLocalBackupFile"

/** [domainJson] is that domain's export sub-object (with `exported_at` already injected — see
 *  [VoxBackupDocument.parseForDomain]), ready to hand to a `*ExportImportHandler.import(...)` call.
 *  [attachmentUris] mirrors the `receiptsZipUri`/`attachmentsZipUri` fields each handler's import()
 *  already reads off the JSON — staged as local `file://` URIs (same process, no FileProvider grant
 *  needed, unlike Hub's cross-app staging). */
data class VoxLocalBackupPayload(val domainJson: JSONObject, val attachmentUris: Map<String, Uri>)

/**
 * Writes/reads a single-domain backup file in exactly the shape Vox Hub's own `BackupZipWriter`/
 * `HubScreen.readExportDocument` produce and consume — a file saved locally from inside one app is
 * importable via Hub, and vice versa. Deliberately a duplicate of that logic (see
 * [VoxBackupDocument]'s doc comment for why), not a shared dependency Hub is refactored onto.
 */
object VoxLocalBackupFile {

    /** [attachmentZipEntries] keys are the literal nested zip-entry names (e.g.
     *  `"$domain-attachments.zip"`, or Expenses' legacy `"expenses-receipts.zip"`) — same convention
     *  as [com.voxapps.hub.domain.backup.BackupZipWriter], mirrored here. */
    fun write(
        outputStream: OutputStream,
        contentResolver: ContentResolver,
        domain: String,
        exportJson: String,
        attachmentZipEntries: Map<String, Uri>
    ) {
        val document = VoxBackupDocument.build(domain, exportJson)
        ZipOutputStream(outputStream).use { zos ->
            zos.putNextEntry(ZipEntry("export.json"))
            zos.write(document.toByteArray())
            zos.closeEntry()
            for ((entryName, uri) in attachmentZipEntries) {
                try {
                    contentResolver.openInputStream(uri)?.use { input ->
                        zos.putNextEntry(ZipEntry(entryName))
                        input.copyTo(zos)
                        zos.closeEntry()
                    }
                } catch (e: Exception) {
                    Logger.w(TAG, "Failed to bundle $entryName into local backup zip", e)
                }
            }
        }
    }

    /**
     * Reads [uri] — either this object's own zip, or a full multi-domain Hub-produced backup (in
     * which case only [domain]'s slice is extracted) — mirroring `HubScreen.readExportDocument`'s
     * fallback chain: try as a zip first, fall back to treating the whole file as raw JSON text if
     * it isn't one. Returns null if [domain] isn't present in the file at all (e.g. a Hub backup
     * that didn't include this app).
     */
    suspend fun readForDomain(context: Context, uri: Uri, domain: String): VoxLocalBackupPayload? =
        withContext(Dispatchers.IO) {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@withContext null
            var documentText: String? = null
            val attachmentUris = mutableMapOf<String, Uri>()
            try {
                ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        val name = entry.name
                        when {
                            name == "export.json" -> documentText = zis.readBytes().decodeToString()
                            name == "expenses-receipts.zip" && domain == "expenses" ->
                                attachmentUris["receiptsZipUri"] = stageZipEntry(context, zis, domain)
                            name.endsWith("-attachments.zip") && name.removeSuffix("-attachments.zip") == domain ->
                                attachmentUris["attachmentsZipUri"] = stageZipEntry(context, zis, domain)
                        }
                        entry = zis.nextEntry
                    }
                }
            } catch (e: Exception) {
                documentText = null
            }
            val text = documentText ?: bytes.decodeToString()
            val domainJson = try {
                VoxBackupDocument.parseForDomain(text, domain)
            } catch (e: Exception) {
                null
            } ?: return@withContext null
            VoxLocalBackupPayload(domainJson, attachmentUris)
        }

    /** Copies one zip entry's bytes into `context.cacheDir/imports` and returns a local `file://`
     *  URI to it — same-process only (no FileProvider/grantUriPermission needed, unlike Hub's
     *  cross-app staging for the identical shape in `readExportDocument`). */
    private fun stageZipEntry(context: Context, zis: ZipInputStream, domain: String): Uri {
        val stagedDir = File(context.cacheDir, "imports").apply { mkdirs() }
        val stagedFile = File(stagedDir, "import_${domain}_${UUID.randomUUID()}.zip")
        FileOutputStream(stagedFile).use { fos -> zis.copyTo(fos) }
        return Uri.fromFile(stagedFile)
    }
}
