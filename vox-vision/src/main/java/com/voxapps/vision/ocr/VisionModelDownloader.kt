package com.voxapps.vision.ocr

import android.content.Context
import com.voxapps.logging.Logger
import com.voxapps.vision.domain.OcrModelRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

private const val TAG = "VisionModelDownloader"

/**
 * Vision's models are downloaded at runtime into app-internal storage, never bundled in the APK or
 * committed to git — mirrors how vox-commander already handles Whisper/Vosk (see
 * `data/remote/ModelDownloader.kt` there), just trimmed down: Vision only ever needs 2-3 small files
 * (det model + one active zone's recognition model + its config), not a multi-engine registry.
 *
 * Every fetch lands in a `.tmp` beside its target, is checked against the digest the registry
 * declares, and only then renamed into place — an interrupted or wrong transfer never leaves a
 * file that [isReady] counts as present.
 *
 * Only one recognition zone's files ever sit on disk at a time — [switchZone] deletes the previous
 * zone's files after the new ones download successfully.
 */
class VisionModelDownloader(
    private val context: Context,
    private val registry: OcrModelRegistry
) {
    private val modelsDir: File get() = File(context.filesDir, "ocr_models").apply { mkdirs() }

    val detFile: File get() = File(modelsDir, "det.onnx")
    val recFile: File get() = File(modelsDir, "rec.onnx")
    val recConfigFile: File get() = File(modelsDir, "rec.yml")
    private val activeZoneFile: File get() = File(modelsDir, "active_zone.txt")

    fun activeZone(): String? = activeZoneFile.takeIf { it.exists() }?.readText()?.trim()?.ifBlank { null }

    fun isReady(zone: String): Boolean =
        detFile.exists() && recFile.exists() && recConfigFile.exists() && activeZone() == zone

    /** Ensures the detection model (universal, downloaded once) is present. */
    suspend fun ensureDetModel() = withContext(Dispatchers.IO) {
        if (detFile.exists()) return@withContext
        val det = registry.det() ?: error("No 'det' entry in ocr_models.json")
        val tmp = File(modelsDir, "det.onnx.tmp")
        try {
            fetchTo(det, tmp)
        } catch (e: Exception) {
            tmp.delete()
            throw e
        }
        if (!tmp.renameTo(detFile)) {
            tmp.delete()
            error("Could not finalise det.onnx")
        }
    }

    /** Downloads [newZone]'s recognition model + config, then removes the previous zone's files. */
    suspend fun switchZone(newZone: String) = withContext(Dispatchers.IO) {
        val rec = registry.rec(newZone) ?: error("No '$newZone' entry in ocr_models.json")
        val config = registry.config(newZone) ?: error("No '${newZone}_config' entry in ocr_models.json")

        val tmpRec = File(modelsDir, "rec.onnx.tmp")
        val tmpConfig = File(modelsDir, "rec.yml.tmp")
        try {
            fetchTo(rec, tmpRec)
            fetchTo(config, tmpConfig)
        } catch (e: Exception) {
            tmpRec.delete()
            tmpConfig.delete()
            throw e
        }

        // Only replace the active files once both new downloads arrived and verified.
        if (!tmpRec.renameTo(recFile) || !tmpConfig.renameTo(recConfigFile)) {
            tmpRec.delete()
            tmpConfig.delete()
            error("Could not finalise the '$newZone' recognition files")
        }
        activeZoneFile.writeText(newZone)

        Logger.d(TAG, "Switched OCR zone to '$newZone'")
    }

    /**
     * Fetches [source] into [tmp]: HTTP status checked, digest checked when the registry declares
     * one. Callers own [tmp]'s lifecycle — on any throw it still exists for them to delete.
     */
    private fun fetchTo(source: OcrModelRegistry.Entry, tmp: File) {
        Logger.d(TAG, "Downloading ${source.url} -> ${tmp.name}")
        val connection = URL(source.url).openConnection() as HttpURLConnection
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                error("HTTP ${connection.responseCode} for ${source.url}")
            }
            connection.inputStream.use { input ->
                tmp.outputStream().use { output -> input.copyTo(output) }
            }
        } finally {
            connection.disconnect()
        }

        val expected = source.sha256
        if (expected != null) {
            val actual = sha256Of(tmp)
            if (!actual.equals(expected, ignoreCase = true)) {
                error("${tmp.name} failed verification: expected $expected, got $actual")
            }
        } else {
            // Reported rather than passed over silently: it means the registry declares no digest
            // for this file, and every download of it is unchecked.
            Logger.d(TAG, "No sha256 for ${source.url} in ocr_models.json — downloaded without verification")
        }
    }

    private fun sha256Of(file: File): String =
        java.security.MessageDigest.getInstance("SHA-256").let { digest ->
            file.inputStream().use { input ->
                val buffer = ByteArray(1 shl 16)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }
}
