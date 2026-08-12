package com.voxapps.commander.data.remote

import android.content.Context
import com.voxapps.identity.VoxRepo
import com.voxapps.logging.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Manages downloading the llama.cpp runtime (libllama.so). Same contract as
 * [WhisperEngineManager], which this is a structural clone of: libraries live in one
 * build-independent directory, are tied to a build by a commit marker beside them, verified
 * against digests the APK's signature covers, and fetched from a release named for the
 * llama.cpp commit they were built from.
 *
 * No enable/disable pair here: the local LLM has no system-wide toggle the way Whisper does —
 * the libraries are wanted exactly when a local LLM engine is the active intent processor, and
 * the load path (LocalLlmInterpreter) asks [needsRefresh]/[downloadLibs] then.
 */
class LlamaEngineManager(
    private val context: Context,
    private val httpClient: okhttp3.OkHttpClient = okhttp3.OkHttpClient()
) {
    companion object {
        private const val TAG = "LlamaEngineManager"

        /**
         * One directory, named the same for every build; [libDir] is the only place any code
         * derives this path from, so the downloader and the loaders cannot disagree on it.
         * Contents are tied to a build by the [COMMIT_MARKER] beside them, not by the name.
         */
        private const val LIB_DIR_NAME = "llama_libs"

        /** Release tag prefix; the full tag is "$TAG_PREFIX-<llama.cpp sha12>". Unlike whisper
         *  there is no legacy bare tag — no install predates the recorded commit. */
        private const val TAG_PREFIX = "llama-libs"

        /** Written beside the libraries, naming the llama.cpp commit they were fetched for. */
        private const val COMMIT_MARKER = ".llama-commit"

        /** Both written into the APK by recordLlamaDigests; see vox-commander/build.gradle.kts. */
        private const val DIGEST_ASSET = "llama-libs.sha256"
        private const val COMMIT_ASSET = "llama-libs.commit"

        // The .so files that make up the llama engine, in load order. One file: ggml is linked in
        // statically and OpenMP is compiled out, so there is no dependency to order against.
        val LLAMA_LIBS = listOf(
            "libllama.so"
        )

        /**
         * Where the downloaded libraries live. Static so call sites without a manager instance —
         * diagnostics, benchmarks — derive the path from the same constant the downloader writes to.
         */
        fun libDir(context: Context): File = File(context.filesDir, LIB_DIR_NAME)
    }

    /**
     * The llama.cpp commit this build was compiled against, or null on a build that recorded none.
     */
    private val llamaCommit: String? by lazy {
        runCatching { context.assets.open(COMMIT_ASSET).bufferedReader().use { it.readText() }.trim() }
            .getOrNull()?.takeIf { it.length >= 12 }
    }

    /** The release these libraries come from — named for the build, so it cannot serve another.
     *  Null when the build recorded no commit: with no per-commit tag to ask, there is nothing
     *  this build can correctly download. */
    private val releaseTag: String?
        get() = llamaCommit?.let { "$TAG_PREFIX-${it.take(12)}" }

    val libDir: File
        get() = libDir(context)

    /**
     * Are the libraries on disk the ones this build asks for? Same marker logic as whisper's:
     * a marker naming another build means stale; a missing marker on an install that has the
     * files is accepted (they are what it has been running).
     */
    private fun libsAreStale(): Boolean {
        val expected = llamaCommit ?: return false
        val recorded = runCatching { File(libDir, COMMIT_MARKER).readText().trim() }.getOrNull()
            ?: return false
        return recorded != expected
    }

    fun areLibsDownloaded(): Boolean {
        if (libsAreStale()) return false
        return LLAMA_LIBS.all { File(libDir, it).exists() }
    }

    /** Available either bundled in the APK (nativeLibraryDir) or downloaded. */
    fun isLlamaAvailable(): Boolean {
        val systemDir = File(context.applicationInfo.nativeLibraryDir)
        if (LLAMA_LIBS.all { File(systemDir, it).exists() }) return true
        return areLibsDownloaded()
    }

    /**
     * True when the load path should bring the directory in line with this build before loading:
     * the commit marker names another build, or a listed file is missing or empty. False when the
     * APK carries the libraries itself — the directory is never consulted then.
     */
    fun needsRefresh(): Boolean {
        val systemDir = File(context.applicationInfo.nativeLibraryDir)
        if (LLAMA_LIBS.all { File(systemDir, it).exists() }) return false
        if (libsAreStale()) return true
        return !LLAMA_LIBS.all { File(libDir, it).length() > 0 }
    }

    /**
     * Replaces the on-disk libraries outright, then refetches. For the load path's one repair
     * attempt: bytes that exist but cannot be loaded tell nothing a digest check can use.
     */
    suspend fun repairLibs(): Boolean = withContext(Dispatchers.IO) {
        libDir.listFiles()?.forEach { it.delete() }
        downloadLibs()
    }

    /**
     * What each library should hash to, read from the asset the build records — inside the APK,
     * so its signature covers them. Same format as dlc-libs.sha256: "<sha256>  <name>" per line.
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
     * Downloads the llama .so files to [libDir]. Same discipline as whisper's: stale set removed
     * first, present files adopted only when they hash to the recorded digest, transfers land in
     * .tmp and are renamed after verification, marker written last so it is only ever present
     * beside a complete set.
     */
    suspend fun downloadLibs(
        onProgress: (Float) -> Unit = {}
    ): Boolean = withContext(Dispatchers.IO) {
        val tag = releaseTag
        if (tag == null) {
            Logger.log("This build recorded no llama.cpp commit — nothing correct to download", TAG)
            return@withContext false
        }
        if (libsAreStale()) {
            Logger.log("llama libs are from an earlier llama.cpp — refetching", TAG)
            libDir.listFiles()?.forEach { it.delete() }
        }
        if (!libDir.exists()) libDir.mkdirs()

        var downloadedCount = 0
        val totalFiles = LLAMA_LIBS.size
        val expected = expectedDigests()
        val baseUrl = VoxRepo.RELEASE_DOWNLOAD_BASE + tag + "/"

        for (libName in LLAMA_LIBS) {
            val targetFile = File(libDir, libName)
            val want = expected[libName]
            if (targetFile.exists()) {
                val keep = when {
                    targetFile.length() <= 0 -> false
                    want == null -> {
                        Logger.log("$libName already exists, keeping (no digest to check against)", TAG)
                        true
                    }
                    sha256Of(targetFile) == want -> {
                        Logger.log("$libName matches the recorded digest, keeping", TAG)
                        true
                    }
                    else -> false
                }
                if (keep) {
                    downloadedCount++
                    onProgress(downloadedCount.toFloat() / totalFiles)
                    continue
                }
                Logger.log("$libName on disk cannot be tied to this build — refetching", TAG)
                targetFile.delete()
            }

            val url = baseUrl + libName
            Logger.log("Downloading $libName from $url", TAG)

            val tempFile = File(libDir, "$libName.tmp")

            try {
                val request = okhttp3.Request.Builder().url(url).build()
                httpClient.newCall(request).execute().use { response ->
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

                if (want != null) {
                    val actual = sha256Of(tempFile)
                    if (actual != want) {
                        Logger.log("$libName failed verification: expected $want, got $actual", TAG)
                        tempFile.delete()
                        return@withContext false
                    }
                } else {
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
                tempFile.delete()
                return@withContext false
            }
        }

        // Written last, so it is only ever present beside a complete set.
        llamaCommit?.let { runCatching { File(libDir, COMMIT_MARKER).writeText(it) } }

        Logger.log("All llama libs downloaded successfully", TAG)
        true
    }
}
