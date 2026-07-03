package com.voxcommander.app.data.remote

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import com.voxcommander.app.data.preferences.SettingsRepository
import com.voxcommander.app.state.AppStateManager
import com.voxcommander.app.utils.Logger
import com.voxcommander.app.utils.Strings
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
    }

    /**
     * Resolves the local File object for a given model.
     * Handles both directory-based (Vosk) and file-based (Whisper/NLU) models.
     */
    fun resolveLocalFile(modelId: String, engineKey: String): File? {
        val rootDir = context.getExternalFilesDir(null) ?: return null
        val extension = RemoteModelRegistry.getExtension(engineKey)

        return if (RemoteModelRegistry.isZipEngine(engineKey)) {
            // ZIP engines: downloaded as .zip, unzipped to a directory named just modelId (no extension)
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
        val downloadDir = if (RemoteModelRegistry.isZipEngine(engineKey)) {
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

        // ZIP-based engines go to temporary Downloads dir for unzip, others directly to root
        val destination = if (RemoteModelRegistry.isZipEngine(engineKey)) Environment.DIRECTORY_DOWNLOADS else null
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
     * Unzips ZIP-based models from temporary downloads to app root.
     * @param modelId Model identifier (without extension)
     * @param engineKey Engine key from models.json
     */
    fun unzipModel(modelId: String, engineKey: String, onComplete: (Boolean) -> Unit) {
        val extension = RemoteModelRegistry.getExtension(engineKey)
        val zipFile = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "$modelId$extension")
        val targetDir = resolveLocalFile(modelId, engineKey) ?: return onComplete(false)

        if (!zipFile.exists()) {
            Logger.log("Unzip failed: ZIP file not found: ${zipFile.absolutePath}", TAG)
            onComplete(false)
            return
        }

        // Write a .downloading marker so we can detect incomplete extractions after a crash
        val marker = File(targetDir.parentFile, "$modelId.downloading")
        try {
            if (targetDir.exists()) targetDir.deleteRecursively()
            targetDir.mkdirs()
            marker.writeText("extracting")

            ZipInputStream(FileInputStream(zipFile)).use { zis ->
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
            marker.delete()
            zipFile.delete()
            Logger.log("Unzip successful: $modelId", TAG)
            onComplete(true)
        } catch (e: Exception) {
            Logger.log("Unzip failed for $modelId: ${e.message}", TAG)
            // Leave the marker — cleanup will detect it on next startup
            onComplete(false)
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

        // Check for 'am' subdirectory — may be directly in model dir or in a nested subdirectory
        val hasAmDir = File(targetDir, "am").exists() ||
            targetDir.listFiles()?.any { File(it, "am").exists() } == true
        if (!hasAmDir) {
            Logger.log("Model $modelId missing 'am' directory — deleting incomplete model", TAG)
            targetDir.deleteRecursively()
            return false
        }

        return true
    }

    /**
     * Agnostic cleanup of unused models.
     * Protects only active voice + intent models. Everything else is purged.
     */
    fun deleteUnusedModels(
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
            
            kotlinx.coroutines.runBlocking { settingsRepo.setModelDownloaded(modelId, false) }
            val snapshot = settingsRepo.getSettingsSnapshot()
            if (snapshot.defaultVoiceFallbackModel == modelId) {
                val activeVoice = snapshot.activeVoiceModelId
                if (activeVoice != null && activeVoice != modelId && snapshot.isModelDownloaded(activeVoice)) {
                    kotlinx.coroutines.runBlocking { settingsRepo.setDefaultVoiceFallback(snapshot.voiceProcessor, activeVoice) }
                } else {
                    kotlinx.coroutines.runBlocking { settingsRepo.clearDefaultVoiceFallback() }
                }
            }
            if (snapshot.defaultIntentFallbackModel == modelId) {
                val activeIntent = snapshot.activeIntentModelId
                if (activeIntent != null && activeIntent != modelId && snapshot.isModelDownloaded(activeIntent)) {
                    kotlinx.coroutines.runBlocking { settingsRepo.setDefaultIntentFallback(snapshot.aiProcessor, activeIntent) }
                } else {
                    kotlinx.coroutines.runBlocking { settingsRepo.clearDefaultIntentFallback() }
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
