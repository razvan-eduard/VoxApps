package com.voxapps.commander.data.remote

import android.content.Context
import com.voxapps.commander.BuildConfig
import com.voxapps.logging.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Manages "Essential DLC" native libraries that are too large to bundle in the APK
 * for F-Droid/IzzyOnDroid limits (30MB).
 *
 * Downloads libraries to filesDir/native_libs/ and provides manual loading via System.load().
 */
object NativeLibManager {
    private const val TAG = "NativeLibManager"
    private const val LIB_DIR_NAME = "native_libs"

    private const val MAX_ATTEMPTS = 3
    private const val RETRY_DELAY_MS = 1500L

    /** Explicit timeouts — OkHttp's defaults are 10s, which these multi-MB libs routinely exceed on
     *  a slow connection (a GitHub release download also redirects to a CDN first). callTimeout is
     *  left unset so a slow but progressing transfer isn't killed mid-file. Lazy so the client isn't
     *  built unless a download actually happens — the common case is libs already present. Mirrors
     *  vox-expenses' ExchangeRateRepository, this repo's existing bare-OkHttp precedent. */
    private val httpClient by lazy {
        okhttp3.OkHttpClient.Builder()
            .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    // Base URL for the native libraries. Pointing to the main repo releases.
    private const val RELEASE_BASE = "https://github.com/razvan-eduard/VoxApps/releases/download/"

    // Libraries stripped from the release APK zip post-build (see
    // scripts/strip_dlc_libs.sh / release-commander.yml) rather than via AGP's
    // packaging.jniLibs.excludes, which proved unreliable on arm64-v8a for this dependency set
    // (see build.gradle.kts's release packaging comment / docs/BUILD_TIME_DEPENDENCIES.md for the
    // investigation) — plain zip removal sidesteps that bug entirely. Order matters:
    // libsherpa-onnx-jni.so's only external NEEDED entry is libonnxruntime.so (confirmed via
    // readelf), so onnxruntime must load first; liblitertlm_jni.so and libvosk.so are
    // self-contained (only system libs — confirmed via readelf on the real AAR artifact), so their
    // position relative to each other doesn't matter.
    val ESSENTIAL_LIBS = listOf(
        "libonnxruntime.so",
        "liblitertlm_jni.so",
        "libvosk.so",
        "libsherpa-onnx-jni.so"
    )

    /**
     * The release these libs are published under — always this exact build's own tag, since
     * [ESSENTIAL_LIBS] is version-specific (this app's set changed from
     * `libllm_inference_engine_jni.so` to `liblitertlm_jni.so` at the LiteRT-LM migration, so libs
     * from a *different* release are not interchangeable).
     *
     * [BuildConfig.VERSION_NAME] rather than a PackageManager lookup: it's a compile-time constant
     * baked from the same `versionName` the release tag is derived from (see
     * `.github/actions/compute-release-tag`), so it cannot throw and cannot disagree with the running
     * build. The old form queried PackageManager at runtime and therefore needed a catch — which
     * hardcoded `commander-v0.5-beta`, a release whose lib set no longer matches [ESSENTIAL_LIBS],
     * so that path would have 404'd had it ever run.
     */
    private fun getReleaseTag(): String = "commander-v${BuildConfig.VERSION_NAME}"

    private fun getDownloadUrl(libName: String): String {
        return "$RELEASE_BASE${getReleaseTag()}/$libName"
    }

    private val _downloadProgress = MutableStateFlow(0f)
    val downloadProgress: StateFlow<Float> = _downloadProgress.asStateFlow()

    private val _status = MutableStateFlow<Status>(Status.IDLE)
    val status: StateFlow<Status> = _status.asStateFlow()

    enum class Status { IDLE, CHECKING, DOWNLOADING, READY, ERROR }

    private fun getLibDir(context: Context): File = File(context.filesDir, LIB_DIR_NAME)

    /**
     * Checks if all essential libraries are already present locally.
     */
    fun areLibsPresent(context: Context): Boolean {
        // If we are in a debug build or any build that HAS them bundled, they will be in nativeLibraryDir
        val systemDir = File(context.applicationInfo.nativeLibraryDir)
        val allInSystem = ESSENTIAL_LIBS.all { File(systemDir, it).exists() }
        if (allInSystem) return true

        // Otherwise check our DLC folder
        val libDir = getLibDir(context)
        return ESSENTIAL_LIBS.all { File(libDir, it).exists() }
    }

    /**
     * Initializes and loads the libraries. Call this at app startup (Splash).
     */
    suspend fun init(context: Context) = withContext(Dispatchers.IO) {
        if (_status.value == Status.READY) return@withContext
        _status.value = Status.CHECKING

        if (areLibsPresent(context)) {
            loadAll(context)
            _status.value = Status.READY
        } else {
            // Trigger download
            val success = downloadLibs(context)
            if (success) {
                loadAll(context)
                _status.value = Status.READY
            } else {
                _status.value = Status.ERROR
            }
        }
    }

    private suspend fun downloadLibs(context: Context): Boolean = withContext(Dispatchers.IO) {
        _status.value = Status.DOWNLOADING
        val libDir = getLibDir(context)
        if (!libDir.exists()) libDir.mkdirs()

        var downloaded = 0
        val total = ESSENTIAL_LIBS.size

        for (libName in ESSENTIAL_LIBS) {
            val targetFile = File(libDir, libName)
            // Length check as well as existence: downloads land in a .tmp and are renamed into place
            // only once complete (see downloadOne), but an install predating that change can still
            // have left a truncated file here, which exists() alone would happily accept forever.
            if (targetFile.exists() && targetFile.length() > 0) {
                downloaded++
                _downloadProgress.value = downloaded.toFloat() / total
                continue
            }

            if (!downloadOne(getDownloadUrl(libName), libDir, libName)) {
                return@withContext false
            }
            downloaded++
            _downloadProgress.value = downloaded.toFloat() / total
        }
        true
    }

    /**
     * One lib, retried up to [MAX_ATTEMPTS] times, written to a temp file and renamed into place
     * only once fully received.
     *
     * Previously a single failure aborted the whole multi-file download and left [Status.ERROR], so
     * one transient stall meant the user had to notice and relaunch to make further progress. The
     * old code also streamed straight into the destination file, so an interrupted transfer left a
     * truncated .so that the next run treated as already downloaded — and `System.load()` on a
     * truncated library fails in a much more confusing way than a failed download does.
     */
    private suspend fun downloadOne(url: String, libDir: File, libName: String): Boolean {
        val targetFile = File(libDir, libName)
        val tempFile = File(libDir, "$libName.tmp")
        repeat(MAX_ATTEMPTS) { attempt ->
            try {
                Logger.log("Downloading essential lib: $libName from $url (attempt ${attempt + 1}/$MAX_ATTEMPTS)", TAG)
                httpClient.newCall(okhttp3.Request.Builder().url(url).build()).execute().use { response ->
                    // A 404 means this release has no such asset — retrying can't help, unlike a
                    // timeout or 5xx, so fail fast instead of burning the whole attempt budget.
                    if (response.code == 404) {
                        Logger.log("$libName not found at $url — not retrying", TAG)
                        return false
                    }
                    if (!response.isSuccessful) error("HTTP ${response.code}")
                    response.body?.byteStream()?.use { input ->
                        tempFile.outputStream().use { output -> input.copyTo(output) }
                    } ?: error("empty response body")
                }
                if (tempFile.exists() && tempFile.length() > 0) {
                    tempFile.renameTo(targetFile)
                    return true
                }
                error("downloaded file was empty")
            } catch (e: Exception) {
                Logger.log("Error downloading $libName (attempt ${attempt + 1}): ${e.message}", TAG)
                tempFile.delete()
                if (attempt < MAX_ATTEMPTS - 1) delay(RETRY_DELAY_MS * (attempt + 1))
            }
        }
        return false
    }

    /**
     * Manually loads the libraries using System.load() if they aren't in the default path.
     */
    fun loadAll(context: Context) {
        val systemDir = File(context.applicationInfo.nativeLibraryDir)
        val libDir = getLibDir(context)

        for (libName in ESSENTIAL_LIBS) {
            try {
                val systemFile = File(systemDir, libName)
                if (systemFile.exists()) {
                    // It's bundled in APK (Debug or non-minified build)
                    val libShortName = libName.removePrefix("lib").removeSuffix(".so")
                    System.loadLibrary(libShortName)
                    Logger.log("Loaded $libName from system path", TAG)
                } else {
                    // It's in DLC folder
                    val dlcFile = File(libDir, libName)
                    if (dlcFile.exists()) {
                        System.load(dlcFile.absolutePath)
                        Logger.log("Loaded $libName from DLC path: ${dlcFile.absolutePath}", TAG)
                    } else {
                        Logger.log("CRITICAL: $libName not found in system or DLC!", TAG)
                    }
                }
            } catch (e: Exception) {
                Logger.log("Failed to load $libName: ${e.message}", TAG)
            }
        }
    }
}
