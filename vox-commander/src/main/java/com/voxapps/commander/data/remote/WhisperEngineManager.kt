package com.voxapps.commander.data.remote

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import com.voxapps.commander.data.preferences.SettingsRepository
import com.voxapps.identity.VoxRepo
import com.voxapps.logging.Logger
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

        /**
         * One directory, named the same for every build.
         *
         * WhisperCppSttEngine, VulkanProbeService, BenchmarkEngine and AppStateManager all build
         * this path themselves rather than asking here, so a per-commit directory would be written
         * by the downloader and looked for by nobody.
         */
        private const val LIB_DIR_NAME = "whisper_libs"

        /**
         * The release a build with no recorded commit fetches from: one tag shared by every version
         * ever released, so what arrives is whatever was published last rather than what that build
         * expects. Kept because installs predating the recorded commit still ask for it.
         */
        private const val LEGACY_TAG = "whisper-libs"

        /** Written beside the libraries, naming the whisper.cpp commit they were fetched for. */
        private const val COMMIT_MARKER = ".whisper-commit"

        /** Both written into the APK by recordWhisperDigests; see vox-commander/build.gradle.kts. */
        private const val DIGEST_ASSET = "whisper-libs.sha256"
        private const val COMMIT_ASSET = "whisper-libs.commit"

        // The .so files that make up the Whisper engine, in load order: libwhisper.so needs
        // libomp.so, so libomp.so is loaded first.
        //
        // ggml is linked into libwhisper.so statically, so it contributes no file here.
        val WHISPER_LIBS = listOf(
            "libomp.so",
            "libwhisper.so"
        )
    }

    /**
     * The whisper.cpp commit this build was compiled against, or null on a build that recorded none.
     */
    private val whisperCommit: String? by lazy {
        runCatching { context.assets.open(COMMIT_ASSET).bufferedReader().use { it.readText() }.trim() }
            .getOrNull()?.takeIf { it.length >= 12 }
    }

    /** The release these libraries come from — named for the build, so it cannot serve another. */
    private val releaseTag: String
        get() = whisperCommit?.let { "$LEGACY_TAG-${it.take(12)}" } ?: LEGACY_TAG

    private val baseUrl: String
        get() = VoxRepo.RELEASE_DOWNLOAD_BASE + releaseTag + "/"

    val libDir: File
        get() = File(context.filesDir, LIB_DIR_NAME)

    /**
     * Are the libraries on disk the ones this build asks for?
     *
     * Presence alone cannot answer it: after a whisper.cpp bump the previous build's libraries are
     * still there, still named the same, and would be loaded instead of the ones the APK was
     * compiled against — the same disagreement the per-commit release tag removes for downloads.
     *
     * Answered from a marker rather than by hashing, so it costs a small read instead of ~107MB of
     * SHA-256 on every check. Libraries fetched before the marker existed carry none; those are
     * accepted, since they are what the install has been running and re-downloading them would cost
     * the user the whole payload to learn nothing.
     */
    private fun libsAreStale(): Boolean {
        val expected = whisperCommit ?: return false
        val recorded = runCatching { File(libDir, COMMIT_MARKER).readText().trim() }.getOrNull()
            ?: return false
        return recorded != expected
    }

    /**
     * Checks if all Whisper .so files are present in filesDir/whisper_libs/.
     */
    fun areLibsDownloaded(): Boolean {
        if (libsAreStale()) return false
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
     * What each library should hash to, read from the asset the build records.
     *
     * The digests are inside the APK, so its signature covers them; a digest fetched from the
     * release the library came from would prove nothing, since whoever can serve one can serve the
     * other. Same file format as core:nativelibs' dlc-libs.sha256 — "<sha256>  <name>" per line.
     *
     * An empty map means nothing can be verified; downloads then proceed unchecked, which is the
     * behaviour of a build that recorded no digests rather than a reason to refuse to run.
     */
    private fun expectedDigests(): Map<String, String> = runCatching {
        context.assets.open(DIGEST_ASSET).bufferedReader().useLines { lines ->
            lines.mapNotNull { line ->
                val parts = line.trim().split(Regex("\\s+"))
                if (parts.size == 2 && parts[0].length == 64) parts[1] to parts[0].lowercase() else null
            }.toMap()
        }
    }.getOrElse { emptyMap() }

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

    /**
     * Downloads all Whisper .so files to filesDir/whisper_libs/.
     * Returns true if all downloads were enqueued successfully.
     * Uses OkHttp for direct file downloads (more control than DownloadManager for multiple files).
     */
    suspend fun downloadLibs(
        onProgress: (Float) -> Unit = {}
    ): Boolean = withContext(Dispatchers.IO) {
        // Libraries from an earlier whisper.cpp are removed before the per-file existence check
        // below, which would otherwise treat them as already downloaded and keep loading them.
        if (libsAreStale()) {
            Logger.log("Whisper libs are from an earlier whisper.cpp — refetching", TAG)
            libDir.listFiles()?.forEach { it.delete() }
        }
        if (!libDir.exists()) libDir.mkdirs()

        val client = okhttp3.OkHttpClient()
        var downloadedCount = 0
        val totalFiles = WHISPER_LIBS.size

        val expected = expectedDigests()

        for (libName in WHISPER_LIBS) {
            val targetFile = File(libDir, libName)
            if (targetFile.exists()) {
                Logger.log("$libName already exists, skipping", TAG)
                downloadedCount++
                onProgress(downloadedCount.toFloat() / totalFiles)
                continue
            }

            val url = baseUrl + libName
            Logger.log("Downloading $libName from $url", TAG)

            // Written to .tmp and renamed once verified, so a transfer that is interrupted or serves
            // the wrong bytes never leaves a file that areLibsDownloaded() counts as present and
            // LibWhisper then loads.
            val tempFile = File(libDir, "$libName.tmp")

            try {
                val request = okhttp3.Request.Builder().url(url).build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Logger.log("Failed to download $libName: HTTP ${response.code}", TAG)
                        return@withContext false
                    }

                    response.body?.byteStream()?.use { input ->
                        tempFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                }

                val want = expected[libName]
                if (want != null) {
                    val actual = sha256Of(tempFile)
                    if (actual != want) {
                        Logger.log("$libName failed verification: expected $want, got $actual", TAG)
                        tempFile.delete()
                        return@withContext false
                    }
                } else {
                    // No recorded digest for this file. Reported rather than passed over silently:
                    // it means the build did not record one, and every later download of it is
                    // unchecked.
                    Logger.log("No recorded digest for $libName — downloaded without verification", TAG)
                }

                if (tempFile.length() <= 0 || !tempFile.renameTo(targetFile)) {
                    Logger.log("Could not finalise $libName", TAG)
                    tempFile.delete()
                    return@withContext false
                }

                Logger.log("Downloaded $libName (${targetFile.length()} bytes)", TAG)
                downloadedCount++
                onProgress(downloadedCount.toFloat() / totalFiles)
            } catch (e: Exception) {
                Logger.log("Error downloading $libName: ${e.message}", TAG)
                // Clean up partial download
                tempFile.delete()
                return@withContext false
            }
        }

        // Written last, so it is only ever present beside a complete set. A marker left by a partial
        // download would make the next launch treat those libraries as current.
        whisperCommit?.let { runCatching { File(libDir, COMMIT_MARKER).writeText(it) } }

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
            val whisperKey = com.voxapps.commander.domain.engine.whisper.WhisperCppSttEngine.ENGINE_KEY
            settingsRepo.setCustomModelPath(whisperKey, "")
            settingsRepo.setEngineModelSelection(whisperKey, "")
            settingsRepo.setActiveVoiceModelId(null)
        }
        Logger.log("Whisper engine disabled (libs deleted=$deleteLibs, models deleted=$deleteModels)", TAG)
    }
}
