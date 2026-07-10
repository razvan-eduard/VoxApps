package com.voxapps.vision.ocr

import android.content.Context
import android.util.Log
import com.voxapps.vision.domain.OcrModelRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL

private const val TAG = "VisionModelDownloader"

/**
 * Vision's models are downloaded at runtime into app-internal storage, never bundled in the APK or
 * committed to git — mirrors how vox-commander already handles Whisper/Vosk (see
 * `data/remote/ModelDownloader.kt` there), just trimmed down: Vision only ever needs 2-3 small files
 * (det model + one active zone's recognition model + its config), not a multi-engine registry.
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
        val url = registry.detUrl() ?: error("No 'det' entry in ocr_models.json")
        downloadTo(url, detFile)
    }

    /** Downloads [newZone]'s recognition model + config, then removes the previous zone's files. */
    suspend fun switchZone(newZone: String) = withContext(Dispatchers.IO) {
        val recUrl = registry.recUrl(newZone) ?: error("No '$newZone' entry in ocr_models.json")
        val configUrl = registry.configUrl(newZone) ?: error("No '${newZone}_config' entry in ocr_models.json")

        val tmpRec = File(modelsDir, "rec.onnx.tmp")
        val tmpConfig = File(modelsDir, "rec.yml.tmp")
        downloadTo(recUrl, tmpRec)
        downloadTo(configUrl, tmpConfig)

        // Only replace the active files once both new downloads succeeded.
        tmpRec.copyTo(recFile, overwrite = true)
        tmpConfig.copyTo(recConfigFile, overwrite = true)
        tmpRec.delete()
        tmpConfig.delete()
        activeZoneFile.writeText(newZone)

        Log.d(TAG, "Switched OCR zone to '$newZone'")
    }

    private fun downloadTo(urlString: String, dest: File) {
        Log.d(TAG, "Downloading $urlString -> ${dest.name}")
        URL(urlString).openStream().use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        }
    }
}
