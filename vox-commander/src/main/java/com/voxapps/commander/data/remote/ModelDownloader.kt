package com.voxapps.commander.data.remote

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import com.voxapps.commander.data.preferences.SettingsRepository
import com.voxapps.commander.state.AppStateManager
import com.voxapps.commander.utils.Logger
import com.voxapps.commander.utils.Strings
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

/**
 * Agnostic Model Downloader & File Manager.
 * Uses RemoteModelRegistry as the SSOT for extensions and keys.
 */
class ModelDownloader(private val context: Context) {

    private val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

    companion object {
        private const val TAG = "ModelDownloader"
        private const val CLEANUP_TAG = "ModelCleanup"

        /**
         * If the extracted directory contains a single subdirectory (the archive's top-level
         * wrapper, e.g. Vosk's `vosk-model-.../` or Piper's `vits-piper-.../`), move its
         * contents up one level so the model files sit directly under [targetDir].
         *
         * IMPORTANT: uses renameTo (atomic same-fs move) and falls back to copyRecursively —
         * NOT File.copyTo, which is non-recursive for directories and would leave subdirectories
         * like `am/ conf/ graph/` empty (Vosk then fails with "Failed to create a model").
         *
         * Pure File logic (no Context) so it is unit-testable.
         */
        internal fun flattenNestedDir(targetDir: File) {
            val children = targetDir.listFiles() ?: return
            if (children.size == 1 && children[0].isDirectory) {
                val nested = children[0]
                Logger.log("Flattening nested directory: ${nested.name}", TAG)
                nested.listFiles()?.forEach { file ->
                    val dest = File(targetDir, file.name)
                    if (!file.renameTo(dest)) {
                        // Cross-filesystem or rename failure — recurse into subdirectories too.
                        file.copyRecursively(dest, overwrite = true)
                    }
                }
                nested.deleteRecursively()
            }
        }
    }

    /**
     * Resolves the local File object for a given model.
     * Handles both directory-based (Vosk) and file-based (Whisper/NLU) models.
     */
    fun resolveLocalFile(modelId: String, engineKey: String): File? {
        val rootDir = context.getExternalFilesDir(null) ?: return null
        val extension = RemoteModelRegistry.getExtension(engineKey)

        return if (RemoteModelRegistry.isZipEngine(engineKey) || extension.equals(".tar.bz2", ignoreCase = true)) {
            // Archive engines: downloaded as .zip/.tar.bz2, extracted to a directory named just modelId (no extension)
            File(rootDir, modelId)
        } else {
            // File-based engines: model stored as modelId + extension
            File(rootDir, "$modelId$extension")
        }
    }

    /**
     * Generic download method.
     */
    fun downloadModel(modelId: String, url: String, engineKey: String): Long {
        val extension = RemoteModelRegistry.getExtension(engineKey)
        val fileName = "$modelId$extension"

        // Pre-flight check
        val localFile = resolveLocalFile(modelId, engineKey)
        if (localFile?.exists() == true) {
            Logger.log("Model already exists: $modelId ($engineKey), skipping download", TAG)
            return -1L
        }

        // Clean up leftover download files to prevent DownloadManager adding -N suffix
        val isArchiveEngine = RemoteModelRegistry.isZipEngine(engineKey) ||
            RemoteModelRegistry.getExtension(engineKey).equals(".tar.bz2", ignoreCase = true)
        val downloadDir = if (isArchiveEngine) {
            context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        } else {
            context.getExternalFilesDir(null)
        }
        val leftoverFile = File(downloadDir, fileName)
        if (leftoverFile.exists()) {
            leftoverFile.delete()
            Logger.log("Cleaned up leftover file: ${leftoverFile.absolutePath}", TAG)
        }

        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle("Downloading Model ($modelId)")
            .setDescription("Preparing offline engine: $engineKey")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)

        // Archive-based engines go to temporary Downloads dir for extraction, others directly to root
        val destination = if (isArchiveEngine) Environment.DIRECTORY_DOWNLOADS else null
        request.setDestinationInExternalFilesDir(context, destination, fileName)

        // LLM-specific flags
        if (RemoteModelRegistry.isLlmEngine(engineKey)) {
            request.setAllowedOverMetered(true)
            request.setAllowedOverRoaming(true)
        }

