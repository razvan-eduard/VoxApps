package com.voxapps.nativelibs

import android.content.Context
import com.voxapps.identity.VoxRepo
import com.voxapps.logging.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Native libraries that may live inside the APK or be fetched from this build's own GitHub release.
 *
 * Commander and Vision each had their own copy of this — 240 and 241 lines, the same seven
 * functions, differing only in a tag prefix and a list of file names. They had drifted: Vision
 * gained per-file retries, atomic writes, version-scoped directories and a hard failure when a lib
 * is missing, while Commander kept logging "CRITICAL" and carrying on to crash later somewhere less
 * obvious. Both DLC bugs this month had to be fixed twice. This is Vision's implementation, shared.
 *
 * Subclassed as an `object` per app so existing call sites keep working unchanged:
 *
 *     object NativeLibManager : NativeLibs(
 *         tagPrefix = "vision",
 *         versionName = BuildConfig.VERSION_NAME,
 *         libs = listOf(…),
 *         bundled = BuildConfig.DLC_MODE == "minimal"
 *     )
 */
open class NativeLibs(
    /** Release-tag prefix for this app — `vision` gives `vision-v<versionName>`. */
    private val tagPrefix: String,
    /** Compile-time constant, so it cannot disagree with the running build. */
    private val versionName: String,
    /**
     * The libraries, **in load order**.
     *
     * Order matters because [loadAll] uses `System.load()` for fetched files, which — unlike the
     * dynamic linker resolving a `System.loadLibrary()` — needs each dependency loaded already.
     */
    val libs: List<String>,
    /**
     * Whether this build ships these libraries inside the APK.
     *
     * Comes from `BuildConfig.DLC_MODE`, which the build script sets from the `voxDlc` property, so
     * the packaging decision and this one are the same decision. Probing the filesystem instead
     * would turn a packaging bug into a silent download.
     */
    private val bundled: Boolean
) {
    private val tag = "NativeLibs/$tagPrefix"

    private val _status = MutableStateFlow(Status.IDLE)
    val status: StateFlow<Status> = _status.asStateFlow()

    private val _downloadProgress = MutableStateFlow(0f)
    val downloadProgress: StateFlow<Float> = _downloadProgress.asStateFlow()

    enum class Status { IDLE, CHECKING, DOWNLOADING, READY, ERROR }

    /** Explicit timeouts — OkHttp's 10s defaults are routinely exceeded by multi-MB libs on a slow
     *  connection, and a GitHub release download redirects to a CDN first. callTimeout stays unset
     *  so a slow but progressing transfer is not killed mid-file. Lazy: the common case downloads
     *  nothing. */
    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    private fun releaseTag(): String = "$tagPrefix-v$versionName"

    /**
     * What each library is expected to hash to, recorded by the build that produced this APK.
     *
     * Read from the APK's own assets, so it is covered by the APK's signature. A digest served from
     * the same place as the library would establish nothing — whoever can substitute one can
     * substitute the other — which is why this cannot be fetched alongside the download.
     *
     * Absent for a `minimal` build (nothing is downloaded) and for any build made before the digests
     * were recorded; an unknown library is then downloaded as before.
     */
    private fun expectedDigests(context: Context): Map<String, String> = runCatching {
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

    /** Version-scoped: these libs are not interchangeable between releases. */
    private fun libDir(context: Context): File =
        File(context.filesDir, "$LIB_DIR_PREFIX${releaseTag()}")

    private fun cleanupOldVersions(context: Context) {
        val current = "$LIB_DIR_PREFIX${releaseTag()}"
        context.filesDir.listFiles()?.forEach { file ->
            if (!file.isDirectory) return@forEach
            if (file.name.startsWith(LIB_DIR_PREFIX) && file.name != current) {
                Logger.d(tag, "Cleaning up old native libs version: ${file.name}")
                file.deleteRecursively()
            }
            if (file.name == "native_libs") file.deleteRecursively()  // pre-versioning layout
        }
    }

    /**
     * Present in the APK, or downloaded and non-empty.
     *
     * Only meaningful for a `full` build, which is the only caller: the APK check below can produce
     * a false negative when the libraries were packaged without extraction (see [loadAll]), so a
     * bundled build must not decide anything with it — it loads what it ships and finds out.
     */
    fun areLibsPresent(context: Context): Boolean {
        val systemDir = File(context.applicationInfo.nativeLibraryDir)
        if (libs.all { File(systemDir, it).exists() }) return true

        val dir = libDir(context)
        return libs.all { File(dir, it).let { f -> f.exists() && f.length() > 0 } }
    }

    /**
     * Whether one named library is on device — in the APK's nativeLibraryDir or fetched into the
     * version-scoped directory. For diagnostics display only: it carries the same
     * extraction-dependent caveat as [areLibsPresent], so nothing loads or decides based on it.
     */
    fun hasLib(context: Context, name: String): Boolean {
        if (File(context.applicationInfo.nativeLibraryDir, name).exists()) return true
        return File(libDir(context), name).let { it.exists() && it.length() > 0 }
    }

    /** Call once at startup, from the splash. */
    suspend fun init(context: Context) = withContext(Dispatchers.IO) {
        if (_status.value == Status.READY) return@withContext

        // Nothing to check and nothing to fetch when they shipped inside the APK: the splash has no
        // reason to wait, and a first launch works with no network at all.
        if (bundled) {
            runCatching { loadAll(context) }
                .onSuccess { _status.value = Status.READY }
                .onFailure {
                    Logger.e(tag, "Bundled libs failed to load: ${it.message}")
                    _status.value = Status.ERROR
                }
            return@withContext
        }

        _status.value = Status.CHECKING

        if (areLibsPresent(context)) {
            try {
                loadAll(context)
                _status.value = Status.READY
                // Only after this version is loaded and running: removing the previous version's
                // directory any earlier turns a failed download into a device with no working
                // libraries at all — the old set cannot be loaded by this version, but it is the
                // only thing left to fall back on until the new set is actually in service.
                cleanupOldVersions(context)
            } catch (e: Throwable) {
                Logger.e(tag, "Native load failed for existing files: ${e.message}")
                libDir(context).deleteRecursively()
                triggerDownload(context)
            }
        } else {
            triggerDownload(context)
        }
    }

    private suspend fun triggerDownload(context: Context) {
        if (downloadLibs(context)) {
            try {
                loadAll(context)
                _status.value = Status.READY
                // Same reasoning as in init(): superseded directories go only once this version
                // reached READY.
                cleanupOldVersions(context)
            } catch (e: Throwable) {
                Logger.e(tag, "Native load failed after download: ${e.message}")
                _status.value = Status.ERROR
            }
        } else {
            _status.value = Status.ERROR
        }
    }

    private suspend fun downloadLibs(context: Context): Boolean = withContext(Dispatchers.IO) {
        _status.value = Status.DOWNLOADING
        val dir = libDir(context)
        if (!dir.exists()) dir.mkdirs()

        val digests = expectedDigests(context)
        // Said once per download run, not per file: without the recorded digests every fetched
        // library is accepted as-is, and that state should never be silent.
        if (digests.isEmpty()) {
            Logger.e(tag, "No ${DIGEST_ASSET} recorded in this APK — downloads are unverified")
        }
        var completed = 0
        for (libName in libs) {
            val target = File(dir, libName)
            if (target.exists() && target.length() > 0) {
                completed++
                _downloadProgress.value = completed.toFloat() / libs.size
                continue
            }
            if (!downloadOne("$RELEASE_BASE${releaseTag()}/$libName", dir, libName, digests[libName])) {
                return@withContext false
            }
            completed++
            _downloadProgress.value = completed.toFloat() / libs.size
        }
        true
    }

    /**
     * One lib, retried up to [MAX_ATTEMPTS] times.
     *
     * A single failure used to abort the whole multi-file download and leave [Status.ERROR], so one
     * transient stall meant relaunching the app to make any further progress. Completed files are
     * skipped by the caller, so a retry only re-fetches what failed.
     */
    private suspend fun downloadOne(url: String, dir: File, libName: String, expected: String?): Boolean {
        val target = File(dir, libName)
        val temp = File(dir, "$libName.tmp")
        repeat(MAX_ATTEMPTS) { attempt ->
            try {
                Logger.d(tag, "Downloading $libName (attempt ${attempt + 1}/$MAX_ATTEMPTS)")
                httpClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
                    // 404 means this release genuinely has no such asset — unlike a timeout or 5xx,
                    // retrying cannot help, so fail fast rather than burning the attempt budget.
                    if (response.code == 404) {
                        Logger.e(tag, "$libName not found at $url — not retrying")
                        return false
                    }
                    if (!response.isSuccessful) error("HTTP ${response.code}")
                    response.body?.byteStream()?.use { input ->
                        temp.outputStream().use { output -> input.copyTo(output) }
                    } ?: error("empty response body")
                }
                // Checked before the rename, so a file that fails never becomes the library the app
                // loads. Treated as a failed attempt rather than a hard stop: a truncated transfer
                // fails this too, and that is worth retrying.
                if (expected != null && temp.exists()) {
                    val actual = sha256Of(temp)
                    if (actual != expected) {
                        temp.delete()
                        error("sha256 mismatch: expected $expected, got $actual")
                    }
                }
                // Written to .tmp and renamed, so an interrupted download never leaves a
                // half-written file that looks present to areLibsPresent.
                if (temp.exists() && temp.length() > 0) {
                    temp.renameTo(target)
                    return true
                }
                error("downloaded file was empty")
            } catch (e: Exception) {
                Logger.e(tag, "Failed to download $libName (attempt ${attempt + 1}): ${e.message}")
                temp.delete()
                if (attempt < MAX_ATTEMPTS - 1) delay(RETRY_DELAY_MS * (attempt + 1))
            }
        }
        return false
    }

    /**
     * Loads every lib, from the APK where present and from the download directory otherwise.
     *
     * Throws rather than logging and continuing: a missing lib surfaces later as an
     * UnsatisfiedLinkError somewhere unrelated, and the splash can act on a thrown failure.
     */
    fun loadAll(context: Context) {
        val dir = libDir(context)

        for (libName in libs) {
            try {
                // Asked for, not probed for. Whether a bundled library exists as a *file* under
                // nativeLibraryDir depends on how the APK was packaged: with extractNativeLibs=false
                // — AGP's default, and what vox-vision builds with — the libraries are mapped
                // straight out of the APK and never unpacked, so `File(nativeLibraryDir, name)`
                // reports missing for a library that is present and perfectly loadable. Probing that
                // path is what made a `minimal` Vision build fail on its own bundled libs.
                System.loadLibrary(libName.removePrefix("lib").removeSuffix(".so"))
            } catch (e: UnsatisfiedLinkError) {
                // Not in the APK: this is a `full` build, so use the copy fetched from the release.
                val fetched = File(dir, libName)
                if (fetched.exists() && fetched.length() > 0) {
                    // Dropped to read-only first. Android already warns on every one of these —
                    // "Attempt to load writable file … This will throw on a future Android version"
                    // — because loading executable code a process can still rewrite is the pattern
                    // being closed off. Done here rather than at download time so it also covers
                    // files an earlier version left behind.
                    fetched.setReadOnly()
                    System.load(fetched.absolutePath)
                } else {
                    throw IllegalStateException("Missing native library: $libName", e)
                }
            }
        }
    }

    private companion object {
        const val LIB_DIR_PREFIX = "native_libs_"
        const val DIGEST_ASSET = "dlc-libs.sha256"
        const val RELEASE_BASE = VoxRepo.RELEASE_DOWNLOAD_BASE
        const val MAX_ATTEMPTS = 3
        const val RETRY_DELAY_MS = 1500L
    }
}
