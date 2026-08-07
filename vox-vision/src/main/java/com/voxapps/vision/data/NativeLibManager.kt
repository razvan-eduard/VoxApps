package com.voxapps.vision.data

import android.content.Context
import com.voxapps.vision.BuildConfig
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
 * Manages "Essential DLC" native libraries for VoxVision.
 * Features atomic downloads and integrity checks to prevent crashes.
 */
object NativeLibManager {
    private const val TAG = "NativeLibManager"
    private const val LIB_DIR_PREFIX = "native_libs_"
    private const val RELEASE_BASE = "https://github.com/razvan-eduard/VoxApps/releases/download/"

    private const val MAX_ATTEMPTS = 3
    private const val RETRY_DELAY_MS = 1500L

    /** Explicit timeouts — OkHttp's defaults are 10s, which these multi-MB libs routinely exceed on
     *  a slow connection (a GitHub release download also redirects to a CDN first). writeTimeout is
     *  irrelevant for a GET but is set for symmetry; callTimeout stays unset so a genuinely slow but
     *  progressing transfer isn't killed mid-file. Lazy so the client isn't built unless a download
     *  actually happens — the common case is libs already present. Mirrors vox-expenses'
     *  ExchangeRateRepository, the existing bare-OkHttp precedent in this repo. */
    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    // Order matters: loadAll() below uses System.load() (not loadLibrary()), which — unlike the
    // dynamic linker's own dependency resolution — requires each lib's dependencies to already be
    // loaded first. Chain (confirmed via `readelf -d`): core -> flann -> geometry -> imgproc ->
    // imgcodecs -> {features, ptcloud, stereo} -> java5. All six after core/imgcodecs are OpenCV
    // 5.0+ additions — java5's own NEEDED entries list libopencv_features.so/ptcloud.so/stereo.so
    // directly (OpenCV 5's java bindings link them unconditionally, even with calib3d/features2d
    // disabled at build time), which is easy to miss since nothing in this app calls their APIs.
    // Absent in OpenCV 4.x, where opencv_java4.so had no such deps.
    val ESSENTIAL_LIBS = listOf(
        "libonnxruntime.so",
        "libopencv_core.so",
        "libopencv_flann.so",
        "libopencv_geometry.so",
        "libopencv_imgproc.so",
        "libopencv_imgcodecs.so",
        "libopencv_features.so",
        "libopencv_ptcloud.so",
        "libopencv_stereo.so",
        "libopencv_java5.so"
    )

    private val _status = MutableStateFlow<Status>(Status.IDLE)
    val status: StateFlow<Status> = _status.asStateFlow()

    private val _downloadProgress = MutableStateFlow(0f)
    val downloadProgress: StateFlow<Float> = _downloadProgress.asStateFlow()

    enum class Status { IDLE, CHECKING, DOWNLOADING, READY, ERROR }

    private fun getLibDir(context: Context): File =
        File(context.filesDir, "$LIB_DIR_PREFIX${getReleaseTag()}")

    private fun cleanupOldVersions(context: Context) {
        val currentDirName = "$LIB_DIR_PREFIX${getReleaseTag()}"
        context.filesDir.listFiles()?.forEach { file ->
            if (file.isDirectory && file.name.startsWith(LIB_DIR_PREFIX) && file.name != currentDirName) {
                Logger.d(TAG, "Cleaning up old native libs version: ${file.name}")
                file.deleteRecursively()
            }
            // Also cleanup the legacy non-versioned directory if it exists
            if (file.isDirectory && file.name == "native_libs") {
                file.deleteRecursively()
            }
        }
    }

    /**
     * The release these libs are published under — always this exact build's own tag, since the
     * OpenCV set in [ESSENTIAL_LIBS] is version-specific and libs from a different release are not
     * interchangeable.
     *
     * [BuildConfig.VERSION_NAME] rather than a PackageManager lookup: it's a compile-time constant
     * baked from the same `versionName` the release tag is derived from (see
     * `.github/actions/compute-release-tag`), so it cannot throw and cannot disagree with the running
     * build. The old form queried PackageManager at runtime and therefore needed a catch — which
     * hardcoded `vision-v0.3`, a release that does not exist at all, so that path could only ever
     * have 404'd.
     */
    private fun getReleaseTag(): String = "vision-v${BuildConfig.VERSION_NAME}"