        val downloadId = downloadManager.enqueue(request)
        Logger.log("Download started: modelId=$modelId, engine=$engineKey, downloadId=$downloadId", TAG)
        return downloadId
    }

    /**
     * Generic delete method.
     */
    fun deleteModelFile(modelId: String, engineKey: String) {
        val file = resolveLocalFile(modelId, engineKey)
        if (file?.exists() == true) {
            Logger.log("Deleting model: $modelId ($engineKey) at ${file.absolutePath}", TAG)
            file.deleteRecursively()
        } else {
            Logger.log("Model file not found for deletion: $modelId ($engineKey)", TAG)
        }
    }

    /**
     * Extracts archive-based models (.zip or .tar.bz2) from temporary downloads to app root.
     * @param modelId Model identifier (without extension)
     * @param engineKey Engine key from models.json
     */
    fun unzipModel(modelId: String, engineKey: String, onComplete: (Boolean) -> Unit) {
        val extension = RemoteModelRegistry.getExtension(engineKey)
        val archiveFile = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "$modelId$extension")
        val targetDir = resolveLocalFile(modelId, engineKey) ?: return onComplete(false)

        if (!archiveFile.exists()) {
            Logger.log("Extraction failed: archive file not found: ${archiveFile.absolutePath}", TAG)
            onComplete(false)
            return
        }

        // Write a .downloading marker so we can detect incomplete extractions after a crash
        val marker = File(targetDir.parentFile, "$modelId.downloading")
        try {
            if (targetDir.exists()) targetDir.deleteRecursively()
            targetDir.mkdirs()
            marker.writeText("extracting")

            if (extension.equals(".tar.bz2", ignoreCase = true)) {
                extractTarBz2(archiveFile, targetDir)
            } else {
                ZipInputStream(FileInputStream(archiveFile)).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        val newFile = File(targetDir, entry.name)
                        if (entry.isDirectory) {
                            newFile.mkdirs()
                        } else {
                            newFile.parentFile?.mkdirs()
                            FileOutputStream(newFile).use { fos ->
                                zis.copyTo(fos)
                            }
                        }
                        entry = zis.nextEntry
                    }
                }
            }

            // Flatten nested top-level directory (e.g. vits-piper-en_US-amy-low/ contains model.onnx etc.)
            flattenNestedDir(targetDir)

            marker.delete()
            archiveFile.delete()
            Logger.log("Extraction successful: $modelId", TAG)
            onComplete(true)
        } catch (e: Exception) {
            Logger.log("Extraction failed for $modelId: ${e.message}", TAG)
            // Leave the marker — cleanup will detect it on next startup
            onComplete(false)
        }
    }

    /**
     * Extracts a .tar.bz2 archive to the target directory.
     */
    private fun extractTarBz2(archiveFile: File, targetDir: File) {
        FileInputStream(archiveFile).use { fis ->
            BufferedInputStream(fis).use { bis ->
                BZip2CompressorInputStream(bis).use { bzis ->
                    TarArchiveInputStream(bzis).use { tis ->
                        var entry: TarArchiveEntry? = tis.nextEntry as? TarArchiveEntry
                        while (entry != null) {
                            val newFile = File(targetDir, entry.name)
                            if (entry.isDirectory) {
                                newFile.mkdirs()
                            } else {
                                newFile.parentFile?.mkdirs()
                                FileOutputStream(newFile).use { fos ->
                                    tis.copyTo(fos)
                                }
                            }
                            entry = tis.nextEntry as? TarArchiveEntry
                        }
                    }
                }
            }
        }
    }

    /**
     * Checks if a model directory is complete and valid.
     * Deletes incomplete/corrupt directories (e.g. if app crashed during unzip).
     * Returns true if the model is valid and ready to use.
     */
    fun validateModel(modelId: String, engineKey: String): Boolean {
        val targetDir = resolveLocalFile(modelId, engineKey) ?: return false
        val marker = File(targetDir.parentFile, "$modelId.downloading")

        // If marker exists, the extraction was interrupted — clean up
        if (marker.exists()) {
            Logger.log("Model $modelId has incomplete extraction marker — deleting corrupt directory", TAG)
            targetDir.deleteRecursively()
            marker.delete()
            return false
        }

        if (!targetDir.exists() || !targetDir.isDirectory) return false

        val extension = RemoteModelRegistry.getExtension(engineKey)

        // Piper TTS models: check for model.onnx
        if (extension.equals(".tar.bz2", ignoreCase = true)) {
            val hasModel = File(targetDir, "model.onnx").exists()
            if (!hasModel) {
                Logger.log("Piper model $modelId missing 'model.onnx' — deleting incomplete model", TAG)
                targetDir.deleteRecursively()
                return false
            }
            return true
        }

        // Vosk/ZIP models must have a NON-EMPTY 'am' directory DIRECTLY under the model dir —
        // Vosk's Model(path) cannot load a nested layout, and an empty 'am' means a broken
        // extraction (e.g. the old non-recursive flatten left subdirs hollow).
        fun amPopulated(dir: File): Boolean {
            val am = File(dir, "am")
            return am.isDirectory && (am.listFiles()?.isNotEmpty() == true)
        }
        // Self-heal a legacy/interrupted extraction that left the archive's wrapper dir in place
        // (e.g. model/model/am/...): flatten it so 'am' sits directly under the model dir.
        if (!amPopulated(targetDir)) {
            flattenNestedDir(targetDir)
        }
        if (!amPopulated(targetDir)) {
            Logger.log("Model $modelId missing/empty 'am' directory — deleting incomplete model", TAG)
            targetDir.deleteRecursively()
            return false
        }

        return true
    }

    /**
     * Agnostic cleanup of unused models.
     * Protects only active voice + intent models. Everything else is purged.
     */
    suspend fun deleteUnusedModels(
        settingsRepo: SettingsRepository,
        activeVoiceModelId: String?,
        activeIntentModelId: String?,
        appStateManager: AppStateManager? = null,
        activeWakeModelId: String? = null
    ) {
        val rootDir = context.getExternalFilesDir(null) ?: return
        val downloadsDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)

        // 1. Build protected set: only active models, resolved against their actual engine
        val protectedNames = mutableSetOf<String>()

        // Essential system items
        protectedNames.addAll(listOf("Download", "transcriptions", "logs"))

        val snapshot = settingsRepo.getSettingsSnapshot()

        // Protect active voice model — resolve only against the active voice engine
        activeVoiceModelId?.let { id ->
            resolveLocalFile(id, snapshot.voiceProcessor)?.let { protectedNames.add(it.name) }
        }

        // Protect active intent model — resolve only against the active intent engine
        activeIntentModelId?.let { id ->
            resolveLocalFile(id, snapshot.aiProcessor)?.let { protectedNames.add(it.name) }
        }

        // Protect wake word model — resolve only against the active wake word engine
        activeWakeModelId?.let { id ->
            resolveLocalFile(id, snapshot.wakeWordEngineType)?.let { protectedNames.add(it.name) }
        }

        val engineKeys = RemoteModelRegistry.getEngineTypes()

        Logger.log("Cleanup started. Protected items: $protectedNames", CLEANUP_TAG)

        // 2. Wipe EVERYTHING else in root
        rootDir.listFiles()?.forEach { file ->
            val name = file.name
            if (name in protectedNames) {
                Logger.log("Keeping protected item: $name", CLEANUP_TAG)
                return@forEach
            }

            Logger.log("DELETING unused item: ${file.absolutePath}", CLEANUP_TAG)
            file.deleteRecursively()

            // Sync settings: extract modelId from filename
            // For file-based engines: strip extension. For zip engines: directory name IS the modelId.
            var modelId = name
            engineKeys.forEach { key ->
                val ext = RemoteModelRegistry.getExtension(key)
                if (ext.isNotBlank() && name.endsWith(ext)) {
                    modelId = name.removeSuffix(ext)
                }
            }
            
            settingsRepo.setModelDownloaded(modelId, false)
            val snapshot = settingsRepo.getSettingsSnapshot()
            if (snapshot.defaultVoiceFallbackModel == modelId) {
                val activeVoice = snapshot.activeVoiceModelId
                if (activeVoice != null && activeVoice != modelId && snapshot.isModelDownloaded(activeVoice)) {
                    settingsRepo.setDefaultVoiceFallback(snapshot.voiceProcessor, activeVoice)
                } else {
                    settingsRepo.clearDefaultVoiceFallback()
                }
            }
            if (snapshot.defaultIntentFallbackModel == modelId) {
                val activeIntent = snapshot.activeIntentModelId
                if (activeIntent != null && activeIntent != modelId && snapshot.isModelDownloaded(activeIntent)) {
                    settingsRepo.setDefaultIntentFallback(snapshot.aiProcessor, activeIntent)
                } else {
                    settingsRepo.clearDefaultIntentFallback()
                }
            }
        }

        // 3. Clean temporary Downloads
        downloadsDir?.listFiles()?.forEach { file ->
            val isKnownZip = engineKeys.any { key ->
                val ext = RemoteModelRegistry.getExtension(key)
                ext.isNotBlank() && file.name.endsWith(ext)
            }
            if (isKnownZip) {
                file.delete()
                Logger.log("Deleted ZIP from downloads: ${file.name}", CLEANUP_TAG)
            }
        }

        Logger.log("Cleanup complete.", CLEANUP_TAG)
        appStateManager?.refreshAll()
    }
}
