package com.voxcommander.app.data.remote

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import com.voxcommander.app.data.preferences.SettingsRepository
import com.voxcommander.app.utils.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Manages downloading, enabling, and disabling the Whisper native libraries (.so files).
 * Libraries are downloaded to filesDir/whisper_libs/ and loaded via System.load().
 */
class WhisperEngineManager(
    private val context: Context,
    private val settingsRepo: SettingsRepository
) {
    companion object {
        private const val TAG = "WhisperEngineManager"
        private const val LIB_DIR_NAME = "whisper_libs"

        // GitHub release URL for the .so files
        // TODO: Replace with actual release URL after uploading
        private const val WHISPER_LIBS_BASE_URL =
            "https://github.com/razvan-eduard/VoxCommander/releases/download/whisper-libs/"

        // The .so files that make up the Whisper engine (in load order)
        val WHISPER_LIBS = listOf(
            "libomp.so",
            "libggml.so",
            "libggml-base.so",
            "libggml-cpu.so",
            "libggml-vulkan.so",
            "libwhisper.so"
        )
    }

    val libDir: File
        get() = File(context.filesDir, LIB_DIR_NAME)

    /**
     * Checks if all Whisper .so files are present in filesDir/whisper_libs/.
     */
    fun areLibsDownloaded(): Boolean {
        return WHISPER_LIBS.all { File(libDir, it).exists() }
    }

    /**
     * Checks if Whisper is available — either system-installed (debug builds) or downloaded.
     */
    fun isWhisperAvailable(): Boolean {
        // Check system nativeLibraryDir first
        val systemDir = File(context.applicationInfo.nativeLibraryDir)
        val systemHasLibs = WHISPER_LIBS.all { File(systemDir, it).exists() }
        if (systemHasLibs) return true
        // Check downloaded libs
        return areLibsDownloaded()
    }

    /**
     * Downloads all Whisper .so files to filesDir/whisper_libs/.
     * Returns true if all downloads were enqueued successfully.
     * Uses OkHttp for direct file downloads (more control than DownloadManager for multiple files).
     */
    suspend fun downloadLibs(
        onProgress: (Float) -> Unit = {}
    ): Boolean = withContext(Dispatchers.IO) {
        if (!libDir.exists()) libDir.mkdirs()

        val client = okhttp3.OkHttpClient()
        var downloadedCount = 0
        val totalFiles = WHISPER_LIBS.size

        for (libName in WHISPER_LIBS) {
            val targetFile = File(libDir, libName)
            if (targetFile.exists()) {
                Logger.log("$libName already exists, skipping", TAG)
                downloadedCount++
                onProgress(downloadedCount.toFloat() / totalFiles)
                continue
            }

            val url = WHISPER_LIBS_BASE_URL + libName
            Logger.log("Downloading $libName from $url", TAG)

            try {
                val request = okhttp3.Request.Builder().url(url).build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Logger.log("Failed to download $libName: HTTP ${response.code}", TAG)
                        return@withContext false
                    }

                    response.body?.byteStream()?.use { input ->
                        targetFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                }

                Logger.log("Downloaded $libName (${targetFile.length()} bytes)", TAG)
                downloadedCount++
                onProgress(downloadedCount.toFloat() / totalFiles)
            } catch (e: Exception) {
                Logger.log("Error downloading $libName: ${e.message}", TAG)
                // Clean up partial download
                targetFile.delete()
                return@withContext false
            }
        }

        Logger.log("All Whisper libs downloaded successfully", TAG)
        true
    }

    /**
     * Enables the Whisper engine: downloads libs if needed, sets the flag, triggers app restart.
     * Returns true if libs are ready (either already present or just downloaded).
     */
    suspend fun enable(onProgress: (Float) -> Unit = {}): Boolean = withContext(Dispatchers.IO) {
        if (isWhisperAvailable()) {
            Logger.log("Whisper libs already available, just enabling", TAG)
            settingsRepo.setWhisperSystemEnabled(true)
            return@withContext true
        }

        val success = downloadLibs(onProgress)
        if (success) {
            settingsRepo.setWhisperSystemEnabled(true)
            Logger.log("Whisper engine enabled successfully", TAG)
        } else {
            Logger.log("Failed to download Whisper libs", TAG)
        }
        success
    }

    /**
     * Disables the Whisper engine and optionally deletes the downloaded libs and models.
     * @param deleteLibs If true, removes the .so files from filesDir to free space.
     * @param deleteModels If true, removes all Whisper (.bin) model files from external files dir.
     */
    suspend fun disable(deleteLibs: Boolean = true, deleteModels: Boolean = true) = withContext(Dispatchers.IO) {
        settingsRepo.setWhisperSystemEnabled(false)
        if (deleteLibs) {
            if (libDir.exists()) {
                libDir.listFiles()?.forEach { it.delete() }
                libDir.delete()
                Logger.log("Deleted Whisper libs from ${libDir.absolutePath}", TAG)
            }
        }
        if (deleteModels) {
            val modelsDir = context.getExternalFilesDir(null)
            if (modelsDir != null && modelsDir.exists()) {
                modelsDir.listFiles()?.filter { it.name.endsWith(".bin") }?.forEach { file ->
                    Logger.log("Deleting Whisper model: ${file.name}", TAG)
                    file.delete()
                    // Clear downloaded flag so UI updates
                    val modelId = file.name.removeSuffix(".bin")
                    settingsRepo.setModelDownloaded(modelId, false)
                }
            }
            // Clear custom model path and active model ID
            val whisperKey = com.voxcommander.app.data.remote.RemoteModelRegistry.getEngineKeyByExtension(".bin")
            whisperKey?.let {
                settingsRepo.setCustomModelPath(it, "")
            }
            settingsRepo.setEngineModelSelection(whisperKey ?: "", "")
            settingsRepo.setActiveVoiceModelId(null)
        }
        Logger.log("Whisper engine disabled (libs deleted=$deleteLibs, models deleted=$deleteModels)", TAG)
    }
}