    /**
     * Verifies that all libs are present AND have non-zero size.
     */
    fun areLibsPresent(context: Context): Boolean {
        val systemDir = File(context.applicationInfo.nativeLibraryDir)
        if (ESSENTIAL_LIBS.all { File(systemDir, it).exists() }) return true
        
        val libDir = getLibDir(context)
        return ESSENTIAL_LIBS.all { 
            val f = File(libDir, it)
            f.exists() && f.length() > 0 
        }
    }

    suspend fun init(context: Context) = withContext(Dispatchers.IO) {
        if (_status.value == Status.READY) return@withContext
        _status.value = Status.CHECKING
        
        cleanupOldVersions(context)
        
        if (areLibsPresent(context)) {
            try {
                loadAll(context)
                _status.value = Status.READY
            } catch (e: Throwable) {
                Logger.e(TAG, "Native load failed for existing files: ${e.message}")
                // Cleanup potentially corrupt files and try redownload
                getLibDir(context).deleteRecursively()
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
            } catch (e: Throwable) {
                Logger.e(TAG, "Native load failed after download: ${e.message}")
                _status.value = Status.ERROR
            }
        } else {
            _status.value = Status.ERROR
        }
    }

    private suspend fun downloadLibs(context: Context): Boolean = withContext(Dispatchers.IO) {
        _status.value = Status.DOWNLOADING
        val libDir = getLibDir(context)
        if (!libDir.exists()) libDir.mkdirs()

        val client = httpClient
        val tag = getReleaseTag()
        var completed = 0

        for (libName in ESSENTIAL_LIBS) {
            val targetFile = File(libDir, libName)

            if (targetFile.exists() && targetFile.length() > 0) {
                completed++
                _downloadProgress.value = completed.toFloat() / ESSENTIAL_LIBS.size
                continue
            }

            if (!downloadOne(client, "$RELEASE_BASE$tag/$libName", libDir, libName)) {
                return@withContext false
            }
            completed++
            _downloadProgress.value = completed.toFloat() / ESSENTIAL_LIBS.size
        }
        true
    }

    /**
     * One lib, retried up to [MAX_ATTEMPTS] times. Previously a single failure anywhere aborted the
     * entire multi-file download and left [Status.ERROR], so one transient stall on a ~7 MB file
     * meant the user had to notice and relaunch the app to make any further progress — observed in
     * practice on a fresh 0.13 -> 0.14 upgrade. Already-completed files are skipped by the caller,
     * so a retry only re-fetches what actually failed.
     */
    private suspend fun downloadOne(
        client: OkHttpClient,
        url: String,
        libDir: File,
        libName: String
    ): Boolean {
        val targetFile = File(libDir, libName)
        val tempFile = File(libDir, "$libName.tmp")
        repeat(MAX_ATTEMPTS) { attempt ->
            try {
                Logger.d(TAG, "Downloading $libName from $url (attempt ${attempt + 1}/$MAX_ATTEMPTS)")
                client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                    // A 404 means this release genuinely has no such asset — retrying can't help,
                    // unlike a timeout/5xx, so fail fast rather than burning the full attempt budget.
                    if (response.code == 404) {
                        Logger.e(TAG, "$libName not found at $url — not retrying")
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
                Logger.e(TAG, "Failed to download $libName (attempt ${attempt + 1}): ${e.message}")
                tempFile.delete()
                if (attempt < MAX_ATTEMPTS - 1) delay(RETRY_DELAY_MS * (attempt + 1))
            }
        }
        return false
    }

    fun loadAll(context: Context) {
        val systemDir = File(context.applicationInfo.nativeLibraryDir)
        val libDir = getLibDir(context)

        for (libName in ESSENTIAL_LIBS) {
            val systemFile = File(systemDir, libName)
            if (systemFile.exists()) {
                System.loadLibrary(libName.removePrefix("lib").removeSuffix(".so"))
            } else {
                val dlcFile = File(libDir, libName)
                if (dlcFile.exists() && dlcFile.length() > 0) {
                    System.load(dlcFile.absolutePath)
                } else {
                    throw IllegalStateException("Missing native library: $libName")
                }
            }
        }
    }
}
